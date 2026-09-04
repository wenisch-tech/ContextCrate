package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "system_setting")
public class SystemSetting {
  public enum CrateCreationMode { EVERYONE, ADMINS_ONLY, ENTITLED_USERS }
  public enum OnboardingPolicy { ADD_TO_EXISTING_CRATE, SHOW_NEW_CRATE_DIALOG, DO_NOTHING }
  @Id private Integer id;
  @Enumerated(EnumType.STRING)
  @Column(name = "crate_creation_mode", nullable = false, length = 32)
  private CrateCreationMode crateCreationMode;
  @Enumerated(EnumType.STRING)
  @Column(name = "onboarding_policy", nullable = false, length = 32)
  private OnboardingPolicy onboardingPolicy;
  @Column(name = "onboarding_crate_id")
  private java.util.UUID onboardingCrateId;
  @Column(name = "time_zone", nullable = false, length = 64)
  private String timeZone;
  protected SystemSetting() {}
  public CrateCreationMode getCrateCreationMode() { return crateCreationMode; }
  public void crateCreationMode(CrateCreationMode mode) { this.crateCreationMode = mode; }
  public OnboardingPolicy getOnboardingPolicy() { return onboardingPolicy; }
  public java.util.UUID getOnboardingCrateId() { return onboardingCrateId; }
  public void onboardingPolicy(OnboardingPolicy policy, java.util.UUID crateId) {
    this.onboardingPolicy = policy; this.onboardingCrateId = crateId;
  }
  public String getTimeZone() { return timeZone; }
  public void timeZone(String value) { this.timeZone = value; }
}
