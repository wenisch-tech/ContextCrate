package tech.wenisch.harvex.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.harvex.domain.CrawlConfiguration;
import tech.wenisch.harvex.domain.CrawlJob;
import tech.wenisch.harvex.service.ConfigurationCodec;
import tech.wenisch.harvex.service.JobService;

class JobApiControllerTest {
  @Test
  void getRedactsCrawlerSecrets() {
    ConfigurationCodec codec = new ConfigurationCodec(new ObjectMapper());
    JobService service = mock(JobService.class);
    UUID id = UUID.randomUUID();
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
    when(service.requireJob(id)).thenReturn(job);

    var response = new JobApiController(service, codec).get(id);

    assertThat(response.configuration().loginConfiguration().clientId()).isEqualTo("crawler");
    assertThat(response.configuration().loginConfiguration().clientSecret()).isNull();
  }
}
