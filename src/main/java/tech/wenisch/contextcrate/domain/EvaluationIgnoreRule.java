package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A crate-level rule that keeps matching questions out of the evaluation list, the averages and the report;
 * they are only counted per rule. The pattern of a question rule is the text the owner chose to ignore.
 */
@Entity
@Table(name = "evaluation_ignore_rule")
public class EvaluationIgnoreRule {
  public enum Kind { QUESTION, TOPIC, TAG }

  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Kind kind;
  @Column(nullable = false, length = 2000) private String pattern;
  @Column(name = "contains_match", nullable = false) private boolean containsMatch;
  @Column(name = "created_by", length = 320) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected EvaluationIgnoreRule() {}

  public EvaluationIgnoreRule(UUID id, UUID crateId, Kind kind, String pattern, boolean containsMatch, String createdBy) {
    this.id = id; this.crateId = crateId; this.kind = kind; this.pattern = pattern; this.containsMatch = containsMatch;
    this.createdBy = createdBy; this.createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public Kind getKind() { return kind; }
  public String getPattern() { return pattern; }
  public boolean isContainsMatch() { return containsMatch; }
  public String getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
}
