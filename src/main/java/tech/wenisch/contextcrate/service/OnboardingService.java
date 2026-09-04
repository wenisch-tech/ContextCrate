package tech.wenisch.contextcrate.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

/** Applies the installation onboarding policy once, at account provisioning time. */
@Service
public class OnboardingService {
  private final SystemSettingRepository settings;
  private final CrateRepository crates;
  private final CrateMemberRepository members;
  private final AppUserRepository users;
  private final AuditLogRepository audits;

  public OnboardingService(SystemSettingRepository settings, CrateRepository crates,
      CrateMemberRepository members, AppUserRepository users, AuditLogRepository audits) {
    this.settings = settings; this.crates = crates; this.members = members; this.users = users;
    this.audits = audits;
  }

  @Transactional
  public void applyToNewUser(AppUser user) {
    SystemSetting setting = settings.findById(1).orElseThrow();
    switch (setting.getOnboardingPolicy()) {
      case ADD_TO_EXISTING_CRATE -> addToCrate(user, setting.getOnboardingCrateId());
      case SHOW_NEW_CRATE_DIALOG -> { user.requireOnboardingCrateCreation(); users.save(user); }
      case DO_NOTHING -> { }
    }
  }

  @Transactional
  public SystemSetting updatePolicy(SystemSetting.OnboardingPolicy policy, UUID crateId) {
    if (policy == null) throw new IllegalArgumentException("An onboarding policy is required");
    if (policy == SystemSetting.OnboardingPolicy.ADD_TO_EXISTING_CRATE) {
      if (crateId == null) throw new IllegalArgumentException("Select a crate for automatic onboarding");
      if (crates.findById(crateId).isEmpty()) throw new IllegalArgumentException("Unknown onboarding crate");
    } else crateId = null;
    SystemSetting setting = settings.findById(1).orElseThrow();
    setting.onboardingPolicy(policy, crateId);
    return settings.save(setting);
  }

  private void addToCrate(AppUser user, UUID crateId) {
    if (crateId == null || crates.findById(crateId).isEmpty())
      throw new IllegalStateException("The configured onboarding crate no longer exists");
    if (members.findByCrateIdAndUserId(crateId, user.getId()).isPresent()) return;
    members.save(new CrateMember(crateId, user.getId(), CrateMember.Role.VIEWER, null));
    audits.save(new AuditLog(crateId, "SYSTEM", "ONBOARDING_MEMBER_ADDED", user.getEmail(),
        CrateMember.Role.VIEWER.name()));
  }
}
