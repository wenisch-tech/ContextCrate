package tech.wenisch.contextcrate.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.backend.BackendChoice;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

/**
 * Copies the configuration of a crate into a new crate: sources, ingestion jobs, extraction rules and the RAG,
 * provider and evaluation settings, including the secrets they hold (Git tokens, crawler passwords, API keys).
 * The copy happens on the server, so no secret reaches the browser. Documents, runs and the search index are not
 * copied: the new crate starts empty and is filled by its own ingestion.
 */
@Service
public class CrateDuplicationService {
  private static final Logger log = LoggerFactory.getLogger(CrateDuplicationService.class);
  /** One row per crate, keyed by crate_id. */
  static final String[] SETTINGS_TABLES = {"crate_rag_settings", "crate_provider_settings", "crate_evaluation_settings"};

  private final CrateService crateService;
  private final CrateRepository crates;
  private final SourceRepository sources;
  private final IngestionJobRepository jobs;
  private final ExtractionRuleRepository rules;
  private final AuditLogRepository audits;
  private final CrateAccessService access;
  private final JdbcTemplate jdbc;

  public CrateDuplicationService(CrateService crateService, CrateRepository crates, SourceRepository sources, IngestionJobRepository jobs,
      ExtractionRuleRepository rules, AuditLogRepository audits, CrateAccessService access, JdbcTemplate jdbc) {
    this.crateService = crateService; this.crates = crates; this.sources = sources; this.jobs = jobs;
    this.rules = rules; this.audits = audits; this.access = access; this.jdbc = jdbc;
  }

  /** Global administrators only. The new crate is owned by the acting administrator and uses the backends chosen in {@code choice}. */
  @Transactional
  public Crate duplicate(UUID sourceCrateId, String name, String description, BackendChoice choice) {
    if (!access.isAdmin()) throw new AccessDeniedException("Only administrators can duplicate a crate");
    Crate original = crates.findById(sourceCrateId).orElseThrow();
    if (original.getStatus() == Crate.Status.PURGING) throw new IllegalStateException("This crate is being deleted");
    String actor = access.currentUser().getEmail();

    Crate copy = crateService.create(name, description, choice);
    UUID target = copy.getId();

    for (String table : SETTINGS_TABLES) copyRow(table, sourceCrateId, target);

    Map<UUID, UUID> sourceIds = new HashMap<>();
    for (Source source : sources.findByCrateIdOrderByCreatedAtDesc(sourceCrateId)) {
      UUID id = UUID.randomUUID();
      sourceIds.put(source.getId(), id);
      Source value = new Source(id, target, source.getName(), source.getDescription(), source.getConnectorType(), source.getConfigurationJson());
      value.update(source.getName(), source.getDescription(), source.getConfigurationJson(), source.isEnabled());
      sources.save(value);
    }
    sources.flush();
    for (IngestionJob job : jobs.findByCrateIdOrderByCreatedAtDesc(sourceCrateId)) {
      IngestionJob value = new IngestionJob(UUID.randomUUID(), target, sourceIds.get(job.getSourceId()), job.getName(), job.getConfigurationJson());
      value.update(job.getName(), job.getConfigurationJson(), job.isEnabled(), job.getMode(), job.getCronExpression());
      jobs.save(value);
    }
    for (ExtractionRule rule : rules.findByCrateIdOrderByCreatedAtDesc(sourceCrateId)) {
      ExtractionRule value = new ExtractionRule(UUID.randomUUID(), rule.getName(), rule.getType(), rule.getPattern(), rule.isEnabled());
      value.assignCrate(target);
      rules.save(value);
    }

    audits.save(new AuditLog(sourceCrateId, actor, "CRATE_DUPLICATED", target.toString(), copy.getName()));
    audits.save(new AuditLog(target, actor, "CRATE_CREATED_AS_COPY", sourceCrateId.toString(), original.getName()));
    log.info("Crate {} ({}) was duplicated into {} ({}) by {}", original.getName(), sourceCrateId, copy.getName(), target, actor);
    return copy;
  }

  /** Replaces the target's row of a per-crate settings table with a copy of the source's row; the columns are read from the row itself. */
  private void copyRow(String table, UUID from, UUID to) {
    List<Map<String, Object>> rows = jdbc.queryForList("select * from " + table + " where crate_id = ?", from);
    if (rows.isEmpty()) return;
    Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
    row.keySet().stream().filter(k -> k.equalsIgnoreCase("crate_id")).findFirst().ifPresent(k -> row.put(k, to));
    jdbc.update("delete from " + table + " where crate_id = ?", to);
    List<String> columns = new ArrayList<>(row.keySet());
    String sql = "insert into " + table + " (" + String.join(", ", columns) + ") values (" + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
    jdbc.update(sql, columns.stream().map(row::get).toArray());
  }
}
