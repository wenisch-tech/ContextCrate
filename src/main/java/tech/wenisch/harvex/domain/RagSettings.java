package tech.wenisch.harvex.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "rag_settings")
public class RagSettings {
  @Id private int id;
  @Column(name="strict_grounding",nullable=false) private boolean strictGrounding;
  @Column(name="allow_client_history",nullable=false) private boolean allowClientHistory;
  @Column(name="inline_citations",nullable=false) private boolean inlineCitations;
  @Column(name="structured_sources",nullable=false) private boolean structuredSources;
  @Column(name="retrieval_mode",nullable=false,length=20) private String retrievalMode;
  @Column(name="source_limit",nullable=false) private int sourceLimit;
  protected RagSettings() {}
  public RagSettings(boolean strictGrounding, boolean allowClientHistory, boolean inlineCitations, boolean structuredSources, String retrievalMode, int sourceLimit) { this.id=1;this.strictGrounding=strictGrounding;this.allowClientHistory=allowClientHistory;this.inlineCitations=inlineCitations;this.structuredSources=structuredSources;this.retrievalMode=retrievalMode;this.sourceLimit=sourceLimit; }
  public boolean isStrictGrounding(){return strictGrounding;} public boolean isAllowClientHistory(){return allowClientHistory;} public boolean isInlineCitations(){return inlineCitations;} public boolean isStructuredSources(){return structuredSources;} public String getRetrievalMode(){return retrievalMode;} public int getSourceLimit(){return sourceLimit;}
  public void update(boolean strictGrounding, boolean allowClientHistory, boolean inlineCitations, boolean structuredSources, String retrievalMode, int sourceLimit){this.strictGrounding=strictGrounding;this.allowClientHistory=allowClientHistory;this.inlineCitations=inlineCitations;this.structuredSources=structuredSources;this.retrievalMode=retrievalMode;this.sourceLimit=sourceLimit;}
}
