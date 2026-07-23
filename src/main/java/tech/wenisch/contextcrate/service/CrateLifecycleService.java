package tech.wenisch.contextcrate.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

@Service
public class CrateLifecycleService {
  private final CrateRepository crates;
  private final CrawlJobRepository jobs;
  private final CrawlRunRepository runs;
  private final PipelineWorkItemRepository work;
  private final CrateAccessService access;
  private final AuditLogRepository audits;
  private final CratePurgeWorker purgeWorker;

  public CrateLifecycleService(
      CrateRepository crates, CrawlJobRepository jobs, CrawlRunRepository runs,
      PipelineWorkItemRepository work, CrateAccessService access, AuditLogRepository audits,
      CratePurgeWorker purgeWorker) {
    this.crates=crates;this.jobs=jobs;this.runs=runs;this.work=work;this.access=access;
    this.audits=audits;this.purgeWorker=purgeWorker;
  }

  @Transactional
  public Crate archive(UUID crateId) {
    access.require(crateId, CrateMember.Role.OWNER);
    Crate crate=crates.findById(crateId).orElseThrow();crate.archiveRequested();
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

  @Transactional
  public void purge(UUID crateId,String confirmation) {
    access.require(crateId,CrateMember.Role.OWNER);
    Crate crate=crates.findById(crateId).orElseThrow();
    if(!crate.getName().equals(confirmation))
      throw new IllegalArgumentException("Enter the crate name to confirm purge");
    crate.purging();crates.save(crate);purgeWorker.purge(crateId);
  }
}
