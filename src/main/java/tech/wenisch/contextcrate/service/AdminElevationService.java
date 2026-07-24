package tech.wenisch.contextcrate.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

@Service
public class AdminElevationService {
  private final CrateAccessService access;private final AdminElevationRepository elevations;
  private final CrateRepository crates;private final AuditLogRepository audits;
  public AdminElevationService(CrateAccessService access,AdminElevationRepository elevations,
      CrateRepository crates,AuditLogRepository audits){
    this.access=access;this.elevations=elevations;this.crates=crates;this.audits=audits;
  }
  @Transactional public AdminElevation start(UUID crateId,String reason){
    if(!access.isAdmin())throw new org.springframework.security.access.AccessDeniedException("Administrator required");
    crates.findById(crateId).orElseThrow();var user=access.currentUser();
    var value=elevations.save(new AdminElevation(user.getId(),crateId,reason));
    audits.save(new AuditLog(crateId,user.getEmail(),"ADMIN_ELEVATION_STARTED",value.getId().toString(),reason));
    return value;
  }
  @Transactional public void end(UUID id){
    var value=elevations.findById(id).orElseThrow();
    if(!value.getAdminUserId().equals(access.currentUser().getId()))
      throw new org.springframework.security.access.AccessDeniedException("Elevation belongs to another administrator");
    value.end();elevations.save(value);
    audits.save(new AuditLog(value.getCrateId(),access.currentUser().getEmail(),"ADMIN_ELEVATION_ENDED",id.toString(),""));
  }
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
  @Transactional public void expire(){
    for(var value:elevations.findByEndedAtIsNullAndExpiresAtBefore(java.time.Instant.now())){
      value.end();elevations.save(value);
      audits.save(new AuditLog(value.getCrateId(),"system","ADMIN_ELEVATION_EXPIRED",
          value.getId().toString(),""));
    }
  }
}
