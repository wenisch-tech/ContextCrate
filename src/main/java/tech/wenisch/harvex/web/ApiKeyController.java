package tech.wenisch.harvex.web;

import java.security.SecureRandom;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.harvex.domain.ApiKey;
import tech.wenisch.harvex.repository.ApiKeyRepository;
import tech.wenisch.harvex.storage.Hashing;

@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {
  private final ApiKeyRepository keys;
  private final SecureRandom random = new SecureRandom();

  public ApiKeyController(ApiKeyRepository keys) {
    this.keys = keys;
  }

  @GetMapping
  public List<ApiKeyView> list() {
    return keys.findAll().stream()
        .map(
            k ->
                new ApiKeyView(
                    k.getId(), k.getName(), k.getKeyPrefix(), k.getCreatedAt(), k.isRevoked()))
        .toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedKey create(@RequestBody CreateKey request) {
    if (request.name() == null || request.name().isBlank())
      throw new IllegalArgumentException("name is required");
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String token = "hvx_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    ApiKey key =
        keys.save(
            new ApiKey(
                UUID.randomUUID(), request.name(), token.substring(0, 12), Hashing.sha256(token)));
    return new CreatedKey(key.getId(), key.getName(), token);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID id) {
    var key = keys.findById(id).orElseThrow();
    key.revoke();
    keys.save(key);
  }

  public record CreateKey(String name) {}

  public record CreatedKey(UUID id, String name, String token) {}

  public record ApiKeyView(
      UUID id, String name, String prefix, java.time.Instant createdAt, boolean revoked) {}
}
