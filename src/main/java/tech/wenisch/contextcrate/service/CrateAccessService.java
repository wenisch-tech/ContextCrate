package tech.wenisch.contextcrate.service;

import java.time.Instant;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.security.ApiKeyPrincipal;

@Service
public class CrateAccessService {
  private final AppUserRepository users;
  private final CrateMemberRepository members;
  private final AdminElevationRepository elevations;
  private final AuditLogRepository audits;
  private final CrateRepository crates;

  public CrateAccessService(
      AppUserRepository users, CrateMemberRepository members, AdminElevationRepository elevations,
      AuditLogRepository audits, CrateRepository crates) {
    this.users = users; this.members = members; this.elevations = elevations;this.audits=audits;
    this.crates=crates;
  }

  public AppUser currentUser() {
    Authentication auth = authentication();
    if (auth.getPrincipal() instanceof ApiKeyPrincipal key && key.personal())
      return users.findById(key.userId()).orElseThrow(() -> new AccessDeniedException("Unknown API-key user"));
    return users.findByEmailIgnoreCase(auth.getName())
        .orElseThrow(() -> new AccessDeniedException("No application user for principal"));
  }

  public List<CrateMember> memberships() {
    Authentication auth = authentication();
    if (auth.getPrincipal() instanceof ApiKeyPrincipal key && !key.personal()) return List.of();
    return members.findByUserId(currentUser().getId());
  }

  public CrateMember.Role require(UUID crateId, CrateMember.Role required) {
    Authentication auth = authentication();
    if (auth.getPrincipal() instanceof ApiKeyPrincipal key && !key.personal()) {
      if (!crateId.equals(key.crateId()) || !key.crateRole().includes(required))
        throw new AccessDeniedException("API key cannot access this crate");
      return key.crateRole();
    }
    AppUser user = currentUser();
    Optional<CrateMember> membership = members.findByCrateIdAndUserId(crateId, user.getId());
    if (membership.isPresent() && membership.get().getRole().includes(required))
      return membership.get().getRole();
    if ("ADMIN".equals(user.getRole())) {
      var active=elevations.findByAdminUserIdAndCrateIdAndEndedAtIsNullAndExpiresAtAfter(
          user.getId(), crateId, Instant.now());
      if(!active.isEmpty()){
        audits.save(new AuditLog(crateId,user.getEmail(),"ADMIN_ELEVATED_ACCESS",
            active.getFirst().getId().toString(),"requiredRole="+required));
      return CrateMember.Role.OWNER;
      }
    }
    throw new AccessDeniedException("Insufficient crate access");
  }

  public boolean isAdmin() { return "ADMIN".equals(currentUser().getRole()); }

  public CrateMember.Role requireMutable(UUID crateId, CrateMember.Role required) {
    CrateMember.Role granted = require(crateId, required);
    crates.findById(crateId).orElseThrow().requireActive();
    return granted;
  }

  public Optional<AdminElevation> activeElevation(UUID crateId) {
    AppUser user = currentUser();
    if (!"ADMIN".equals(user.getRole())) return Optional.empty();
    return elevations.findByAdminUserIdAndCrateIdAndEndedAtIsNullAndExpiresAtAfter(
        user.getId(), crateId, Instant.now()).stream().findFirst();
  }

  private static Authentication authentication() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Authentication required");
    return auth;
  }
}
