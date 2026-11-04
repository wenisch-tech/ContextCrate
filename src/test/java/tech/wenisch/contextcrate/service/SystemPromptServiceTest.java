package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.domain.AuditLog;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.domain.SystemSetting;
import tech.wenisch.contextcrate.repository.AppUserRepository;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.SystemSettingRepository;

class SystemPromptServiceTest {
  final UUID crateId = UUID.randomUUID();
  final RagSettingsService rag = mock(RagSettingsService.class);
  final SystemSettingsService system = mock(SystemSettingsService.class);
  final SystemSetting setting = mock(SystemSetting.class);
  final CrateAccessService access = mock(CrateAccessService.class);
  final AuditLogRepository audits = mock(AuditLogRepository.class);
  final AnswerService answers = mock(AnswerService.class);
  final SystemPromptService service = new SystemPromptService(rag, system, access, audits, answers);

  SystemPromptServiceTest() {
    when(system.current()).thenReturn(setting);
    var user = mock(AppUser.class);
    when(user.getEmail()).thenReturn("owner@example.test");
    when(access.currentUser()).thenReturn(user);
    when(rag.current(crateId)).thenReturn(new RagSettings(crateId, false, true, true, true, true, true, "revise-once", "hybrid", 8));
    when(answers.previewSystemPrompt(crateId)).thenReturn("built-in");
  }

  @Test
  void anOwnerCanSetThePromptWhileItIsAllowedAndOnlyItsLengthIsAudited() {
    when(setting.isAllowCrateSystemPrompts()).thenReturn(true);
    service.update(crateId, "  Answer in German.  ");

    verify(access).requireMutable(crateId, CrateMember.Role.OWNER);
    verify(rag).updateSystemPrompt(crateId, "Answer in German.");
    var audit = ArgumentCaptor.forClass(AuditLog.class);
    verify(audits).save(audit.capture());
    assertThat(audit.getValue().toString()).doesNotContain("German");
  }

  @Test
  void itIsRefusedWhileTheAdministratorHasNotEnabledIt() {
    when(setting.isAllowCrateSystemPrompts()).thenReturn(false);
    assertThatThrownBy(() -> service.update(crateId, "text")).isInstanceOf(IllegalStateException.class).hasMessageContaining("not enabled");
    verify(rag, never()).updateSystemPrompt(any(), any());
    verifyNoInteractions(audits);
  }

  @Test
  void nonOwnersAreRefusedAndATooLongPromptIsRejectedBeforeAnythingIsSaved() {
    when(setting.isAllowCrateSystemPrompts()).thenReturn(true);
    doThrow(new AccessDeniedException("owner required")).when(access).requireMutable(crateId, CrateMember.Role.OWNER);
    assertThatThrownBy(() -> service.update(crateId, "text")).isInstanceOf(AccessDeniedException.class);
    verify(rag, never()).updateSystemPrompt(any(), any());
  }

  @Test
  void theViewShowsTheStoredPromptTheInstallationPromptAndThePreview() {
    when(setting.isAllowCrateSystemPrompts()).thenReturn(false);
    when(setting.getGlobalSystemPrompt()).thenReturn("  Be formal.  ");
    var stored = new RagSettings(crateId, false, true, true, true, true, true, "revise-once", "hybrid", 8);
    stored.updateSystemPrompt("kept while off");
    when(rag.current(crateId)).thenReturn(stored);

    var view = service.view(crateId);

    assertThat(view.allowed()).isFalse();
    assertThat(view.systemPrompt()).isEqualTo("kept while off");
    assertThat(view.installationPrompt()).isEqualTo("Be formal.");
    assertThat(view.preview()).isEqualTo("built-in");
    assertThat(view.maxLength()).isEqualTo(4000);
    verify(access).require(crateId, CrateMember.Role.OWNER);
  }

  @Test
  void onlyAdministratorsChangeThePolicyAndTheInstallationPromptHasTheSameLimit() {
    var settings = mock(SystemSettingRepository.class);
    var admin = new UserAdministrationService(mock(AppUserRepository.class), mock(PasswordEncoder.class), settings, access, mock(OnboardingService.class));
    var real = mock(SystemSetting.class, CALLS_REAL_METHODS);
    when(settings.findById(1)).thenReturn(java.util.Optional.of(real));
    when(settings.save(any())).thenAnswer(i -> i.getArgument(0));

    when(access.isAdmin()).thenReturn(false);
    assertThatThrownBy(() -> admin.systemPromptPolicy(true, "x")).isInstanceOf(AccessDeniedException.class);

    when(access.isAdmin()).thenReturn(true);
    assertThat(real.isAllowCrateSystemPrompts()).isFalse(); // off by default
    admin.systemPromptPolicy(true, "  Be formal.  ");
    assertThat(real.isAllowCrateSystemPrompts()).isTrue();
    assertThat(real.getGlobalSystemPrompt()).isEqualTo("Be formal.");
    assertThatThrownBy(() -> admin.systemPromptPolicy(true, "x".repeat(4001))).isInstanceOf(IllegalArgumentException.class);
    admin.systemPromptPolicy(false, "");
    assertThat(real.getGlobalSystemPrompt()).isNull();
  }
}
