package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class FrontendMigrationTest {
  @Test
  void templatesNoLongerLoadBootstrap() throws IOException {
    try (var files = Files.walk(Path.of("src/main/resources/templates"))) {
      for (Path file : files.filter(path -> path.toString().endsWith(".html")).toList())
        assertThat(Files.readString(file)).as(file.toString())
            .doesNotContain("/webjars/bootstrap", "data-bs-", "class=\"bi");
    }
  }

  @Test
  void largeConfigurationFormsExposeAtomicWizardControls() throws IOException {
    assertThat(Files.readString(Path.of("src/main/resources/templates/ingestion-job-form.html")))
        .contains("x-data=\"wizard(5)\"", "Ready to save", "Save ingestion job",
            "fragments :: sidebar", "fragments :: overlay", "fragments :: topbar(${job == null ? 'Create ingestion job' : 'Edit ingestion job'})")
        .doesNotContain("fragments :: nav");
    assertThat(Files.readString(Path.of("src/main/resources/templates/settings.html")))
        .contains("x-data=\"wizard(4)\"", "Retrieval &amp; answers", "Review &amp; save");
  }

  @Test
  void sidebarProvidesThemeAndProjectMetadata() throws IOException {
    assertThat(Files.readString(Path.of("src/main/resources/templates/fragments.html")))
        .contains("$store.theme.toggle()", "applicationVersion", "AGPL-3.0",
            "github.com/wenisch-tech/contextcrate", "issues/new", ">Chat</a>",
            "aria-label=\"Switch crate\"", ">Switch crate</span>", "Available crates", "Manage crates")
        .doesNotContain(">Switch crate</a>");
  }

  @Test
  void frontendRestoresCrateCardLinksAndAppliesLightThemeReliably() throws IOException {
    String css = Files.readString(Path.of("src/main/frontend/contextcrate.css"));
    String javascript = Files.readString(Path.of("src/main/frontend/contextcrate.js"));

    assertThat(css).contains(".stretched-link", "absolute inset-0", "html[data-theme=\"light\"]");
    assertThat(javascript).contains("applyTheme", "document.documentElement.dataset.theme");
  }
}
