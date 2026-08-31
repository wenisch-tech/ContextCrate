package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.ConcurrentModel;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.domain.SystemSetting;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.AdminElevationRepository;
import tech.wenisch.contextcrate.service.*;

class AdminUiControllerTest {
  private final UserAdministrationService administration = mock(UserAdministrationService.class);
  private final CrateOverviewService overview = mock(CrateOverviewService.class);
  private final CrateAccessService access = mock(CrateAccessService.class);
  private final AdminElevationService elevations = mock(AdminElevationService.class);
  private final AdminElevationRepository elevationRecords = mock(AdminElevationRepository.class);
  private final PipelineQueue queue = mock(PipelineQueue.class);
  private final ContextCrateProperties properties = mock(ContextCrateProperties.class);
  private final AdminUiController controller = new AdminUiController(
      administration, overview, access, elevations, elevationRecords, queue, properties);

  @Test
  void theModelAttributeRejectsNonAdministrators() {
    doThrow(new AccessDeniedException("Administrator required")).when(administration).requireAdmin();

    assertThatThrownBy(() -> controller.admin(new ConcurrentModel()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void thePanelExposesEverythingTheTemplateRenders() {
    AppUser admin = new AppUser(UUID.randomUUID(), "admin@example.com", "hash", "ADMIN", false);
    when(access.currentUser()).thenReturn(admin);
    when(administration.users()).thenReturn(List.of(admin));
    when(administration.systemSetting()).thenReturn(mock(SystemSetting.class));
    when(overview.all()).thenReturn(List.of());
    when(queue.deadLetters()).thenReturn(List.of());
    when(elevationRecords.findByAdminUserIdAndEndedAtIsNullAndExpiresAtAfterOrderByStartedAtDesc(
        eq(admin.getId()), any(Instant.class))).thenReturn(List.of());
    ConcurrentModel model = new ConcurrentModel();

    assertThat(controller.panel(model)).isEqualTo("admin");
    assertThat(model.asMap())
        .containsKeys("users", "cards", "systemSetting", "creationModes", "properties",
            "queueDepth", "deadLetters", "currentUserId", "elevations");
    assertThat(model.getAttribute("currentUserId")).isEqualTo(admin.getId());
  }

  @Test
  void writesDelegateAndRedirectBackToTheirTab() {
    UUID id = UUID.randomUUID();

    assertThat(controller.createUser("new@example.com", "temporary-secret"))
        .isEqualTo("redirect:/admin?userCreated#users");
    assertThat(controller.role(id, "ADMIN")).isEqualTo("redirect:/admin?userSaved#users");
    assertThat(controller.enabled(id, false)).isEqualTo("redirect:/admin?userSaved#users");
    assertThat(controller.crateCreation(SystemSetting.CrateCreationMode.ADMINS_ONLY))
        .isEqualTo("redirect:/admin?policySaved#policies");
    assertThat(controller.elevate(id, "incident 42"))
        .isEqualTo("redirect:/admin?elevationStarted#crates");
    assertThat(controller.endElevation(id)).isEqualTo("redirect:/admin?elevationEnded#crates");

    verify(administration).create("new@example.com", "temporary-secret");
    verify(administration).role(id, "ADMIN");
    verify(administration).enabled(id, false);
    verify(administration).crateCreationMode(SystemSetting.CrateCreationMode.ADMINS_ONLY);
    verify(elevations).start(id, "incident 42");
    verify(elevations).end(id);
  }

  @Test
  void requeueRequiresAnAdministratorBeforeTouchingTheQueue() {
    doThrow(new AccessDeniedException("Administrator required")).when(administration).requireAdmin();
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> controller.requeue(id)).isInstanceOf(AccessDeniedException.class);
    verify(queue, never()).requeue(id);
  }

  @Test
  void guardViolationsBecomeAFlashMessageInsteadOfAJsonError() {
    String view = controller.rejected(new IllegalStateException("You cannot disable your own account"));

    assertThat(view).startsWith("redirect:/admin?error=");
    assertThat(view).contains("cannot+disable+your+own+account");
  }
}
