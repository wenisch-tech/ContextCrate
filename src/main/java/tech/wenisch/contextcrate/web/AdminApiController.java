package tech.wenisch.contextcrate.web;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminApiController {
  private final CrateAccessService access;private final UserAdministrationService administration;
  private final AdminElevationService elevations;private final AdminElevationRepository elevationRecords;
  private final CrateOverviewService overview;
  public AdminApiController(CrateAccessService access,UserAdministrationService administration,
      AdminElevationService elevations,AdminElevationRepository elevationRecords,
      CrateOverviewService overview){
    this.access=access;this.administration=administration;this.elevations=elevations;
    this.elevationRecords=elevationRecords;this.overview=overview;
  }
  @GetMapping("/users") public List<AppUser> users(){return administration.users();}
  @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED)
  public AppUser createUser(@RequestBody UserRequest r){return administration.create(r.email(),r.temporaryPassword());}
  @PutMapping("/users/{id}/creation-entitlement") public AppUser entitlement(@PathVariable UUID id,@RequestBody Entitlement r){return administration.entitlement(id,r.allowed());}
  @PutMapping("/users/{id}/enabled") public AppUser enabled(@PathVariable UUID id,@RequestBody Enabled r){return administration.enabled(id,r.enabled());}
  @PutMapping("/users/{id}/role") public AppUser role(@PathVariable UUID id,@RequestBody RoleRequest r){return administration.role(id,r.role());}
  @PostMapping("/users/{id}/password-reset") public AppUser resetPassword(@PathVariable UUID id,@RequestBody PasswordReset r){return administration.resetPassword(id,r.temporaryPassword());}
  @PutMapping("/settings/crate-creation") public SystemSetting creation(@RequestBody CreationMode r){return administration.crateCreationMode(r.mode());}
  @PutMapping("/settings/time-zone") public SystemSetting timeZone(@RequestBody TimeZoneRequest r){return administration.timeZone(r.timeZone());}
  @PutMapping("/settings/onboarding-policy") public SystemSetting onboardingPolicy(@RequestBody OnboardingPolicyRequest r){return administration.onboardingPolicy(r.policy(),r.crateId());}
  @GetMapping("/crates") public List<CrateOverviewService.CrateCard> crates(){administration.requireAdmin();return overview.all();}
  @GetMapping("/elevations") public List<AdminElevation> activeElevations(){
    administration.requireAdmin();
    return elevationRecords.findByAdminUserIdAndEndedAtIsNullAndExpiresAtAfterOrderByStartedAtDesc(
        access.currentUser().getId(),java.time.Instant.now());
  }
  @PostMapping("/crates/{crateId}/elevations") public AdminElevation elevate(@PathVariable UUID crateId,@RequestBody Elevation r){return elevations.start(crateId,r.reason());}
  @DeleteMapping("/elevations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void end(@PathVariable UUID id){elevations.end(id);}
  public record UserRequest(String email,String temporaryPassword){}
  public record Entitlement(boolean allowed){}
  public record Enabled(boolean enabled){}
  public record RoleRequest(String role){}
  public record PasswordReset(String temporaryPassword){}
  public record CreationMode(SystemSetting.CrateCreationMode mode){}
  public record TimeZoneRequest(String timeZone){}
  public record OnboardingPolicyRequest(SystemSetting.OnboardingPolicy policy,UUID crateId){}
  public record Elevation(String reason){}
}
