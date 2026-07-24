package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "system_setting")
public class SystemSetting {
  public enum CrateCreationMode { EVERYONE, ADMINS_ONLY, ENTITLED_USERS }
  @Id private Integer id;
  @Enumerated(EnumType.STRING)
  @Column(name = "crate_creation_mode", nullable = false, length = 32)
  private CrateCreationMode crateCreationMode;
  protected SystemSetting() {}
  public CrateCreationMode getCrateCreationMode() { return crateCreationMode; }
  public void crateCreationMode(CrateCreationMode mode) { this.crateCreationMode = mode; }
}
