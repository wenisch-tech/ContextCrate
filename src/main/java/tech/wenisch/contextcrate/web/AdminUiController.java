package tech.wenisch.contextcrate.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;
import tech.wenisch.contextcrate.domain.SharedModel;
import tech.wenisch.contextcrate.domain.SystemSetting;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.AdminElevationRepository;
import tech.wenisch.contextcrate.service.*;

/**
 * The installation-wide administration panel. Wraps the operations that previously existed only on
 * {@link AdminApiController} and {@link OperationsApiController}. Follows the form-post/redirect
 * convention of {@link UiController} rather than talking to the JSON API.
 */
@Controller
@RequestMapping("/admin")
public class AdminUiController {
  private final UserAdministrationService administration;
  private final CrateOverviewService overview;
  private final CrateAccessService access;
  private final AdminElevationService elevations;
  private final AdminElevationRepository elevationRecords;
  private final PipelineQueue queue;
  private final ContextCrateProperties properties;
  private final SharedModelService sharedModels;
  private final IndexRebuildService rebuild;
  private final RagSettingsService rag;

  public AdminUiController(UserAdministrationService administration, CrateOverviewService overview,
      CrateAccessService access, AdminElevationService elevations,
      AdminElevationRepository elevationRecords, PipelineQueue queue,
      ContextCrateProperties properties, SharedModelService sharedModels, IndexRebuildService rebuild,
      RagSettingsService rag) {
    this.sharedModels = sharedModels; this.rebuild = rebuild; this.rag = rag;
    this.administration = administration; this.overview = overview; this.access = access;
    this.elevations = elevations; this.elevationRecords = elevationRecords; this.queue = queue;
    this.properties = properties;
  }

  @ModelAttribute
  void admin(Model model) {
    administration.requireAdmin();
    model.addAttribute("isAdmin", true);
    model.addAttribute("currentUser", access.currentUser());
  }

  @GetMapping({"", "/"})
  String panel(Model model) {
    Map<String, Long> depths = new LinkedHashMap<>();
    for (WorkStage stage : WorkStage.values()) depths.put(stage.name(), queue.depth(stage));
    model.addAttribute("users", administration.users());
    model.addAttribute("cards", overview.all());
    model.addAttribute("systemSetting", administration.systemSetting());
    model.addAttribute("creationModes", SystemSetting.CrateCreationMode.values());
    model.addAttribute("onboardingPolicies", SystemSetting.OnboardingPolicy.values());
    model.addAttribute("timeZones", List.of("UTC", "Europe/London", "Europe/Berlin", "Europe/Paris",
        "Europe/Moscow", "Africa/Cairo", "Africa/Johannesburg", "America/New_York",
        "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Sao_Paulo",
        "Asia/Dubai", "Asia/Kolkata", "Asia/Singapore", "Asia/Tokyo", "Asia/Shanghai",
        "Australia/Sydney", "Pacific/Auckland"));
    model.addAttribute("sharedModelRows", sharedModels.all().stream()
        .map(m -> new SharedModelRow(m, sharedModels.usageCount(m))).toList());
    model.addAttribute("modelKinds", SharedModel.Kind.values());
    model.addAttribute("properties", properties);
    model.addAttribute("queueDepth", depths);
    model.addAttribute("deadLetters", queue.deadLetters());
    model.addAttribute("currentUserId", access.currentUser().getId());
    model.addAttribute("elevations",
        elevationRecords.findByAdminUserIdAndEndedAtIsNullAndExpiresAtAfterOrderByStartedAtDesc(
            access.currentUser().getId(), Instant.now()));
    return "admin";
  }

  @PostMapping("/users")
  String createUser(@RequestParam String email, @RequestParam String temporaryPassword) {
    administration.create(email, temporaryPassword);
    return "redirect:/admin?userCreated#users";
  }

  @PostMapping("/users/{id}/entitlement")
  String entitlement(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean allowed) {
    administration.entitlement(id, allowed);
    return "redirect:/admin?userSaved#users";
  }

  @PostMapping("/users/{id}/enabled")
  String enabled(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean enabled) {
    administration.enabled(id, enabled);
    return "redirect:/admin?userSaved#users";
  }

  @PostMapping("/users/{id}/role")
  String role(@PathVariable UUID id, @RequestParam String role) {
    administration.role(id, role);
    return "redirect:/admin?userSaved#users";
  }

  @PostMapping("/users/{id}/password-reset")
  String resetPassword(@PathVariable UUID id, @RequestParam String temporaryPassword) {
    administration.resetPassword(id, temporaryPassword);
    return "redirect:/admin?passwordReset#users";
  }

