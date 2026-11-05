package tech.wenisch.contextcrate.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

@Service
public class CrateLifecycleService {
  private static final Logger log = LoggerFactory.getLogger(CrateLifecycleService.class);
  private final CrateRepository crates;
  private final SourceRepository sources;
  private final IngestionJobRepository jobs;
  private final IngestionRunRepository runs;
  private final PipelineWorkItemRepository work;
  private final CrateAccessService access;
  private final AuditLogRepository audits;
  private final CratePurgeWorker purgeWorker;

  public CrateLifecycleService(
      CrateRepository crates, SourceRepository sources, IngestionJobRepository jobs,
      IngestionRunRepository runs,
      PipelineWorkItemRepository work, CrateAccessService access, AuditLogRepository audits,
      CratePurgeWorker purgeWorker) {
    this.crates=crates;this.sources=sources;this.jobs=jobs;this.runs=runs;this.work=work;this.access=access;
    this.audits=audits;this.purgeWorker=purgeWorker;
  }

  @Transactional
  public Crate archive(UUID crateId) {
    access.require(crateId, CrateMember.Role.OWNER);
    Crate crate=crates.findById(crateId).orElseThrow();crate.archiveRequested();
    for(var source:sources.findByCrateIdOrderByCreatedAtDesc(crateId)){
      source.update(source.getName(),source.getDescription(),source.getConfigurationJson(),false);sources.save(source);
    }
    for(var job:jobs.findByCrateIdOrderByCreatedAtDesc(crateId)){
      job.update(job.getName(),job.getConfigurationJson(),false);jobs.save(job);
    }
    for(var run:runs.findByCrateId(crateId))
      if(run.getStatus()==PipelineTypes.RunStatus.RUNNING||run.getStatus()==PipelineTypes.RunStatus.PAUSED){
        run.status(PipelineTypes.RunStatus.CANCELLED);runs.save(run);
      }
    work.deleteByCrateId(crateId);crate.archived();crates.save(crate);
    audits.save(new AuditLog(crateId,access.currentUser().getEmail(),"CRATE_ARCHIVED",crateId.toString(),""));
    return crate;
  }

  @Transactional
  public Crate restore(UUID crateId) {
    access.require(crateId,CrateMember.Role.OWNER);
    Crate crate=crates.findById(crateId).orElseThrow();crate.restore();return crates.save(crate);
  }

  /**
   * Deletes a crate for good: stops its processing, then removes its documents, artifacts, search index and
   * configuration in the background. Allowed for the crate's owners and for global administrators; the crate name
   * has to be typed to confirm. A failed deletion leaves the crate as PURGE_FAILED and can be started again.
   */
  @Transactional
  public void delete(UUID crateId,String confirmation) {
    String actor=authorizeDelete(crateId);
    Crate crate=crates.findById(crateId).orElseThrow();
    if(confirmation==null||!crate.getName().equals(confirmation.strip()))
      throw new IllegalArgumentException("Type the exact crate name to confirm the deletion");
    for(var source:sources.findByCrateIdOrderByCreatedAtDesc(crateId)){
      source.update(source.getName(),source.getDescription(),source.getConfigurationJson(),false);sources.save(source);
    }
    for(var job:jobs.findByCrateIdOrderByCreatedAtDesc(crateId)){
      job.update(job.getName(),job.getConfigurationJson(),false);jobs.save(job);
    }
    for(var run:runs.findByCrateId(crateId))
      if(run.getStatus()==PipelineTypes.RunStatus.RUNNING||run.getStatus()==PipelineTypes.RunStatus.PAUSED){
        run.status(PipelineTypes.RunStatus.CANCELLED);runs.save(run);
      }
    work.deleteByCrateId(crateId);
    crate.deleting();crates.save(crate);
    audits.save(new AuditLog(crateId,actor,"CRATE_DELETE_REQUESTED",crateId.toString(),crate.getName()));
    log.warn("Crate {} ({}) is being deleted by {}",crate.getName(),crateId,actor);
    // Only after the status change is visible, so the worker never races the transaction.
    if(TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
        @Override public void afterCommit(){purgeWorker.purge(crateId);}
      });
    else purgeWorker.purge(crateId);
  }

  /** Owners may delete their crate; so may global administrators, without an elevation, because deleting exposes no content. */
  private String authorizeDelete(UUID crateId){
    try{
      access.require(crateId,CrateMember.Role.OWNER);
    }catch(AccessDeniedException e){
      if(!access.isAdmin()) throw e;
    }
    return access.currentUser().getEmail();
  }

  @Transactional
  public void purge(UUID crateId,String confirmation) {
    access.require(crateId,CrateMember.Role.OWNER);
    Crate crate=crates.findById(crateId).orElseThrow();
    if(!crate.getName().equals(confirmation))
      throw new IllegalArgumentException("Enter the crate name to confirm purge");
    crate.purging();crates.save(crate);purgeWorker.purge(crateId);
  }
}
