package tech.wenisch.contextcrate.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.wenisch.contextcrate.index.RoutingSearchIndex;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.storage.ArtifactStore;
import tech.wenisch.contextcrate.storage.RoutingArtifactStore;

/** Removes everything a crate owns: artifacts, the search index (also a dedicated one), and the relational rows. */
@Service
public class CratePurgeWorker {
  private static final Logger log = LoggerFactory.getLogger(CratePurgeWorker.class);
  /** Children before parents. Tables with a cascading foreign key still list their rows explicitly, so the order never matters. */
  static final String[] TABLES = {"extraction_result","content_hit","document_image","document_context","chunk_question","chunk_proposition",
      "proposition_evaluation","document_chunk","normalized_document","acquisition_record","source_item","pipeline_work_item","ingestion_run",
      "ingestion_job","source","extraction_rule","evaluation_case","evaluation_golden_item","evaluation_ignore_rule","crate_evaluation_settings","audit_log","api_key",
      "admin_elevation","crate_index_generation","crate_backend_settings","crate_rag_settings","crate_provider_settings","crate_member"};

  private final JdbcTemplate jdbc;
  private final ArtifactStore artifacts;
  private final SearchIndex index;
  private final CrateRepository crates;
  private final TransactionTemplate transaction;
  private RoutingArtifactStore routingStore;
  private RoutingSearchIndex routingIndex;

  public CratePurgeWorker(JdbcTemplate jdbc,ArtifactStore artifacts,SearchIndex index,CrateRepository crates,TransactionTemplate transaction){
    this.jdbc=jdbc;this.artifacts=artifacts;this.index=index;this.crates=crates;this.transaction=transaction;
  }

  @Autowired(required=false)
  void setRouting(RoutingArtifactStore store,RoutingSearchIndex searchIndex){this.routingStore=store;this.routingIndex=searchIndex;}

  @Async
  public void purge(UUID crateId) {
    try {
      // Artifacts and index first: if a dedicated backend is unreachable the crate stays PURGE_FAILED, still
      // knows where its data lives, and the deletion can be retried.
      artifacts.deletePrefix("crates/"+crateId);
      index.deleteNamespace(crateId);
      deleteRows(crateId);
      if(routingStore!=null)routingStore.invalidate(crateId);
      if(routingIndex!=null)routingIndex.invalidate(crateId);
      log.info("Crate {} was deleted",crateId);
    } catch(Exception e) {
      log.error("Deleting crate {} failed: {}",crateId,e.toString(),e);
      crates.findById(crateId).ifPresent(c->{c.purgeFailed();crates.save(c);});
    }
  }

  void deleteRows(UUID crateId) {
    transaction.executeWithoutResult(status->{
      for(String table:TABLES) jdbc.update("delete from "+table+" where crate_id = ?",crateId);
      jdbc.update("delete from crate where id = ?",crateId);
    });
  }
}
