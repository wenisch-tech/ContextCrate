package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.*;

class IngestionRunResponseTest {
  @Test
  void redactsTokenFromImmutableRunSnapshotResponse() {
    UUID crateId = UUID.randomUUID();
    IngestionRun run = new IngestionRun(UUID.randomUUID(), crateId, UUID.randomUUID(),
        UUID.randomUUID(), "{}", "{}");
    SourceConfiguration source = SourceConfiguration.git("https://example.com/repository.git");

    IngestionRunResponse response = IngestionRunResponse.from(run, source,
        IngestionConfiguration.git(new IngestionConfiguration.Git("", "git", "secret-token",
            java.util.List.of("**"), java.util.List.of(), 10_000, 1_048_576,
            CrawlConfiguration.Output.defaults())));

    assertThat(response.tokenConfigured()).isTrue();
    assertThat(response.jobConfiguration().git().token()).isNull();
  }
}
