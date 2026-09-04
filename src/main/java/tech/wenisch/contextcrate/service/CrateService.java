package tech.wenisch.contextcrate.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

@Service
public class CrateService {
  private final CrateRepository crates;
  private final CrateMemberRepository members;
  private final AppUserRepository users;
  private final SystemSettingRepository settings;
  private final AuditLogRepository audits;
  private final CrateAccessService access;
  private final JdbcTemplate jdbc;
  private final ContextCrateProperties properties;

  public CrateService(
      CrateRepository crates, CrateMemberRepository members, AppUserRepository users,
      SystemSettingRepository settings, AuditLogRepository audits, CrateAccessService access,
      JdbcTemplate jdbc, ContextCrateProperties properties) {
    this.crates = crates; this.members = members; this.users = users; this.settings = settings;
    this.audits = audits; this.access = access; this.jdbc = jdbc; this.properties = properties;
  }

  public List<Crate> accessible() {
    return access.memberships().stream().map(CrateMember::getCrateId).distinct()
        .map(crates::findById).flatMap(Optional::stream)
        .sorted(Comparator.comparing(Crate::getName, String.CASE_INSENSITIVE_ORDER)).toList();
  }

  public Crate require(UUID crateId, CrateMember.Role role) {
    access.require(crateId, role);
    return crates.findById(crateId).orElseThrow();
  }

  public List<CrateMember> members(UUID crateId) {
    require(crateId, CrateMember.Role.VIEWER);
    return members.findByCrateId(crateId);
  }

  public Optional<CrateMember> membership(UUID crateId) {
    return members.findByCrateIdAndUserId(crateId, access.currentUser().getId());
  }

  public boolean canCreate() {
    AppUser user = access.currentUser();
    SystemSetting.CrateCreationMode mode = settings.findById(1).orElseThrow().getCrateCreationMode();
    return user.isOnboardingCrateCreationRequired()
        || mode == SystemSetting.CrateCreationMode.EVERYONE
        || "ADMIN".equals(user.getRole())
        || mode == SystemSetting.CrateCreationMode.ENTITLED_USERS && user.isCanCreateCrates();
  }

  @Transactional
  public Crate update(UUID crateId, String name, String description) {
    access.requireMutable(crateId, CrateMember.Role.OWNER);
    Crate crate = crates.findById(crateId).orElseThrow();
    crate.update(name, description);
    return crates.save(crate);
  }

  @Transactional
  public Crate create(String name, String description) {
    AppUser user = access.currentUser();
    if (!canCreate()) throw new org.springframework.security.access.AccessDeniedException("Crate creation is restricted");
    // Flushed rather than saved: initializeConfiguration writes through JdbcTemplate, which does
    // not trigger a Hibernate flush, so the crate row must already exist for its foreign keys.
    Crate crate = crates.saveAndFlush(new Crate(UUID.randomUUID(), name, description, user.getId()));
    members.save(new CrateMember(crate.getId(), user.getId(), CrateMember.Role.OWNER, user.getId()));
    initializeConfiguration(crate.getId());
    audits.save(new AuditLog(crate.getId(), user.getEmail(), "CRATE_CREATED", crate.getId().toString(), name));
    if (user.isOnboardingCrateCreationRequired()) {
      user.completeOnboardingCrateCreation();
      users.save(user);
    }
    return crate;
  }

  @Transactional
  public CrateMember addMember(UUID crateId, String email, CrateMember.Role role) {
    access.requireMutable(crateId, CrateMember.Role.OWNER);
    AppUser actor = access.currentUser();
    AppUser user = users.findByEmailIgnoreCase(email).orElseThrow();
    CrateMember member = members.findByCrateIdAndUserId(crateId, user.getId())
        .orElse(new CrateMember(crateId, user.getId(), role, actor.getId()));
    member.role(role); members.save(member);
    audits.save(new AuditLog(crateId, actor.getEmail(), "MEMBER_UPDATED", user.getEmail(), role.name()));
    return member;
  }

  @Transactional
  public void removeMember(UUID crateId, UUID userId) {
    access.requireMutable(crateId, CrateMember.Role.OWNER);
    CrateMember member = members.findByCrateIdAndUserId(crateId, userId).orElseThrow();
    if (member.getRole() == CrateMember.Role.OWNER
        && members.countByCrateIdAndRole(crateId, CrateMember.Role.OWNER) <= 1)
      throw new IllegalStateException("A crate must retain at least one owner");
    members.delete(member);
  }

  private void initializeConfiguration(UUID crateId) {
    jdbc.update("""
        insert into crate_rag_settings(crate_id, strict_grounding, allow_client_history,
          inline_citations, structured_sources, retrieval_mode, retrieval_strategy,
          proposition_failure_policy, source_limit)
        values (?, false, true, true, true, 'hybrid', 'standard', 'fail-indexing', ?)
        """, crateId, properties.answering().sourceLimit());
    jdbc.update("""
        insert into crate_provider_settings(crate_id, embeddings_enabled, embeddings_provider,
          answering_enabled) values (?, true, 'local', false)
        """, crateId);
    jdbc.update("""
        insert into crate_index_generation(crate_id, generation, status,
          configuration_fingerprint, document_count, created_at, activated_at)
        values (?, 1, 'ACTIVE', 'initial', 0, current_timestamp, current_timestamp)
        """, crateId);
  }
}
