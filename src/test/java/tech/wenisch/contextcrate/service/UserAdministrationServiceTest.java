package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.repository.AppUserRepository;
import tech.wenisch.contextcrate.repository.SystemSettingRepository;

class UserAdministrationServiceTest {
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final PasswordEncoder passwords = mock(PasswordEncoder.class);
  private final SystemSettingRepository settings = mock(SystemSettingRepository.class);
  private final CrateAccessService access = mock(CrateAccessService.class);
  private final OnboardingService onboarding = mock(OnboardingService.class);
  private final UserAdministrationService service =
      new UserAdministrationService(users, passwords, settings, access, onboarding);

  private AppUser admin;
  private AppUser other;

  @BeforeEach
  void setUp() {
    admin = new AppUser(UUID.randomUUID(), "admin@example.com", "hash", "ADMIN", false);
    other = new AppUser(UUID.randomUUID(), "member@example.com", "hash", "USER", false);
    when(access.isAdmin()).thenReturn(true);
    when(access.currentUser()).thenReturn(admin);
    when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
    when(users.findById(other.getId())).thenReturn(Optional.of(other));
    when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(passwords.encode(anyString())).thenReturn("encoded");
  }

  @Test
  void nonAdministratorsAreRejected() {
    when(access.isAdmin()).thenReturn(false);

    assertThatThrownBy(service::users).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void administratorsCannotDisableThemselves() {
    when(users.findAll()).thenReturn(List.of(admin, other));

    assertThatThrownBy(() -> service.enabled(admin.getId(), false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("your own account");
    assertThat(admin.isEnabled()).isTrue();
  }

  @Test
  void administratorsCannotRevokeTheirOwnRole() {
    when(users.findAll()).thenReturn(List.of(admin, other));

    assertThatThrownBy(() -> service.role(admin.getId(), "USER"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("your own administrator role");
    assertThat(admin.getRole()).isEqualTo("ADMIN");
  }

  @Test
  void theLastEnabledAdministratorCannotBeDemoted() {
    AppUser secondAdmin = new AppUser(UUID.randomUUID(), "second@example.com", "hash", "ADMIN", false);
    secondAdmin.enabled(false);
    when(users.findById(secondAdmin.getId())).thenReturn(Optional.of(secondAdmin));
    // The acting administrator is a different account, so the self-check does not apply; the
    // remaining ADMIN is disabled, so demoting this one would leave no usable administrator.
    when(access.currentUser()).thenReturn(other);
    when(users.findAll()).thenReturn(List.of(admin, secondAdmin, other));

    assertThatThrownBy(() -> service.role(admin.getId(), "USER"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("last enabled administrator");
    assertThat(admin.getRole()).isEqualTo("ADMIN");
  }

  @Test
  void anotherAdministratorMayBeDemotedWhenOneRemains() {
    AppUser secondAdmin = new AppUser(UUID.randomUUID(), "second@example.com", "hash", "ADMIN", false);
    when(users.findById(secondAdmin.getId())).thenReturn(Optional.of(secondAdmin));
    when(users.findAll()).thenReturn(List.of(admin, secondAdmin));

    assertThat(service.role(secondAdmin.getId(), "user").getRole()).isEqualTo("USER");
  }

  @Test
  void unknownRolesAreRejected() {
    assertThatThrownBy(() -> service.role(other.getId(), "SUPERUSER"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ADMIN or USER");
  }

  @Test
  void resettingAPasswordForcesAChangeAtTheNextSignIn() {
    AppUser result = service.resetPassword(other.getId(), "temporary-secret");

    assertThat(result.getPasswordHash()).isEqualTo("encoded");
    assertThat(result.isPasswordChangeRequired()).isTrue();
  }

  @Test
  void duplicateEmailAddressesAreRejected() {
    when(users.findByEmailIgnoreCase("member@example.com")).thenReturn(Optional.of(other));

    assertThatThrownBy(() -> service.create("member@example.com", "temporary-secret"))
        .isInstanceOf(IllegalStateException.class);
    verify(users, never()).save(any());
  }

  @Test
  void createdUsersStartAsUsersWithAPendingPasswordChange() {
    when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

    AppUser created = service.create("New@Example.com", "temporary-secret");

    assertThat(created.getEmail()).isEqualTo("new@example.com");
    assertThat(created.getRole()).isEqualTo("USER");
    assertThat(created.isPasswordChangeRequired()).isTrue();
    assertThat(created.isCanCreateCrates()).isFalse();
    verify(onboarding).applyToNewUser(created);
  }
}
