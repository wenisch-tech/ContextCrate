package tech.wenisch.contextcrate.web;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminApiController {
  private final CrateAccessService access;private final AppUserRepository users;
  private final PasswordEncoder passwords;private final SystemSettingRepository settings;
  private final AdminElevationService elevations;
  public AdminApiController(CrateAccessService access,AppUserRepository users,PasswordEncoder passwords,
      SystemSettingRepository settings,AdminElevationService elevations){
    this.access=access;this.users=users;this.passwords=passwords;this.settings=settings;this.elevations=elevations;
  }
  @GetMapping("/users") public List<AppUser> users(){admin();return users.findAll();}
  @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED)
  public AppUser createUser(@RequestBody UserRequest r){admin();return users.save(new AppUser(UUID.randomUUID(),r.email(),passwords.encode(r.temporaryPassword()),"USER",true));}
  @PutMapping("/users/{id}/creation-entitlement") public AppUser entitlement(@PathVariable UUID id,@RequestBody Entitlement r){admin();var u=users.findById(id).orElseThrow();u.canCreateCrates(r.allowed());return users.save(u);}
  @PutMapping("/settings/crate-creation") public SystemSetting creation(@RequestBody CreationMode r){admin();var s=settings.findById(1).orElseThrow();s.crateCreationMode(r.mode());return settings.save(s);}
  @PostMapping("/crates/{crateId}/elevations") public AdminElevation elevate(@PathVariable UUID crateId,@RequestBody Elevation r){return elevations.start(crateId,r.reason());}
  @DeleteMapping("/elevations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void end(@PathVariable UUID id){elevations.end(id);}
  private void admin(){if(!access.isAdmin())throw new org.springframework.security.access.AccessDeniedException("Administrator required");}
  public record UserRequest(String email,String temporaryPassword){}
  public record Entitlement(boolean allowed){}
  public record CreationMode(SystemSetting.CrateCreationMode mode){}
  public record Elevation(String reason){}
}
