package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

/** Duplicates a crate against the real schema and compares what arrives in the copy. */
@SpringBootTest(
    properties = {
      "contextcrate.artifacts.path=target/it-data-duplicate/artifacts",
      "contextcrate.index.path=target/it-data-duplicate/index",
      "contextcrate.embeddings.enabled=false"
    })
@ActiveProfiles("test")
class CrateDuplicationIntegrationTest {
  @Autowired CrateService crateService;
  @Autowired CrateDuplicationService duplication;
  @Autowired CrateRepository crates;
  @Autowired SourceRepository sources;
  @Autowired IngestionJobRepository jobs;
  @Autowired ExtractionRuleRepository rules;
  @Autowired CrateMemberRepository members;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void clear() { SecurityContextHolder.clearContext(); }

  @Test
  void copiesConfigurationWithSecretsIntoANewEmptyCrate() {
    String email = jdbc.queryForObject("select email from app_user where role = 'ADMIN' fetch first 1 rows only", String.class);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

    Crate original = crateService.create("Original", "the original");
    UUID from = original.getId();
    jdbc.update("update crate_provider_settings set answering_api_key = ?, answering_base_url = ? where crate_id = ?", "sk-secret", "https://llm.example.test/v1", from);
    jdbc.update("update crate_rag_settings set system_prompt = ?, source_limit = 7 where crate_id = ?", "Answer in German.", from);
    Source source = new Source(UUID.randomUUID(), from, "Docs", "d", ConnectorType.GIT, "{\"git\":{\"url\":\"https://git.example.test/r.git\"}}");
    sources.saveAndFlush(source);
    IngestionJob job = new IngestionJob(UUID.randomUUID(), from, source.getId(), "Nightly", "{\"git\":{\"token\":\"ghp_secret\"}}");
    job.update("Nightly", job.getConfigurationJson(), true, IngestionJobMode.SCHEDULED, "0 0 3 * * *");
    jobs.saveAndFlush(job);
    ExtractionRule rule = new ExtractionRule(UUID.randomUUID(), "Ips", PipelineTypes.ExtractionType.REGEX, "\\d+", true);
    rule.assignCrate(from);
    rules.saveAndFlush(rule);

    Crate copy = duplication.duplicate(from, "Copy", "the copy", null);
    UUID to = copy.getId();

    assertThat(to).isNotEqualTo(from);
    assertThat(copy.getName()).isEqualTo("Copy");
    Map<String, Object> provider = jdbc.queryForMap("select answering_api_key, answering_base_url from crate_provider_settings where crate_id = ?", to);
    assertThat(provider.get("ANSWERING_API_KEY")).isEqualTo("sk-secret");
    assertThat(provider.get("ANSWERING_BASE_URL")).isEqualTo("https://llm.example.test/v1");
    assertThat(jdbc.queryForObject("select system_prompt from crate_rag_settings where crate_id = ?", String.class, to)).isEqualTo("Answer in German.");
    assertThat(jdbc.queryForObject("select source_limit from crate_rag_settings where crate_id = ?", Integer.class, to)).isEqualTo(7);

    List<Source> copiedSources = sources.findByCrateIdOrderByCreatedAtDesc(to);
    assertThat(copiedSources).hasSize(1);
    assertThat(copiedSources.getFirst().getId()).isNotEqualTo(source.getId());
    assertThat(copiedSources.getFirst().getConfigurationJson()).isEqualTo(source.getConfigurationJson());
    List<IngestionJob> copiedJobs = jobs.findByCrateIdOrderByCreatedAtDesc(to);
    assertThat(copiedJobs).hasSize(1);
    assertThat(copiedJobs.getFirst().getSourceId()).isEqualTo(copiedSources.getFirst().getId());
    assertThat(copiedJobs.getFirst().getConfigurationJson()).contains("ghp_secret");
    assertThat(copiedJobs.getFirst().getMode()).isEqualTo(IngestionJobMode.SCHEDULED);
    assertThat(copiedJobs.getFirst().getCronExpression()).isEqualTo("0 0 3 * * *");
    assertThat(copiedJobs.getFirst().isEnabled()).isTrue();
    assertThat(rules.findByCrateIdOrderByCreatedAtDesc(to)).extracting(ExtractionRule::getName).containsExactly("Ips");

    assertThat(jdbc.queryForObject("select count(*) from normalized_document where crate_id = ?", Long.class, to)).isZero();
    assertThat(members.findByCrateId(to)).hasSize(1);
    // The original is untouched.
    assertThat(sources.findByCrateIdOrderByCreatedAtDesc(from)).hasSize(1);
    assertThat(jdbc.queryForObject("select answering_api_key from crate_provider_settings where crate_id = ?", String.class, from)).isEqualTo("sk-secret");
  }
}
