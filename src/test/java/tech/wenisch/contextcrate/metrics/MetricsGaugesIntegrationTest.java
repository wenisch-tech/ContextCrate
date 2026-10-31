package tech.wenisch.contextcrate.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.wenisch.contextcrate.domain.CrateIds;

@SpringBootTest(
    properties = {
      "contextcrate.artifacts.path=target/it-data-metrics/artifacts",
      "contextcrate.index.path=target/it-data-metrics/index",
      "contextcrate.embeddings.enabled=false"
    })
@ActiveProfiles("test")
class MetricsGaugesIntegrationTest {
  @Autowired MeterRegistry registry;
  @Autowired HotContentTracker tracker;
  @Autowired MetricsGauges gauges;

  @Test
  void publishesSystemGaugesAndBoundedTopContentGauges() {
    assertThat(registry.find("contextcrate.pipeline.queue.depth").gauges()).isNotEmpty();
    assertThat(registry.find("contextcrate.pipeline.dead_letters").gauge()).isNotNull();

    UUID document = UUID.randomUUID();
    for (int i = 0; i < 3; i++) tracker.record(CrateIds.LEGACY, document, null);
    tracker.flush();
    gauges.refresh();

    var hot = registry.find("contextcrate.content.document.hits")
        .tag("document_id", document.toString()).gauge();
    assertThat(hot).isNotNull();
    assertThat(hot.value()).isEqualTo(3.0);
    assertThat(hot.getId().getTag("crate")).isNotBlank();
    assertThat(registry.find("contextcrate.crates").tag("status", "ACTIVE").gauge()).isNotNull();
  }
}
