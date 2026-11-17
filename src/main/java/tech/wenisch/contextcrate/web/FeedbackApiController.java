package tech.wenisch.contextcrate.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.AnswerFeedback;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.feedback.FeedbackAdminService;
import tech.wenisch.contextcrate.feedback.FeedbackService;
import tech.wenisch.contextcrate.service.CrateAccessService;

/** Rating from the chat UI (viewers) and feedback management (owners). */
@RestController
@RequestMapping("/api/v1/crates/{crateId}/feedback")
public class FeedbackApiController {
  private final FeedbackService feedback;
  private final FeedbackAdminService admin;
  private final CrateAccessService access;

  public FeedbackApiController(FeedbackService feedback, FeedbackAdminService admin, CrateAccessService access) {
    this.feedback = feedback; this.admin = admin; this.access = access;
  }

  public record RateRequest(String token, Integer relevance, Integer quality, String comment) {}
  public record Entry(UUID id, String channel, String question, String answer, Short relevance, Short quality, String comment, Instant ratedAt, String enrichment) {
    static Entry of(AnswerFeedback f) {
      return new Entry(f.getId(), f.getChannel().name(), f.getQuestion(), f.getAnswer(), f.getRelevance(), f.getQuality(), f.getComment(), f.getRatedAt(), f.getEnrichmentStatus().name());
    }
  }

  @PostMapping
  public ResponseEntity<Void> rate(@PathVariable UUID crateId, @RequestBody RateRequest request) {
    access.require(crateId, CrateMember.Role.VIEWER);
    var saved = feedback.rate(request.token(), request.relevance(), request.quality(), request.comment());
    // A token of another crate is treated like an unknown one.
    if (saved.isEmpty() || !saved.get().getCrateId().equals(crateId)) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    return ResponseEntity.noContent().build();
  }

  @GetMapping
  public List<Entry> list(@PathVariable UUID crateId, @RequestParam(defaultValue = "100") int limit) {
    access.require(crateId, CrateMember.Role.OWNER);
    return admin.recent(crateId, limit).stream().map(Entry::of).toList();
  }

  @PostMapping("/{id}/approve")
  public ResponseEntity<Void> approve(@PathVariable UUID crateId, @PathVariable UUID id) {
    access.requireMutable(crateId, CrateMember.Role.OWNER);
    admin.approve(crateId, id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/reject")
  public ResponseEntity<Void> reject(@PathVariable UUID crateId, @PathVariable UUID id) {
    access.requireMutable(crateId, CrateMember.Role.OWNER);
    admin.reject(crateId, id);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID crateId, @PathVariable UUID id) {
    access.requireMutable(crateId, CrateMember.Role.OWNER);
    admin.delete(crateId, id);
    return ResponseEntity.noContent().build();
  }
}
