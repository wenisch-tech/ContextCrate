package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "crate_member")
@IdClass(CrateMember.Key.class)
public class CrateMember {
  public enum Role {
    VIEWER(10), EDITOR(20), OWNER(30);
    private final int authority;
    Role(int authority) { this.authority = authority; }
    public boolean includes(Role required) { return authority >= required.authority; }
  }

  @Id @Column(name = "crate_id") private UUID crateId;
  @Id @Column(name = "user_id") private UUID userId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Role role;
  @Column(name = "invited_by") private UUID invitedBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected CrateMember() {}
  public CrateMember(UUID crateId, UUID userId, Role role, UUID invitedBy) {
    this.crateId = crateId; this.userId = userId; this.role = role;
    this.invitedBy = invitedBy; this.createdAt = Instant.now();
  }
  public void role(Role role) { this.role = role; }
  public UUID getCrateId() { return crateId; }
  public UUID getUserId() { return userId; }
  public Role getRole() { return role; }
  public UUID getInvitedBy() { return invitedBy; }
  public Instant getCreatedAt() { return createdAt; }

  public record Key(UUID crateId, UUID userId) implements Serializable {}
}
