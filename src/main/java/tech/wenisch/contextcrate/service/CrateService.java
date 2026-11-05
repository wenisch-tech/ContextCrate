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
  private final tech.wenisch.contextcrate.repository.SharedModelRepository sharedModels;

  public CrateService(
      CrateRepository crates, CrateMemberRepository members, AppUserRepository users,
      SystemSettingRepository settings, AuditLogRepository audits, CrateAccessService access,
      JdbcTemplate jdbc, ContextCrateProperties properties,
      tech.wenisch.contextcrate.repository.SharedModelRepository sharedModels) {
    this.sharedModels = sharedModels;
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

  public List<MemberView> memberViews(UUID crateId) {
    access.require(crateId, CrateMember.Role.OWNER);
    return members.findByCrateId(crateId).stream()
        .map(member -> new MemberView(member,
            users.findById(member.getUserId()).orElseThrow().getEmail()))
        .sorted(Comparator.comparing(MemberView::email, String.CASE_INSENSITIVE_ORDER))
        .toList();
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

  // Dedicated storage / search backend (optional collaborators keep the service usable without them).
  private tech.wenisch.contextcrate.repository.CrateBackendSettingsRepository backendSettings;
  private tech.wenisch.contextcrate.storage.StorageConnectionTester storageTester;
  private tech.wenisch.contextcrate.index.SearchBackendTester searchTester;
  private tech.wenisch.contextcrate.storage.RoutingArtifactStore routingStore;
  private tech.wenisch.contextcrate.index.RoutingSearchIndex routingIndex;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setBackends(tech.wenisch.contextcrate.repository.CrateBackendSettingsRepository repository,
      tech.wenisch.contextcrate.storage.StorageConnectionTester storageTester, tech.wenisch.contextcrate.index.SearchBackendTester searchTester,
      tech.wenisch.contextcrate.storage.RoutingArtifactStore store, tech.wenisch.contextcrate.index.RoutingSearchIndex index) {
    this.backendSettings = repository; this.storageTester = storageTester; this.searchTester = searchTester;
    this.routingStore = store; this.routingIndex = index;
  }

  public Crate create(String name, String description) { return create(name, description, null); }

  /** Tests a storage configuration before the crate exists (the dialog's "Test connection"). */
  public tech.wenisch.contextcrate.backend.ConnectionTest testStorage(tech.wenisch.contextcrate.backend.BackendChoice.StorageChoice choice) {
    requireBackendPolicy(true, false);
    requireUnsafeAllowed(Boolean.TRUE.equals(choice.trustAllCertificates()), "storage");
    return storageTester.test(choice.toConfig());
  }

  public tech.wenisch.contextcrate.backend.ConnectionTest testSearchBackend(tech.wenisch.contextcrate.backend.BackendChoice.SearchChoice choice) {
    requireBackendPolicy(false, true);
    requireUnsafeAllowed(Boolean.TRUE.equals(choice.trustAllCertificates()), "searchBackend");
    return searchTester.test(choice.toConfig());
  }

  /** The creation right and the matching policy are required to even test a configuration, so the test cannot be used to probe the network. */
  private void requireBackendPolicy(boolean storage, boolean search) {
    if (!canCreate()) throw new org.springframework.security.access.AccessDeniedException("Crate creation is restricted");
    SystemSetting policy = settings.findById(1).orElseThrow();
    if (storage && !policy.isAllowCustomStorage())
      throw new tech.wenisch.contextcrate.backend.BackendValidationException("storage", "Dedicated storage is not enabled by the administrator", null);
    if (search && !policy.isAllowCustomSearchBackend())
      throw new tech.wenisch.contextcrate.backend.BackendValidationException("searchBackend", "A dedicated search backend is not enabled by the administrator", null);
  }

  /** Skipping TLS validation for a crate's own backend is an administrator decision. */
  private void requireUnsafeAllowed(boolean requested, String field) {
    if (requested && !settings.findById(1).orElseThrow().isAllowUnsafeBackends())
      throw new tech.wenisch.contextcrate.backend.BackendValidationException(field,
          "Ignoring TLS certificate errors is not enabled by the administrator. Use a certificate your server trusts.", null);
  }

  /** Checks the choice against the policy and the connection tests; returns the settings to store, or {@code null} for the global providers. */
  private CrateBackendSettings validatedBackends(tech.wenisch.contextcrate.backend.BackendChoice choice, UUID crateId) {
    if (choice == null || !(choice.customStorage() || choice.customSearch())) return null;
    if (backendSettings == null) throw new IllegalStateException("Dedicated backends are not available");
    requireBackendPolicy(choice.customStorage(), choice.customSearch());
    if (choice.customStorage()) requireUnsafeAllowed(Boolean.TRUE.equals(choice.storage().trustAllCertificates()), "storage");
    if (choice.customSearch()) requireUnsafeAllowed(Boolean.TRUE.equals(choice.searchBackend().trustAllCertificates()), "searchBackend");
    CrateBackendSettings row = new CrateBackendSettings(crateId);
    if (choice.customStorage()) {
      var config = choice.storage().toConfig();
      var test = storageTester.test(config);
      if (!test.ok()) throw new tech.wenisch.contextcrate.backend.BackendValidationException("storage", failure("The storage cannot be used", test), test);
      row.s3(config);
    }
    if (choice.customSearch()) {
      var config = choice.searchBackend().toConfig();
      var test = searchTester.test(config);
      if (!test.ok()) throw new tech.wenisch.contextcrate.backend.BackendValidationException("searchBackend", failure("The search backend cannot be used", test), test);
      row.index(config);
    }
    return row;
  }

  private static String failure(String prefix, tech.wenisch.contextcrate.backend.ConnectionTest test) {
    return test.steps().stream().filter(s -> s.status() == tech.wenisch.contextcrate.backend.ConnectionTest.Status.FAILED).findFirst()
        .map(s -> prefix + ": " + s.label() + " failed. " + (s.detail() == null ? "" : s.detail())).orElse(prefix);
  }

  @Transactional
  public Crate create(String name, String description, tech.wenisch.contextcrate.backend.BackendChoice choice) {
    AppUser user = access.currentUser();
    if (!canCreate()) throw new org.springframework.security.access.AccessDeniedException("Crate creation is restricted");
    UUID crateId = UUID.randomUUID();
    CrateBackendSettings backend = validatedBackends(choice, crateId);
    // Flushed rather than saved: initializeConfiguration writes through JdbcTemplate, which does
    // not trigger a Hibernate flush, so the crate row must already exist for its foreign keys.
    Crate crate = crates.saveAndFlush(new Crate(crateId, name, description, user.getId()));
    if (backend != null) {
      backendSettings.save(backend);
      if (routingStore != null) routingStore.invalidate(crateId);
      if (routingIndex != null) routingIndex.invalidate(crateId);
    }
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
    // A model the administrator marked as default is preselected, and switched on, for new crates.
    UUID embedding = defaultModel(tech.wenisch.contextcrate.domain.SharedModel.Kind.EMBEDDING);
    UUID reranking = defaultModel(tech.wenisch.contextcrate.domain.SharedModel.Kind.RERANKING);
    UUID answer = defaultModel(tech.wenisch.contextcrate.domain.SharedModel.Kind.ANSWER);
    jdbc.update("""
        insert into crate_provider_settings(crate_id, embeddings_enabled, embeddings_provider,
          answering_enabled, reranking_enabled, embedding_shared_model_id, reranking_shared_model_id,
          answering_shared_model_id)
        values (?, true, ?, ?, ?, ?, ?, ?)
        """, crateId, embedding == null ? "local" : "openai-compatible", answer != null,
        reranking != null ? Boolean.TRUE : null, embedding, reranking, answer);
    // A judge model marked as default for new crates is preselected (evaluation itself stays off).
    UUID judge = defaultModel(tech.wenisch.contextcrate.domain.SharedModel.Kind.JUDGE);
    if (judge != null) jdbc.update("insert into crate_evaluation_settings(crate_id, judge_shared_model_id) values (?, ?)", crateId, judge);
    jdbc.update("""
        insert into crate_index_generation(crate_id, generation, status,
          configuration_fingerprint, document_count, created_at, activated_at)
        values (?, 1, 'ACTIVE', 'initial', 0, current_timestamp, current_timestamp)
        """, crateId);
  }

  private UUID defaultModel(tech.wenisch.contextcrate.domain.SharedModel.Kind kind) {
    return sharedModels.findByKindAndDefaultForNewCratesTrue(kind).stream()
        .filter(tech.wenisch.contextcrate.domain.SharedModel::isAvailableToUsers)
        .map(tech.wenisch.contextcrate.domain.SharedModel::getId).findFirst().orElse(null);
  }

  public record MemberView(CrateMember member, String email) { }
}
