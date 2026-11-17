package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.util.UUID;

/** A source that was shown to the answer model; {@code identityUri} survives re-indexing and new document versions. */
@Entity
@Table(name = "answer_feedback_source")
public class AnswerFeedbackSource {
  @Id private UUID id;
  @Column(name = "feedback_id", nullable = false) private UUID feedbackId;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(nullable = false) private int citation;
  @Column(name = "chunk_id") private UUID chunkId;
  @Column(name = "document_id") private UUID documentId;
  @Column(name = "identity_uri", length = 4096) private String identityUri;
  @Column(length = 1000) private String title;
  @Column(name = "source_uri", length = 4096) private String sourceUri;

  protected AnswerFeedbackSource() {}

  public AnswerFeedbackSource(UUID feedbackId, UUID crateId, int citation, UUID chunkId, UUID documentId, String identityUri, String title, String sourceUri) {
    this.id = UUID.randomUUID(); this.feedbackId = feedbackId; this.crateId = crateId; this.citation = citation;
    this.chunkId = chunkId; this.documentId = documentId; this.identityUri = identityUri;
    this.title = title == null || title.length() <= 1000 ? title : title.substring(0, 1000); this.sourceUri = sourceUri;
  }

  public UUID getFeedbackId() { return feedbackId; }
  public UUID getCrateId() { return crateId; }
  public int getCitation() { return citation; }
  public UUID getChunkId() { return chunkId; }
  public UUID getDocumentId() { return documentId; }
  public String getIdentityUri() { return identityUri; }
  public String getTitle() { return title; }
  public String getSourceUri() { return sourceUri; }
}
