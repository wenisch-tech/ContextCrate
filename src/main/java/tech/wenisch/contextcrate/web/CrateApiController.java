package tech.wenisch.contextcrate.web;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.service.*;

@RestController
@RequestMapping("/api/v1/crates")
public class CrateApiController {
  private final CrateService crates;private final CrateLifecycleService lifecycle;
  public CrateApiController(CrateService crates,CrateLifecycleService lifecycle){
    this.crates=crates;this.lifecycle=lifecycle;
  }
  @GetMapping public List<Crate> list(){return crates.accessible();}
  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  public Crate create(@RequestBody CrateRequest request){return crates.create(request.name(),request.description());}
  @GetMapping("/{crateId}") public Crate get(@PathVariable UUID crateId){return crates.require(crateId,CrateMember.Role.VIEWER);}
  @PutMapping("/{crateId}") public Crate update(@PathVariable UUID crateId,@RequestBody CrateRequest request){return crates.update(crateId,request.name(),request.description());}
  @GetMapping("/{crateId}/members") public List<CrateMember> members(@PathVariable UUID crateId){return crates.members(crateId);}
  @PutMapping("/{crateId}/members") public CrateMember member(@PathVariable UUID crateId,@RequestBody MemberRequest request){return crates.addMember(crateId,request.email(),request.role());}
  @DeleteMapping("/{crateId}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable UUID crateId,@PathVariable UUID userId){crates.removeMember(crateId,userId);}
  @PostMapping("/{crateId}/archive") public Crate archive(@PathVariable UUID crateId){return lifecycle.archive(crateId);}
  @PostMapping("/{crateId}/restore") public Crate restore(@PathVariable UUID crateId){return lifecycle.restore(crateId);}
  @PostMapping("/{crateId}/purge") @ResponseStatus(HttpStatus.ACCEPTED)
  public void purge(@PathVariable UUID crateId,@RequestBody PurgeRequest request){lifecycle.purge(crateId,request.confirmation());}
  public record CrateRequest(String name,String description){}
  public record MemberRequest(String email,CrateMember.Role role){}
  public record PurgeRequest(String confirmation){}
}
