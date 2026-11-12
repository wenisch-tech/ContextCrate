package tech.wenisch.contextcrate.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.AuditLog;
import tech.wenisch.contextcrate.domain.EvaluationCase;
import tech.wenisch.contextcrate.domain.EvaluationIgnoreRule;
import tech.wenisch.contextcrate.domain.EvaluationIgnoreRule.Kind;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.EvaluationCaseRepository;
import tech.wenisch.contextcrate.repository.EvaluationIgnoreRuleRepository;

/**
 * Ignore rules keep monitoring traffic (health checks and the like) out of the evaluation list, the
 * averages and the report. A rule matches a question, a topic or a tag; the case it catches is only
 * counted per rule. Callers must already have checked for the crate owner.
 */
@Service
public class EvaluationIgnoreService {
  public static final int MAX_RULES = 200, MAX_PATTERN = 2_000;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A rule as shown to the owner; {@code caught} is how many cases it caught so far. */
  public record RuleView(UUID id, String kind, String pattern, boolean containsMatch, String createdBy, Instant createdAt, long caught) {}

  /** Per-rule totals for a timeframe. Nothing but the rule and a number. */
  public record RuleCount(String kind, String pattern, boolean containsMatch, long count) {}

  public record Summary(long total, List<RuleCount> rules) {}

  private final EvaluationIgnoreRuleRepository rules;
  private final EvaluationCaseRepository cases;
  private final AuditLogRepository audits;

  public EvaluationIgnoreService(EvaluationIgnoreRuleRepository rules, EvaluationCaseRepository cases, AuditLogRepository audits) {
    this.rules = rules; this.cases = cases; this.audits = audits;
  }

  // ------------------------------------------------------------------ matching

  /** Lowercase, single spaces, no trailing punctuation: "What is 1+1?" and "what is 1+1" are the same text. */
  static String normalize(String text) {
    if (text == null) return "";
    return text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("[\\s.?!]+$", "");
  }

  static boolean matchesText(EvaluationIgnoreRule rule, String text) {
    if (text == null || text.isBlank()) return false;
    String value = normalize(text), pattern = normalize(rule.getPattern());
    if (pattern.isEmpty()) return false;
    return rule.isContainsMatch() ? value.contains(pattern) : value.equals(pattern);
  }

  static boolean matchesTag(EvaluationIgnoreRule rule, Collection<String> tags) {
    String pattern = normalize(rule.getPattern());
    return tags.stream().anyMatch(t -> normalize(t).equals(pattern));
  }

  /** Rule of the crate that matches the question text, if any. */
  @Transactional(readOnly = true)
  public Optional<UUID> matchQuestion(UUID crateId, String question) {
    if (question == null || question.isBlank()) return Optional.empty();
    return rules.findByCrateIdOrderByCreatedAtAsc(crateId).stream()
        .filter(r -> r.getKind() == Kind.QUESTION && matchesText(r, question)).map(EvaluationIgnoreRule::getId).findFirst();
  }

  /** Rule of the crate that matches the stored topic or one of the tags (a JSON array), if any. */
  @Transactional(readOnly = true)
  public Optional<UUID> matchTopicOrTags(UUID crateId, String topic, String tagsJson) {
    List<String> tags = readTags(tagsJson);
    if ((topic == null || topic.isBlank()) && tags.isEmpty()) return Optional.empty();
    return rules.findByCrateIdOrderByCreatedAtAsc(crateId).stream()
        .filter(r -> (r.getKind() == Kind.TOPIC && matchesText(r, topic)) || (r.getKind() == Kind.TAG && matchesTag(r, tags)))
        .map(EvaluationIgnoreRule::getId).findFirst();
  }

