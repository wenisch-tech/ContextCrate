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
  @Column(name = "time_zone", nullable = false, length = 64)
  private String timeZone;
  protected SystemSetting() {}
  public CrateCreationMode getCrateCreationMode() { return crateCreationMode; }
  public void crateCreationMode(CrateCreationMode mode) { this.crateCreationMode = mode; }
  public String getTimeZone() { return timeZone; }
  public void timeZone(String value) { this.timeZone = value; }
}
