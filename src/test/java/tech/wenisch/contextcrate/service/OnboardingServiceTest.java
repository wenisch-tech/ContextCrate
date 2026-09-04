package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

class OnboardingServiceTest {
  private final SystemSettingRepository settings = mock(SystemSettingRepository.class);
  private final CrateRepository crates = mock(CrateRepository.class);
  private final CrateMemberRepository members = mock(CrateMemberRepository.class);
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final AuditLogRepository audits = mock(AuditLogRepository.class);
  private final OnboardingService service = new OnboardingService(settings, crates, members, users, audits);
  private final AppUser user = new AppUser(UUID.randomUUID(), "new@example.com", "hash", "USER", false);
  private final SystemSetting setting = mock(SystemSetting.class);

  @BeforeEach
  void setUp() {
    when(settings.findById(1)).thenReturn(Optional.of(setting));
  }

  @Test
  void selectedCrateAddsANewUserAsViewerAndAuditsIt() {
    UUID crateId = UUID.randomUUID();
    when(setting.getOnboardingPolicy()).thenReturn(SystemSetting.OnboardingPolicy.ADD_TO_EXISTING_CRATE);
    when(setting.getOnboardingCrateId()).thenReturn(crateId);
    when(crates.findById(crateId)).thenReturn(Optional.of(mock(Crate.class)));
    when(members.findByCrateIdAndUserId(crateId, user.getId())).thenReturn(Optional.empty());

    service.applyToNewUser(user);

    var member = org.mockito.ArgumentCaptor.forClass(CrateMember.class);
    verify(members).save(member.capture());
    assertThat(member.getValue().getCrateId()).isEqualTo(crateId);
    assertThat(member.getValue().getUserId()).isEqualTo(user.getId());
    assertThat(member.getValue().getRole()).isEqualTo(CrateMember.Role.VIEWER);
    verify(audits).save(argThat(audit -> audit.getActor().equals("SYSTEM")
        && audit.getAction().equals("ONBOARDING_MEMBER_ADDED")
        && audit.getSubject().equals(user.getEmail())));
  }

  @Test
  void selectedCrateDoesNotDuplicateAnExistingMembership() {
    UUID crateId = UUID.randomUUID();
    when(setting.getOnboardingPolicy()).thenReturn(SystemSetting.OnboardingPolicy.ADD_TO_EXISTING_CRATE);
    when(setting.getOnboardingCrateId()).thenReturn(crateId);
    when(crates.findById(crateId)).thenReturn(Optional.of(mock(Crate.class)));
    when(members.findByCrateIdAndUserId(crateId, user.getId())).thenReturn(Optional.of(mock(CrateMember.class)));

    service.applyToNewUser(user);

    verify(members, never()).save(any());
    verifyNoInteractions(audits);
  }

  @Test
  void dialogPolicyGrantsOnlyThePendingOnboardingAllowance() {
    when(setting.getOnboardingPolicy()).thenReturn(SystemSetting.OnboardingPolicy.SHOW_NEW_CRATE_DIALOG);

    service.applyToNewUser(user);

    assertThat(user.isOnboardingCrateCreationRequired()).isTrue();
    verify(users).save(user);
    verifyNoInteractions(members, audits);
  }

  @Test
  void doNothingLeavesTheNewAccountUnchanged() {
    when(setting.getOnboardingPolicy()).thenReturn(SystemSetting.OnboardingPolicy.DO_NOTHING);

    service.applyToNewUser(user);

    assertThat(user.isOnboardingCrateCreationRequired()).isFalse();
    verifyNoInteractions(users, members, audits);
  }

  @Test
  void selectedCratePolicyRequiresAnExistingCrate() {
    UUID crateId = UUID.randomUUID();
    when(crates.findById(crateId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updatePolicy(SystemSetting.OnboardingPolicy.ADD_TO_EXISTING_CRATE, crateId))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown onboarding crate");
  }
}
