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
  void largeConfigurationFormsExposeConfigurationControls() throws IOException {
    assertThat(Files.readString(Path.of("src/main/resources/templates/ingestion-job-form.html")))
        .contains("x-data=\"wizard(6)\"", "authMethod = $el.elements.authMethod?.value || 'NONE'",
            "4 Authentication", "6 Review", "x-model=\"authMethod\"",
            "authMethod === 'FORM'", "authMethod === 'OAUTH2'", "Ready to save", "Save ingestion job",
            "fragments :: sidebar", "fragments :: overlay", "fragments :: topbar(${job == null ? 'Create ingestion job' : 'Edit ingestion job'})")
        .doesNotContain("fragments :: nav");
    assertThat(Files.readString(Path.of("src/main/resources/templates/settings.html")))
        .contains("General settings", "Save general settings", "x-data=\"wizard(4)\"",
            "Retrieval &amp; answers", "Review &amp; save");
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
  void everyTemplateUsesTheSingleSidebarLayout() throws IOException {
    try (var files = Files.walk(Path.of("src/main/resources/templates"))) {
      for (Path file : files.filter(path -> path.toString().endsWith(".html")).toList())
        assertThat(Files.readString(file)).as(file.toString()).doesNotContain("fragments :: nav");
    }
  }

  @Test
  void frontendRestoresCrateCardLinksAndAppliesLightThemeReliably() throws IOException {
    String css = Files.readString(Path.of("src/main/frontend/contextcrate.css"));
    String javascript = Files.readString(Path.of("src/main/frontend/contextcrate.js"));

    assertThat(css).contains(".stretched-link", "absolute inset-0", "html[data-theme=\"light\"]");
    assertThat(javascript).contains("applyTheme", "document.documentElement.dataset.theme");
  }

  @Test
  void sourcePagesExposeCurrentCorpusAndPreserveDocumentSourceFilters() throws IOException {
    assertThat(Files.readString(Path.of("src/main/resources/templates/sources.html")))
        .contains("summary.documents", "summary.chunks", "summary.latestRun", "sourceId=${source.id}");
    assertThat(Files.readString(Path.of("src/main/resources/templates/source-details.html")))
        .contains("Last run", "latestRuns.get(job.id)", "Not run yet");
    assertThat(Files.readString(Path.of("src/main/resources/templates/documents.html")))
        .contains("name=\"sourceId\"", "id=\"source-filter\"", "availableSources", "source.id == sourceId",
            "All sources", "filteredSource", "Clear source filter", "sourceId=${sourceId}");
  }

  @Test
  void crateOnboardingDialogIsServerMarkedAndOpenedByTheFrontend() throws IOException {
    assertThat(Files.readString(Path.of("src/main/resources/templates/crates.html")))
        .contains("onboardingCrateCreationRequired", "data-onboarding-required",
            "Create your first crate to continue.");
    assertThat(Files.readString(Path.of("src/main/frontend/contextcrate.js")))
        .contains("requiredOnboardingModal", "data-onboarding-required=\"true\"");
  }

  @Test
  void crateOverviewUsesSpacedThreeDimensionalCardsWithStatIcons() throws IOException {
    assertThat(Files.readString(Path.of("src/main/resources/templates/crates.html")))
        .contains("crate-grid", "data-lucide=\"files\"", "data-lucide=\"network\"",
            "data-lucide=\"users-round\"", "data-lucide=\"braces\"", "crate-activity",
            "card.ingestion.points");
    assertThat(Files.readString(Path.of("src/main/frontend/contextcrate.css")))
        .contains(".crate-grid", "perspective(1100px)", ".crate-card::after", ".crate-stat__icon");
  }

  @Test
  void loginKeepsItsAnimatedNetworkDecorativeAndMotionSafe() throws IOException {
    String login = Files.readString(Path.of("src/main/resources/templates/login.html"));
    String css = Files.readString(Path.of("src/main/frontend/contextcrate.css"));

    assertThat(login).contains("class=\"login-network\"", "aria-hidden=\"true\"",
        "id=\"login-network-canvas\"").doesNotContain("login-network__mesh");
    assertThat(css).contains("place-items-start", "pointer-events-none",
        "@media (prefers-reduced-motion: reduce)");
    assertThat(Files.readString(Path.of("src/main/frontend/contextcrate.js")))
        .contains("loginNetworkCanvas", "ResizeObserver", "prefers-reduced-motion",
            "requestAnimationFrame", "connections()");
  }
}
