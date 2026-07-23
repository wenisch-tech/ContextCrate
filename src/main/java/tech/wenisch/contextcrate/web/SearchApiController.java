package tech.wenisch.contextcrate.web;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.service.CrateAccessService;

@RestController
@RequestMapping("/api/v1/crates/{crateId}/search")
public class SearchApiController {
  private final SearchIndex index;
  private final CrateAccessService access;

  public SearchApiController(SearchIndex index, CrateAccessService access) {
    this.index = index;
    this.access = access;
  }

  @GetMapping
  public SearchIndex.SearchResults search(
      @PathVariable UUID crateId,
      @RequestParam("q") String query,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String mode)
      throws Exception {
    access.require(crateId, CrateMember.Role.VIEWER);
    return index.search(new SearchIndex.SearchRequest(crateId, query, limit, runId, kind, mode));
  }
}
