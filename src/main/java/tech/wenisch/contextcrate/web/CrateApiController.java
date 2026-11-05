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
  private CrateBackendService backends;
  public CrateApiController(CrateService crates,CrateLifecycleService lifecycle){
    this.crates=crates;this.lifecycle=lifecycle;
  }
  @org.springframework.beans.factory.annotation.Autowired(required=false) void setBackends(CrateBackendService backends){this.backends=backends;}
  @GetMapping public List<Crate> list(){return crates.accessible();}
  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  public Crate create(@RequestBody CrateRequest request){return crates.create(request.name(),request.description(),new tech.wenisch.contextcrate.backend.BackendChoice(request.storage(),request.searchBackend()));}
  /** "Test connection" of the create dialog for a dedicated S3 storage. Needs the creation right and the matching administrator policy. */
  @PostMapping("/test-storage") public tech.wenisch.contextcrate.backend.ConnectionTest testStorage(@RequestBody tech.wenisch.contextcrate.backend.BackendChoice.StorageChoice request){return crates.testStorage(request);}
  /** "Test connection" for a dedicated search backend. */
  @PostMapping("/test-search-backend") public tech.wenisch.contextcrate.backend.ConnectionTest testSearchBackend(@RequestBody tech.wenisch.contextcrate.backend.BackendChoice.SearchChoice request){return crates.testSearchBackend(request);}
  /** Storage and search backend of a crate (owners), without secrets. */
  @GetMapping("/{crateId}/backends") public Map<String,Object> backends(@PathVariable UUID crateId){return backends.view(crateId);}
  /** Tests the dedicated backends of a crate again with the stored settings. */
  @PostMapping("/{crateId}/backends/test") public Map<String,tech.wenisch.contextcrate.backend.ConnectionTest> testBackends(@PathVariable UUID crateId){return backends.test(crateId);}
  @GetMapping("/{crateId}") public Crate get(@PathVariable UUID crateId){return crates.require(crateId,CrateMember.Role.VIEWER);}
  @PutMapping("/{crateId}") public Crate update(@PathVariable UUID crateId,@RequestBody CrateRequest request){return crates.update(crateId,request.name(),request.description());}
  @GetMapping("/{crateId}/members") public List<CrateMember> members(@PathVariable UUID crateId){return crates.members(crateId);}
  @PutMapping("/{crateId}/members") public CrateMember member(@PathVariable UUID crateId,@RequestBody MemberRequest request){return crates.addMember(crateId,request.email(),request.role());}
  @DeleteMapping("/{crateId}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable UUID crateId,@PathVariable UUID userId){crates.removeMember(crateId,userId);}
  @PostMapping("/{crateId}/archive") public Crate archive(@PathVariable UUID crateId){return lifecycle.archive(crateId);}
  @PostMapping("/{crateId}/restore") public Crate restore(@PathVariable UUID crateId){return lifecycle.restore(crateId);}
  /** Permanently deletes the crate with its documents, artifacts and search index (owners and global administrators). */
  @PostMapping("/{crateId}/delete") @ResponseStatus(HttpStatus.ACCEPTED)
  public void delete(@PathVariable UUID crateId,@RequestBody PurgeRequest request){lifecycle.delete(crateId,request.confirmation());}
  @PostMapping("/{crateId}/purge") @ResponseStatus(HttpStatus.ACCEPTED)
  public void purge(@PathVariable UUID crateId,@RequestBody PurgeRequest request){lifecycle.purge(crateId,request.confirmation());}
  public record CrateRequest(String name,String description,tech.wenisch.contextcrate.backend.BackendChoice.StorageChoice storage,tech.wenisch.contextcrate.backend.BackendChoice.SearchChoice searchBackend){
    public CrateRequest(String name,String description){this(name,description,null,null);}
  }
  public record MemberRequest(String email,CrateMember.Role role){}
  public record PurgeRequest(String confirmation){}
}
