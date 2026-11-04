package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import tech.wenisch.contextcrate.domain.SystemSetting;
import tech.wenisch.contextcrate.repository.AppUserRepository;
import tech.wenisch.contextcrate.repository.SystemSettingRepository;

class BackendPolicyTest {
  final SystemSettingRepository settings = mock(SystemSettingRepository.class);
  final CrateAccessService access = mock(CrateAccessService.class);
  final UserAdministrationService administration = new UserAdministrationService(mock(AppUserRepository.class), mock(PasswordEncoder.class), settings, access, mock(OnboardingService.class));

  @Test
  void bothPoliciesAreOffByDefaultAndOnlyAnAdministratorCanChangeThem() {
    var setting = mock(SystemSetting.class, CALLS_REAL_METHODS);
    assertThat(setting.isAllowCustomStorage()).isFalse();
    assertThat(setting.isAllowCustomSearchBackend()).isFalse();

    when(access.isAdmin()).thenReturn(true);
    when(settings.findById(1)).thenReturn(Optional.of(setting));
    when(settings.save(any())).thenAnswer(i -> i.getArgument(0));
    administration.backendPolicy(true, false);
    assertThat(setting.isAllowCustomStorage()).isTrue();
    assertThat(setting.isAllowCustomSearchBackend()).isFalse();

    when(access.isAdmin()).thenReturn(false);
    assertThatThrownBy(() -> administration.backendPolicy(true, true)).isInstanceOf(AccessDeniedException.class);
  }
}
