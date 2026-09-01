package tech.wenisch.contextcrate.web;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.service.ApiKeyService;
import tech.wenisch.contextcrate.service.CrateAccessService;

@RestController
@RequestMapping("/api/v1")
public class ApiKeyController {
  private final ApiKeyService apiKeys;
  private final CrateAccessService access;

  public ApiKeyController(ApiKeyService apiKeys, CrateAccessService access) {
    this.apiKeys = apiKeys;
    this.access = access;
  }

  @GetMapping("/me/api-keys")
  public List<ApiKeyView> personal() {
    return views(apiKeys.personalKeys(access.currentUser().getId()));
  }

  @GetMapping("/crates/{crateId}/api-keys")
  public List<ApiKeyView> crateKeys(@PathVariable UUID crateId) {
    access.require(crateId, CrateMember.Role.OWNER);
    return views(apiKeys.crateKeys(crateId));
  }

  private static List<ApiKeyView> views(List<ApiKey> values) {
    return values.stream()
        .map(
            k ->
                new ApiKeyView(
                    k.getId(), k.getName(), k.getKeyPrefix(), k.getCreatedAt(), k.isRevoked()))
        .toList();
  }

  @PostMapping("/me/api-keys")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedKey createPersonal(@RequestBody CreateKey request) {
    var created = apiKeys.createPersonal(access.currentUser().getId(), request.name());
    return new CreatedKey(created.id(), created.name(), created.token());
  }

  @PostMapping("/crates/{crateId}/api-keys")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedKey createCrate(@PathVariable UUID crateId,@RequestBody CreateCrateKey request) {
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    var created = apiKeys.createCrate(crateId, request.name(), request.role());
    return new CreatedKey(created.id(), created.name(), created.token());
  }

  @DeleteMapping("/me/api-keys/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID id) {
    apiKeys.revokePersonal(access.currentUser().getId(), id);
  }

  @DeleteMapping("/crates/{crateId}/api-keys/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeCrate(@PathVariable UUID crateId,@PathVariable UUID id){
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    apiKeys.revokeCrate(crateId, id);
  }

  public record CreateKey(String name) {}
  public record CreateCrateKey(String name,CrateMember.Role role) {}

  public record CreatedKey(UUID id, String name, String token) {}

  public record ApiKeyView(
      UUID id, String name, String prefix, java.time.Instant createdAt, boolean revoked) {}
}
