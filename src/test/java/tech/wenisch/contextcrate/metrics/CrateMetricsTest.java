package tech.wenisch.contextcrate.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.repository.CrateRepository;

class CrateMetricsTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final CrateRepository crates = mock(CrateRepository.class);

  @Test
  void tagsMetricsWithTheCrateNameAndCachesTheLookup() {
    UUID id = UUID.randomUUID();
    Crate crate = mock(Crate.class);
    when(crate.getName()).thenReturn("Handbook");
    when(crates.findById(id)).thenReturn(Optional.of(crate));
    var metrics = new CrateMetrics(registry, crates, 10);

    metrics.count("mcp.tool.calls", id, "tool", "search_crate", "outcome", "ok");
    metrics.count("mcp.tool.calls", id, "tool", "search_crate", "outcome", "ok");

    assertThat(registry.counter("contextcrate.mcp.tool.calls", "crate", "Handbook", "tool", "search_crate", "outcome", "ok").count())
        .isEqualTo(2);
    verify(crates, times(1)).findById(id);
  }

  @Test
  void fallsBackToTheIdWhenTheCrateIsUnknown() {
    UUID id = UUID.randomUUID();
    when(crates.findById(id)).thenReturn(Optional.empty());
    assertThat(new CrateMetrics(registry, crates, 10).crate(id)).isEqualTo(id.toString());
    assertThat(new CrateMetrics(registry, crates, 10).crate(null)).isEqualTo("none");
  }

  @Test
  void boundsTheNumberOfDistinctKeyNames() {
    var metrics = new CrateMetrics(registry, crates, 2);
    assertThat(metrics.keyTag("api-key:one")).isEqualTo("one");
    assertThat(metrics.keyTag("two")).isEqualTo("two");
    assertThat(metrics.keyTag("three")).isEqualTo("other");
    assertThat(metrics.keyTag("one")).isEqualTo("one");
    assertThat(metrics.keyTag(" ")).isEqualTo("other");
  }

  @Test
  void recordsTimersAndSummaries() {
    var metrics = new CrateMetrics(registry, null, 10);
    UUID id = UUID.randomUUID();
    metrics.time("llm.duration", id, 2_000_000, "purpose", "answer");
    metrics.summary("answer.sources", id, 4);
    assertThat(registry.get("contextcrate.llm.duration").timer().count()).isEqualTo(1);
    assertThat(registry.get("contextcrate.answer.sources").summary().totalAmount()).isEqualTo(4);
  }
}