  @PostMapping("/settings/crate-creation")
  String crateCreation(@RequestParam SystemSetting.CrateCreationMode mode) {
    administration.crateCreationMode(mode);
    return "redirect:/admin?policySaved#policies";
  }

  @PostMapping("/settings/time-zone")
  String timeZone(@RequestParam String timeZone) {
    administration.timeZone(timeZone);
    return "redirect:/admin?timeZoneSaved#policies";
  }

  @PostMapping("/settings/model-policy")
  String modelPolicy(@RequestParam(defaultValue = "false") boolean allowCustomEndpoints,
      @RequestParam(defaultValue = "false") boolean allowLocalModels,
      @RequestParam(defaultValue = "false") boolean allowVisionDescriptions) {
    administration.modelPolicy(allowCustomEndpoints, allowLocalModels, allowVisionDescriptions);
    return "redirect:/admin?modelPolicySaved#policies";
  }

  @PostMapping("/settings/onboarding-policy")
  String onboardingPolicy(@RequestParam SystemSetting.OnboardingPolicy policy,
      @RequestParam(required = false) UUID crateId) {
    administration.onboardingPolicy(policy, crateId);
    return "redirect:/admin?onboardingPolicySaved#policies";
  }

  @PostMapping("/models")
  String createModel(@RequestParam SharedModel.Kind kind, @RequestParam String name, @RequestParam String baseUrl,
      @RequestParam String model, @RequestParam(required = false) String apiKey,
      @RequestParam(required = false) Integer dimensions, @RequestParam(required = false) Integer maxInputCharacters,
      @RequestParam(required = false) Integer timeoutSeconds,
      @RequestParam(defaultValue = "false") boolean availableToUsers,
      @RequestParam(defaultValue = "false") boolean defaultForNewCrates) {
    sharedModels.create(kind, name, baseUrl, model, apiKey, dimensions, maxInputCharacters, timeoutSeconds, availableToUsers, defaultForNewCrates);
    return "redirect:/admin?modelSaved#models";
  }

  @PostMapping("/models/{id}")
  String updateModel(@PathVariable UUID id, @RequestParam String name, @RequestParam String baseUrl,
      @RequestParam String model, @RequestParam(required = false) String apiKey,
      @RequestParam(required = false) Integer dimensions, @RequestParam(required = false) Integer maxInputCharacters,
      @RequestParam(required = false) Integer timeoutSeconds,
      @RequestParam(defaultValue = "false") boolean availableToUsers,
      @RequestParam(defaultValue = "false") boolean defaultForNewCrates) {
    var kind = sharedModels.all().stream().filter(m -> m.getId().equals(id)).map(SharedModel::getKind).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown shared model"));
    boolean material = sharedModels.update(id, name, baseUrl, model, apiKey, dimensions, maxInputCharacters, timeoutSeconds, availableToUsers, defaultForNewCrates);
    if (material) for (UUID crateId : sharedModels.crateIdsUsing(id)) {
      // Changed vectors invalidate the index; a changed answer model invalidates proposition indexes.
      boolean stale = kind == SharedModel.Kind.EMBEDDING
          || (kind == SharedModel.Kind.ANSWER && "proposition".equals(rag.current(crateId).getRetrievalStrategy()));
      if (stale) rebuild.rebuildAsync(crateId);
    }
    return "redirect:/admin?modelSaved#models";
  }

  @PostMapping("/models/{id}/delete")
  String deleteModel(@PathVariable UUID id) {
    sharedModels.delete(id);
    return "redirect:/admin?modelDeleted#models";
  }

  public record SharedModelRow(SharedModel model, int uses) {}

  @PostMapping("/crates/{crateId}/elevations")
  String elevate(@PathVariable UUID crateId, @RequestParam String reason) {
    elevations.start(crateId, reason);
    return "redirect:/admin?elevationStarted#crates";
  }

  @PostMapping("/elevations/{id}/end")
  String endElevation(@PathVariable UUID id) {
    elevations.end(id);
    return "redirect:/admin?elevationEnded#crates";
  }

  @PostMapping("/queue/dead-letters/{id}/requeue")
  String requeue(@PathVariable UUID id) {
    administration.requireAdmin();
    queue.requeue(id);
    return "redirect:/admin?requeued#system";
  }

  /**
   * Renders guard violations as a flash message on the panel itself. Declared locally so it takes
   * precedence over {@link ApiExceptionHandler}, which would otherwise answer with JSON.
   * {@code AccessDeniedException} is deliberately not handled here and still yields 403.
   */
  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  String rejected(RuntimeException e) {
    String message = e.getMessage() == null ? "The change was rejected." : e.getMessage();
    return "redirect:/admin?error=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
  }
}
