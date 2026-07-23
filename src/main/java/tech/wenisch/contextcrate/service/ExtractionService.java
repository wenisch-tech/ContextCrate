package tech.wenisch.contextcrate.service;

import static tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.*;

@Service
public class ExtractionService {
  private static final int CONTEXT_CHARS = 80;
  private static final int MAX_MATCH_LENGTH = 4096;

  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final ExtractionRuleRepository rules;
  private final ExtractionResultRepository results;
  private final PipelineQueue queue;
  private final List<ExtractionStrategy> strategies;

  public ExtractionService(
      NormalizedDocumentRepository documents,
      DocumentChunkRepository chunks,
      ExtractionRuleRepository rules,
      ExtractionResultRepository results,
      PipelineQueue queue,
      List<ExtractionStrategy> strategies) {
    this.documents = documents;
    this.chunks = chunks;
    this.rules = rules;
    this.results = results;
    this.queue = queue;
    this.strategies = strategies;
  }

  @Transactional
  public void extract(PipelinePayload payload) {
    NormalizedDocument document = documents.findById(payload.entityId()).orElseThrow();
    if (payload.crateId() != null && !payload.crateId().equals(document.getCrateId()))
      throw new IllegalArgumentException("Pipeline message crosses crate boundary");
    if (!document.getRunId().equals(payload.runId())) return;
    List<ExtractionRule> enabled =
        rules.findByCrateIdAndEnabledTrueOrderByNameAsc(document.getCrateId());
    results.deleteByDocumentId(document.getId());
    if (enabled.isEmpty()) return;
    List<ExtractionResult> created = new ArrayList<>();
    for (DocumentChunk chunk : chunks.findByDocumentIdOrderByOrdinal(document.getId()))
      for (ExtractionRule rule : enabled)
        for (ExtractionMatch match : strategy(rule).extract(rule, chunk.getContent()))
          created.add(result(rule, document, chunk, match));
    results.saveAll(created);
  }

  @Transactional
  public ExtractionRule createRule(
      UUID crateId, String name, PipelineTypes.ExtractionType type, String pattern, boolean enabled) {
    ExtractionRule rule = new ExtractionRule(UUID.randomUUID(), requireName(name), type, pattern, enabled);
    rule.assignCrate(crateId);
    strategy(rule).validate(rule);
    return rules.save(rule);
  }

  @Transactional
  public ExtractionRule updateRule(
      UUID crateId,UUID id,String name,PipelineTypes.ExtractionType type,String pattern,boolean enabled) {
    ExtractionRule rule = rules.findByIdAndCrateId(id,crateId).orElseThrow();
    rule.update(requireName(name), type, pattern, enabled);
    strategy(rule).validate(rule);
    return rules.save(rule);
  }

  @Transactional
  public void deleteRule(UUID crateId,UUID id) {
    ExtractionRule rule = rules.findByIdAndCrateId(id,crateId).orElseThrow();
    rule.update(rule.getName(), rule.getType(), rule.getPattern(), false);
    rules.save(rule);
  }

  public List<ExtractionMatch> test(PipelineTypes.ExtractionType type, String pattern, String text) {
    ExtractionRule rule = new ExtractionRule(UUID.randomUUID(), "test", type, pattern, true);
    ExtractionStrategy strategy = strategy(rule);
    strategy.validate(rule);
    return strategy.extract(rule, text == null ? "" : text);
  }

  public Page<ExtractionResult> search(
      UUID crateId, UUID runId, UUID documentId, UUID chunkId, UUID ruleId,
      String value, Pageable pageable) {
    return results.search(crateId, runId, documentId, chunkId, ruleId, blankToNull(value), pageable);
  }

  @Transactional
  public void rebuildRun(UUID crateId, UUID runId) {
    var scoped = documents.findByRunId(runId).stream()
        .filter(document -> crateId.equals(document.getCrateId())).toList();
    if (scoped.isEmpty()) throw new NoSuchElementException("Run has no documents in this crate");
    results.deleteByRunId(runId);
    scoped.forEach(document -> publish(document, true));
  }

  @Transactional
  public void rebuildDocument(UUID crateId,UUID documentId) {
    NormalizedDocument document=documents.findByIdAndCrateId(documentId,crateId).orElseThrow();
    results.deleteByDocumentId(documentId);publish(document,true);
  }

  public List<ExtractionRule> rules(UUID crateId) {
    return rules.findByCrateIdOrderByCreatedAtDesc(crateId);
  }

  public long countResults(UUID ruleId) {
    return results.countByRuleId(ruleId);
  }

  public void publish(NormalizedDocument document, boolean rebuild) {
    String key = rebuild ? "extract:" + document.getId() + ":" + UUID.randomUUID() : "extract:" + document.getId();
    queue.publish(
        PipelineMessage.create(
            document.getCrateId(),
            WorkStage.EXTRACT,
            JobService.payload(document.getCrateId(), document.getRunId(), document.getId()),
            document.getRunId(),
            document.getCrateId() + ":" + key,
            30));
  }

  private ExtractionResult result(
      ExtractionRule rule, NormalizedDocument document, DocumentChunk chunk, ExtractionMatch match) {
    String value = trim(match.value(), MAX_MATCH_LENGTH);
    String content = chunk.getContent();
    String before = content.substring(Math.max(0, match.start() - CONTEXT_CHARS), match.start());
    String after = content.substring(match.end(), Math.min(content.length(), match.end() + CONTEXT_CHARS));
    var result = new ExtractionResult(
        stable(rule.getId() + ":" + chunk.getId() + ":" + match.start() + ":" + match.end() + ":" + value),
        rule.getId(),
        document.getRunId(),
        document.getId(),
        chunk.getId(),
        chunk.getOrdinal(),
        value,
        match.start(),
        match.end(),
        before,
        after);
    result.assignCrate(document.getCrateId());
    return result;
  }

  private ExtractionStrategy strategy(ExtractionRule rule) {
    if (rule.getType() == null) throw new IllegalArgumentException("type is required");
    return strategies.stream()
        .filter(s -> s.supports(rule))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported extraction type " + rule.getType()));
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    return name.trim();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String trim(String value, int maximum) {
    return value.length() <= maximum ? value : value.substring(0, maximum);
  }

  private static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
