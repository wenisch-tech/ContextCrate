package tech.wenisch.contextcrate.web;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.service.*;

@RestController
@RequestMapping("/api/v1/crates/{crateId}/sources")
public class SourceApiController {
  private final SourceService sources;
  private final SourceConfigurationCodec codec;
  private final CrateAccessService access;

  public SourceApiController(SourceService sources, SourceConfigurationCodec codec,
      CrateAccessService access) {
    this.sources = sources;
    this.codec = codec;
    this.access = access;
  }

  @GetMapping
  public List<SourceResponse> list(@PathVariable UUID crateId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    return sources.list(crateId).stream().map(this::response).toList();
  }

  @GetMapping("/{sourceId}")
  public SourceResponse get(@PathVariable UUID crateId, @PathVariable UUID sourceId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    return response(sources.require(crateId, sourceId));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SourceResponse create(@PathVariable UUID crateId,
      @Valid @RequestBody SourceRequest request) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    return response(sources.create(crateId, request.name(), request.description(), request.connectorType(),
        request.configuration()));
  }

  @PutMapping("/{sourceId}")
  public SourceResponse update(@PathVariable UUID crateId, @PathVariable UUID sourceId,
      @Valid @RequestBody SourceRequest request) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    Source existing = sources.require(crateId, sourceId);
    if (existing.getConnectorType() != request.connectorType())
      throw new IllegalArgumentException("A source connector cannot be changed");
    return response(sources.update(crateId, sourceId, request.name(), request.description(), request.configuration(),
        request.enabled() == null || request.enabled()));
  }

  private SourceResponse response(Source source) {
    SourceConfiguration stored = codec.read(source.getConfigurationJson(), source.getConnectorType());
    return new SourceResponse(source.getId(), source.getName(), source.getConnectorType(),
        source.getDescription(), source.isEnabled(), codec.read(codec.write(stored.withoutSecrets()),
            source.getConnectorType()), false,
        sources.jobCount(source.getId()));
  }

  public record SourceRequest(
      String name,
      String description,
      ConnectorType connectorType,
      @Valid SourceConfiguration configuration,
      Boolean enabled) {
    public SourceRequest {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
      if (connectorType == null) throw new IllegalArgumentException("connectorType is required");
    }
  }

  public record SourceResponse(
      UUID id,
      String name,
      ConnectorType connectorType,
      String description,
      boolean enabled,
      SourceConfiguration configuration,
      boolean tokenConfigured,
      long ingestionJobCount) {}
}
