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
import tech.wenisch.contextcrate.domain.SystemSetting;
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

  public AdminUiController(UserAdministrationService administration, CrateOverviewService overview,
      CrateAccessService access, AdminElevationService elevations,
      AdminElevationRepository elevationRecords, PipelineQueue queue,
      ContextCrateProperties properties) {
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

  @PostMapping("/settings/onboarding-policy")
  String onboardingPolicy(@RequestParam SystemSetting.OnboardingPolicy policy,
      @RequestParam(required = false) UUID crateId) {
    administration.onboardingPolicy(policy, crateId);
    return "redirect:/admin?onboardingPolicySaved#policies";
  }

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
