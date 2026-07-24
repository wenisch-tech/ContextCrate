package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DashboardTemplateTest {
  @Test
  void markdownRendererDoesNotUseThymeleafJavaScriptInlining() throws IOException {
    String html;
    try (var input = getClass().getResourceAsStream("/templates/dashboard.html")) {
      assertThat(input).isNotNull();
      html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(html)
        .contains("th:data-crate-id=\"${crate.id}\"")
        .contains("const crateId=document.getElementById('dashboard').dataset.crateId;")
        .contains("const renderMarkdown=()=>")
        .contains("<script th:inline=\"none\">")
        .doesNotContain("<script th:inline=\"javascript\">");
  }
}
