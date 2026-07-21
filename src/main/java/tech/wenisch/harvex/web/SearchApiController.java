package tech.wenisch.harvex.web;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.harvex.index.SearchIndex;

@RestController
@RequestMapping("/api/v1/search")
public class SearchApiController {
  private final SearchIndex index;

  public SearchApiController(SearchIndex index) {
    this.index = index;
  }

  @GetMapping
  public SearchIndex.SearchResults search(
      @RequestParam("q") String query,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String mode)
      throws Exception {
    return index.search(new SearchIndex.SearchRequest(query, limit, runId, kind, mode));
  }
}
