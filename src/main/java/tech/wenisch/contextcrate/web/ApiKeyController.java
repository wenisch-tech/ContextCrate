package tech.wenisch.contextcrate.web;

import java.security.SecureRandom;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.ApiKeyRepository;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.storage.Hashing;

@RestController
@RequestMapping("/api/v1")
public class ApiKeyController {
  private final ApiKeyRepository keys;
  private final SecureRandom random = new SecureRandom();
  private final CrateAccessService access;

  public ApiKeyController(ApiKeyRepository keys, CrateAccessService access) {
    this.keys = keys;
    this.access = access;
  }

  @GetMapping("/me/api-keys")
  public List<ApiKeyView> personal() {
    return views(keys.findByUserIdOrderByCreatedAtDesc(access.currentUser().getId()));
  }

  @GetMapping("/crates/{crateId}/api-keys")
  public List<ApiKeyView> crateKeys(@PathVariable UUID crateId) {
    access.require(crateId, CrateMember.Role.OWNER);
    return views(keys.findByCrateIdOrderByCreatedAtDesc(crateId));
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
    return create(request.name(),ApiKey.KeyType.PERSONAL,access.currentUser().getId(),null,null);
  }

  @PostMapping("/crates/{crateId}/api-keys")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedKey createCrate(@PathVariable UUID crateId,@RequestBody CreateCrateKey request) {
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    if(request.role()==CrateMember.Role.OWNER)
      throw new IllegalArgumentException("Crate API keys may be Viewer or Editor");
    return create(request.name(),ApiKey.KeyType.CRATE,null,crateId,request.role());
  }

  private CreatedKey create(String name,ApiKey.KeyType type,UUID userId,UUID crateId,CrateMember.Role role) {
    if (name == null || name.isBlank())
      throw new IllegalArgumentException("name is required");
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String token = "cc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    ApiKey key =
        keys.save(
            new ApiKey(
                UUID.randomUUID(), name, token.substring(0, 12), Hashing.sha256(token),
                type,userId,crateId,role));
    return new CreatedKey(key.getId(), key.getName(), token);
  }

  @DeleteMapping("/me/api-keys/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID id) {
    var key = keys.findById(id).orElseThrow();
    if(key.getKeyType()!=ApiKey.KeyType.PERSONAL||!access.currentUser().getId().equals(key.getUserId()))
      throw new org.springframework.security.access.AccessDeniedException("Key belongs to another principal");
    key.revoke();
    keys.save(key);
  }

  @DeleteMapping("/crates/{crateId}/api-keys/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeCrate(@PathVariable UUID crateId,@PathVariable UUID id){
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    var key=keys.findById(id).orElseThrow();
    if(!crateId.equals(key.getCrateId()))
      throw new org.springframework.security.access.AccessDeniedException("Key belongs to another crate");
    key.revoke();keys.save(key);
  }

  public record CreateKey(String name) {}
  public record CreateCrateKey(String name,CrateMember.Role role) {}

  public record CreatedKey(UUID id, String name, String token) {}

  public record ApiKeyView(
      UUID id, String name, String prefix, java.time.Instant createdAt, boolean revoked) {}
}
