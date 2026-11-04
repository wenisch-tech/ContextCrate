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
  @Column(name = "allow_custom_endpoints", nullable = false)
  private boolean allowCustomEndpoints = true;
  @Column(name = "allow_local_models", nullable = false)
  private boolean allowLocalModels = true;
  @Column(name = "allow_vision_descriptions", nullable = false)
  private boolean allowVisionDescriptions = true;
  @Column(name = "allow_custom_storage", nullable = false)
  private boolean allowCustomStorage;
  @Column(name = "allow_custom_search_backend", nullable = false)
  private boolean allowCustomSearchBackend;
  public boolean isAllowCustomStorage() { return allowCustomStorage; }
  public boolean isAllowCustomSearchBackend() { return allowCustomSearchBackend; }
  public void backendPolicy(boolean allowCustomStorage, boolean allowCustomSearchBackend) {
    this.allowCustomStorage = allowCustomStorage; this.allowCustomSearchBackend = allowCustomSearchBackend;
  }
  public boolean isAllowVisionDescriptions() { return allowVisionDescriptions; }
  public void allowVisionDescriptions(boolean value) { this.allowVisionDescriptions = value; }
  protected SystemSetting() {}
  public CrateCreationMode getCrateCreationMode() { return crateCreationMode; }
  public void crateCreationMode(CrateCreationMode mode) { this.crateCreationMode = mode; }
  public OnboardingPolicy getOnboardingPolicy() { return onboardingPolicy; }
  public java.util.UUID getOnboardingCrateId() { return onboardingCrateId; }
  public void onboardingPolicy(OnboardingPolicy policy, java.util.UUID crateId) {
    this.onboardingPolicy = policy; this.onboardingCrateId = crateId;
  }
  public boolean isAllowCustomEndpoints() { return allowCustomEndpoints; }
  public boolean isAllowLocalModels() { return allowLocalModels; }
  public void modelPolicy(boolean allowCustomEndpoints, boolean allowLocalModels) {
    this.allowCustomEndpoints = allowCustomEndpoints; this.allowLocalModels = allowLocalModels;
  }
  public String getTimeZone() { return timeZone; }
  public void timeZone(String value) { this.timeZone = value; }
}
