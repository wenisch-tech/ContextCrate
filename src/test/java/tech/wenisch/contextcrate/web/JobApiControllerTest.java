package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;
import tech.wenisch.contextcrate.domain.CrawlJob;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.ConfigurationCodec;
import tech.wenisch.contextcrate.service.JobService;

class JobApiControllerTest {
  @Test
  void getRedactsCrawlerSecrets() {
    ConfigurationCodec codec = new ConfigurationCodec(new ObjectMapper());
    JobService service = mock(JobService.class);
    UUID id = UUID.randomUUID(), crateId = UUID.randomUUID();
    CrateAccessService access = mock(CrateAccessService.class);
    var login =
        new CrawlConfiguration.LoginConfiguration(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            "https://keycloak.example.com",
            "crawler",
            "client-secret",
            "realm",
            CrawlConfiguration.AuthMethod.OAUTH2);
    CrawlJob job =
        new CrawlJob(id, "job", codec.write(new CrawlConfiguration(null, null, null, null, login)));
    job.assignCrate(crateId);
    when(service.requireJob(crateId,id)).thenReturn(job);

    var response = new JobApiController(service, codec, access).get(crateId,id);

    assertThat(response.configuration().loginConfiguration().clientId()).isEqualTo("crawler");
    assertThat(response.configuration().loginConfiguration().clientSecret()).isNull();
    verify(access).require(crateId, CrateMember.Role.VIEWER);
  }
}
