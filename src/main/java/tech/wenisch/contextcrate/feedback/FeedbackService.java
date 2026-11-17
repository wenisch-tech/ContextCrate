package tech.wenisch.contextcrate.feedback;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.domain.AnswerFeedback;
import tech.wenisch.contextcrate.domain.AnswerFeedbackSource;
import tech.wenisch.contextcrate.repository.AnswerFeedbackRepository;
import tech.wenisch.contextcrate.repository.AnswerFeedbackSourceRepository;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;
import tech.wenisch.contextcrate.storage.Hashing;

/**
 * Collects ratings for answers. Every rateable answer gets a random token; only its SHA-256 hash is stored.
 * The token is the credential of the public rating page, so it is long, unguessable and expires.
 */
@Service
public class FeedbackService {
  private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
  public static final int MAX_COMMENT = 2000;
  private static final String NO_ANSWER = AnswerService.NO_ANSWER;
  private static final SecureRandom RANDOM = new SecureRandom();

  /** What is handed to the client: the token (shown once) and the page where it can be used. */
  public record Ticket(UUID id, String token, String url, Instant expiresAt) {}

  private final AnswerFeedbackRepository feedback;
  private final AnswerFeedbackSourceRepository sources;
  private final NormalizedDocumentRepository documents;
  private final RagSettingsService settings;
  private final String publicUrl;

  public FeedbackService(AnswerFeedbackRepository feedback, AnswerFeedbackSourceRepository sources, NormalizedDocumentRepository documents,
      RagSettingsService settings, @Value("${contextcrate.public-url:}") String publicUrl) {
    this.feedback = feedback; this.sources = sources; this.documents = documents; this.settings = settings;
    this.publicUrl = publicUrl == null ? "" : publicUrl.strip().replaceAll("/+$", "");
  }

  public boolean enabled(UUID crateId) {
    try { return settings.current(crateId).isFeedbackEnabled(); } catch (RuntimeException e) { return false; }
  }

  /** The base URL used in links: the configured public URL, else the one of the current request (call on the request thread). */
  public String baseUrl() {
    if (!publicUrl.isEmpty()) return publicUrl;
    try { return org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString(); }
    catch (RuntimeException e) { return ""; }
  }

  /** Stores the answer for rating. Never throws; empty when feedback is off, nothing was retrieved or storing failed. */
  @Transactional
  public Optional<Ticket> open(AnswerService.Prepared prepared, String answerText, AnswerFeedback.Channel channel, String baseUrl) {
    try {
      if (prepared == null || answerText == null || answerText.isBlank() || NO_ANSWER.equals(answerText)) return Optional.empty();
      var policy = settings.current(prepared.crateId());
      if (!policy.isFeedbackEnabled()) return Optional.empty();
      byte[] bytes = new byte[32];
      RANDOM.nextBytes(bytes);
      String token = "fb_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      Instant expires = Instant.now().plus(Duration.ofHours(policy.getFeedbackLinkTtlHours()));
      var row = new AnswerFeedback(UUID.randomUUID(), prepared.crateId(), channel, actor(), expires, Hashing.sha256(token), prepared.question(), answerText);
      feedback.save(row);
      for (var s : prepared.sources()) {
        String identity = s.documentId() == null ? null : documents.findByIdAndCrateId(s.documentId(), prepared.crateId()).map(d -> d.getIdentityUri()).orElse(null);
        sources.save(new AnswerFeedbackSource(row.getId(), prepared.crateId(), s.citation(), s.chunkId(), s.documentId(), identity, s.title(), s.sourceUri()));
      }
      return Optional.of(new Ticket(row.getId(), token, baseUrl == null || baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "") + "/feedback/" + token, expires));
    } catch (RuntimeException e) {
      log.warn("Could not store an answer for feedback. crateId={}: {}", prepared == null ? null : prepared.crateId(), e.toString());
      return Optional.empty();
    }
  }

  /** The row behind a token, if it exists and has not expired. */
  @Transactional(readOnly = true)
  public Optional<AnswerFeedback> find(String token) {
    if (token == null || token.length() > 100 || !token.startsWith("fb_")) return Optional.empty();
    return feedback.findByTokenHash(Hashing.sha256(token)).filter(f -> !f.isExpired(Instant.now()));
  }

  /** Stores or replaces the ratings. A rating is 1 to 5; a {@code null} dimension keeps its value. */
  @Transactional
  public Optional<AnswerFeedback> rate(String token, Integer relevance, Integer quality, String comment) {
    validate(relevance); validate(quality);
    if (comment != null && comment.length() > MAX_COMMENT) throw new IllegalArgumentException("comment must not exceed " + MAX_COMMENT + " characters");
    var row = find(token);
    if (row.isEmpty()) return row;
    var f = row.get();
    f.rate(relevance, quality, comment);
    var policy = settings.current(f.getCrateId());
    if (f.getEnrichmentStatus() == AnswerFeedback.Enrichment.NONE || f.getEnrichmentStatus() == AnswerFeedback.Enrichment.CANDIDATE || f.getEnrichmentStatus() == AnswerFeedback.Enrichment.APPROVED) {
      boolean good = !sources.findByFeedbackId(f.getId()).isEmpty() && f.getQuality() != null && f.getRelevance() != null
          && f.getQuality() >= policy.getFeedbackEnrichmentMinQuality() && f.getRelevance() >= policy.getFeedbackEnrichmentMinRelevance();
      f.enrichment(!good ? AnswerFeedback.Enrichment.NONE : policy.isFeedbackEnrichmentRequiresApproval() && f.getEnrichmentStatus() != AnswerFeedback.Enrichment.APPROVED ? AnswerFeedback.Enrichment.CANDIDATE : AnswerFeedback.Enrichment.APPROVED);
    }
    return Optional.of(feedback.save(f));
  }

  public List<AnswerFeedbackSource> sourcesOf(UUID feedbackId) { return sources.findByFeedbackId(feedbackId); }

  private static void validate(Integer value) {
    if (value != null && (value < 1 || value > 5)) throw new IllegalArgumentException("ratings must be between 1 and 5");
  }

  private static String actor() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    return a == null ? "system" : a.getName();
  }
}
