package tech.wenisch.contextcrate.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.storage.ArtifactStore;

@Service
public class CratePurgeWorker {
  private final JdbcTemplate jdbc;
  private final ArtifactStore artifacts;
  private final SearchIndex index;
  private final CrateRepository crates;
  public CratePurgeWorker(JdbcTemplate jdbc,ArtifactStore artifacts,SearchIndex index,CrateRepository crates){
    this.jdbc=jdbc;this.artifacts=artifacts;this.index=index;this.crates=crates;
  }

  @Async
  public void purge(UUID crateId) {
    try {
      artifacts.deletePrefix("crates/"+crateId);
      index.deleteNamespace(crateId);
      deleteRows(crateId);
    } catch(Exception e) {
      crates.findById(crateId).ifPresent(c->{c.purgeFailed();crates.save(c);});
    }
  }

  @Transactional
  protected void deleteRows(UUID crateId) {
    for(String table:new String[]{"extraction_result","document_chunk","normalized_document",
        "acquisition_record","source_item","pipeline_work_item","ingestion_run","ingestion_job","source",
        "extraction_rule","audit_log","api_key","admin_elevation","crate_index_generation",
        "crate_rag_settings","crate_provider_settings","crate_member"})
      jdbc.update("delete from "+table+" where crate_id = ?",crateId);
    jdbc.update("delete from crate where id = ?",crateId);
  }
}
