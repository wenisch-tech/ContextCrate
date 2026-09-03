package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DashboardTemplateTest {
  @Test
  void dashboardCombinesLiveOperationsWithAnimatedPipelineFlow() throws IOException {
    String html;
    try (var input = getClass().getResourceAsStream("/templates/dashboard.html")) {
      assertThat(input).isNotNull();
      html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(html)
        .contains("x-data=\"liveView($el.dataset.crateId)\"")
        .contains("Chunks", "Active runs", "Failed work", "Pipeline activity")
        .contains("Document index", "Sources monitored", "Indexing activity", "Documents indexed")
        .contains("analytics-chart", "contextcrate-logo-clean.png", "last 24h")
        .contains("WEB_FETCH", "GIT_FETCH", "BROWSER_FETCH", "PARSE", "DISCOVERY", "EXTRACT", "INDEX")
        .contains("pipeline-graph", "pipeline-packet", "x-cloak", "PROCESSING")
        .doesNotContain("pipeline?.[k]?.COMPLETED")
        .doesNotContain("answer-form", "content-search")
        .doesNotContain("innerHTML", "/webjars/bootstrap");
  }
}
