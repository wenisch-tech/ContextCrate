package tech.wenisch.contextcrate.service;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.domain.SystemSetting;
import tech.wenisch.contextcrate.repository.AppUserRepository;
import tech.wenisch.contextcrate.repository.SystemSettingRepository;

/**
 * Installation-wide user administration. Concentrates the lock-out guards so that the REST API and
 * the administration UI cannot diverge: an administrator may never demote or disable their own
 * account, and the last enabled administrator must survive every change. Without the latter rule
 * {@code SecurityConfig.initializeAdmin} fails on the next start-up and the installation no longer
 * boots.
 */
@Service
public class UserAdministrationService {
  public static final String ADMIN = "ADMIN";
  public static final String USER = "USER";

  private final AppUserRepository users;
  private final PasswordEncoder passwords;
  private final SystemSettingRepository settings;
  private final CrateAccessService access;

  public UserAdministrationService(AppUserRepository users, PasswordEncoder passwords,
      SystemSettingRepository settings, CrateAccessService access) {
    this.users = users; this.passwords = passwords; this.settings = settings; this.access = access;
  }

  public List<AppUser> users() {
    requireAdmin();
    return users.findAll().stream()
        .sorted(java.util.Comparator.comparing(AppUser::getEmail, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  @Transactional
  public AppUser create(String email, String temporaryPassword) {
    requireAdmin();
    String address = requireText(email, "An e-mail address is required");
    requireText(temporaryPassword, "A temporary password is required");
    if (users.findByEmailIgnoreCase(address).isPresent())
      throw new IllegalStateException("A user with this e-mail address already exists");
    return users.save(
        new AppUser(UUID.randomUUID(), address, passwords.encode(temporaryPassword), USER, true));
  }

  @Transactional
  public AppUser entitlement(UUID id, boolean allowed) {
    requireAdmin();
    AppUser user = require(id);
    user.canCreateCrates(allowed);
    return users.save(user);
  }

  @Transactional
  public AppUser enabled(UUID id, boolean enabled) {
    requireAdmin();
    AppUser user = require(id);
    if (!enabled) {
      requireNotSelf(user, "You cannot disable your own account");
      requireAnotherAdminRemains(user, "The last enabled administrator cannot be disabled");
    }
    user.enabled(enabled);
    return users.save(user);
  }

  @Transactional
  public AppUser role(UUID id, String role) {
    requireAdmin();
    String value = role == null ? "" : role.trim().toUpperCase(java.util.Locale.ROOT);
    if (!ADMIN.equals(value) && !USER.equals(value))
      throw new IllegalArgumentException("Role must be ADMIN or USER");
    AppUser user = require(id);
    if (ADMIN.equals(user.getRole()) && !ADMIN.equals(value)) {
      requireNotSelf(user, "You cannot revoke your own administrator role");
      requireAnotherAdminRemains(user, "The last enabled administrator cannot be demoted");
    }
    user.role(value);
    return users.save(user);
  }

  @Transactional
  public AppUser resetPassword(UUID id, String temporaryPassword) {
    requireAdmin();
    requireText(temporaryPassword, "A temporary password is required");
    AppUser user = require(id);
    user.resetPassword(passwords.encode(temporaryPassword));
    return users.save(user);
  }

  public SystemSetting systemSetting() {
    requireAdmin();
    return settings.findById(1).orElseThrow();
  }

  @Transactional
  public SystemSetting crateCreationMode(SystemSetting.CrateCreationMode mode) {
    requireAdmin();
    if (mode == null) throw new IllegalArgumentException("A crate creation mode is required");
    SystemSetting setting = settings.findById(1).orElseThrow();
    setting.crateCreationMode(mode);
    return settings.save(setting);
  }

  @Transactional
  public SystemSetting timeZone(String timeZone) {
    requireAdmin();
    try { java.time.ZoneId.of(timeZone); }
    catch (RuntimeException e) { throw new IllegalArgumentException("A valid IANA time zone is required"); }
    SystemSetting setting = settings.findById(1).orElseThrow();
    setting.timeZone(timeZone);
    return settings.save(setting);
  }

  public void requireAdmin() {
    if (!access.isAdmin()) throw new AccessDeniedException("Administrator required");
  }

  private AppUser require(UUID id) {
    return users.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private void requireNotSelf(AppUser user, String message) {
    if (user.getId().equals(access.currentUser().getId())) throw new IllegalStateException(message);
  }

  private void requireAnotherAdminRemains(AppUser user, String message) {
    boolean another = users.findAll().stream()
        .anyMatch(u -> !u.getId().equals(user.getId()) && ADMIN.equals(u.getRole()) && u.isEnabled());
    if (!another) throw new IllegalStateException(message);
  }

  private static String requireText(String value, String message) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    return value.trim();
  }
}