  private static List<String> readTags(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      List<String> out = new ArrayList<>();
      for (JsonNode n : MAPPER.readTree(json)) out.add(n.asText());
      return out;
    } catch (Exception e) { return List.of(); }
  }

  // ------------------------------------------------------------------ managing rules

  @Transactional(readOnly = true)
  public List<RuleView> views(UUID crateId) {
    Map<UUID, Long> caught = new HashMap<>();
    for (Object[] row : cases.countIgnored(crateId)) caught.put((UUID) row[0], ((Number) row[1]).longValue());
    return rules.findByCrateIdOrderByCreatedAtAsc(crateId).stream()
        .map(r -> new RuleView(r.getId(), r.getKind().name(), r.getPattern(), r.isContainsMatch(), r.getCreatedBy(), r.getCreatedAt(), caught.getOrDefault(r.getId(), 0L))).toList();
  }

  /** Adds one rule and ignores the existing cases it matches. */
  @Transactional
  public RuleView add(UUID crateId, String actor, String kind, String pattern, boolean containsMatch) {
    var added = addAll(crateId, actor, kind, List.of(pattern == null ? "" : pattern), containsMatch);
    if (!added.isEmpty()) return added.getFirst();
    // The rule already exists: return it.
    boolean contains = containsMatch && !"tag".equalsIgnoreCase(kind == null ? "" : kind.strip());
    return views(crateId).stream().filter(v -> v.kind().equalsIgnoreCase(kind.strip()) && normalize(v.pattern()).equals(normalize(pattern)) && v.containsMatch() == contains)
        .findFirst().orElseThrow();
  }

  /**
   * Adds a rule for each pattern (for example all wordings of one topic); a pattern that already has a rule is
   * skipped. The existing cases they match are ignored as well.
   */
  @Transactional
  public List<RuleView> addAll(UUID crateId, String actor, String kind, List<String> patterns, boolean containsMatch) {
    Kind parsed;
    try { parsed = Kind.valueOf(kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT)); }
    catch (IllegalArgumentException e) { throw new IllegalArgumentException("The kind must be question, topic or tag"); }
    boolean contains = containsMatch && parsed != Kind.TAG;
    List<EvaluationIgnoreRule> existing = rules.findByCrateIdOrderByCreatedAtAsc(crateId);
    List<EvaluationIgnoreRule> added = new ArrayList<>();
    for (String raw : patterns) {
      String pattern = raw == null ? "" : raw.strip();
      if (pattern.isEmpty()) throw new IllegalArgumentException("Give the text to ignore");
      if (pattern.length() > MAX_PATTERN) throw new IllegalArgumentException("The text to ignore is too long (at most 2000 characters)");
      if (normalize(pattern).isEmpty()) throw new IllegalArgumentException("Give the text to ignore");
      boolean duplicate = java.util.stream.Stream.concat(existing.stream(), added.stream()).anyMatch(r -> r.getKind() == parsed
          && normalize(r.getPattern()).equals(normalize(pattern)) && r.isContainsMatch() == contains);
      if (duplicate) continue;
      if (existing.size() + added.size() >= MAX_RULES) throw new IllegalArgumentException("A crate can have at most " + MAX_RULES + " ignore rules");
      added.add(new EvaluationIgnoreRule(UUID.randomUUID(), crateId, parsed, pattern, contains, actor));
    }
    for (EvaluationIgnoreRule rule : added) {
      rules.save(rule);
      // Details never contain the ignored text itself.
      audits.save(new AuditLog(crateId, actor, "EVALUATION_IGNORE_CHANGED", rule.getId().toString(), "added " + parsed.name().toLowerCase(Locale.ROOT) + " rule"));
    }
    backfill(crateId, added);
    return views(crateId).stream().filter(v -> added.stream().anyMatch(a -> a.getId().equals(v.id()))).toList();
  }

  /** Marks the existing cases the new rules match. Waiting cases are left to the worker, which checks the rules itself. */
  private void backfill(UUID crateId, List<EvaluationIgnoreRule> added) {
    if (added.isEmpty()) return;
    List<EvaluationCase> changed = new ArrayList<>();
    for (EvaluationCase c : cases.findByCrateIdAndIgnoredRuleIdIsNull(crateId)) {
      if (c.getStatus() == EvaluationCase.Status.PENDING) continue;
      List<String> tags = readTags(c.getTags());
      for (EvaluationIgnoreRule rule : added) {
        boolean hit = switch (rule.getKind()) {
          case QUESTION -> matchesText(rule, c.getQuestion());
          case TOPIC -> matchesText(rule, c.getTopic());
          case TAG -> matchesTag(rule, tags);
        };
        if (hit) { c.ignoredBy(rule.getId()); changed.add(c); break; }
      }
    }
    if (!changed.isEmpty()) cases.saveAll(changed);
  }

  /** Removes the rule; the cases it caught count normally again. */
  @Transactional
  public void delete(UUID crateId, String actor, UUID id) {
    rules.findById(id).filter(r -> r.getCrateId().equals(crateId)).ifPresent(rule -> {
      cases.clearIgnored(crateId, id);
      rules.delete(rule);
      audits.save(new AuditLog(crateId, actor, "EVALUATION_IGNORE_CHANGED", id.toString(), "deleted " + rule.getKind().name().toLowerCase(Locale.ROOT) + " rule"));
    });
  }

  // ------------------------------------------------------------------ report

  /** Messages caught per rule within {@code [from, to)}; every rule is listed, also with 0. */
  @Transactional(readOnly = true)
  public Summary summary(UUID crateId, Instant from, Instant to) {
    Map<UUID, Long> counts = new HashMap<>();
    for (Object[] row : cases.countIgnoredBetween(crateId, from, to)) counts.put((UUID) row[0], ((Number) row[1]).longValue());
    List<RuleCount> list = rules.findByCrateIdOrderByCreatedAtAsc(crateId).stream()
        .map(r -> new RuleCount(r.getKind().name(), r.getPattern(), r.isContainsMatch(), counts.getOrDefault(r.getId(), 0L)))
        .sorted(Comparator.comparingLong(RuleCount::count).reversed().thenComparing(RuleCount::pattern)).toList();
    return new Summary(list.stream().mapToLong(RuleCount::count).sum(), list);
  }
}
