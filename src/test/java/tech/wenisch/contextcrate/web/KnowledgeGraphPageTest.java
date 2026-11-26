package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.ConcurrentModel;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.CrateMember.Role;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.graph.GraphModel.*;
import tech.wenisch.contextcrate.graph.GraphSearchService;
import tech.wenisch.contextcrate.graph.GraphStore;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.CrateService;

/** The knowledge graph page and its API: rendering, access rules and the graph view. */
class KnowledgeGraphPageTest {
  private final UUID crateId = UUID.randomUUID();
  private final CrateAccessService access = mock(CrateAccessService.class);
  private final CrateService crateService = mock(CrateService.class);
  private final GraphStore store = mock(GraphStore.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);
  private final GraphSearchService graphs = mock(GraphSearchService.class);
  private final KnowledgeGraphUiController ui = new KnowledgeGraphUiController(access, crateService, store, settings);
  private final KnowledgeGraphApiController api = new KnowledgeGraphApiController(access, store, graphs, mock(DocumentChunkRepository.class), mock(NormalizedDocumentRepository.class));
  private final BuildStatus ready = new BuildStatus("READY", false, Instant.EPOCH, Instant.parse("2026-11-20T10:15:30Z"), 2, 1, 1, 1, null);

  private void viewer() {
    when(access.currentUser()).thenReturn(mock(AppUser.class));
    when(access.require(crateId, Role.VIEWER)).thenReturn(Role.VIEWER);
    when(access.activeElevation(crateId)).thenReturn(Optional.empty());
    when(crateService.require(crateId, Role.VIEWER)).thenReturn(new Crate(crateId, "Handbook", null, null));
  }

  @Test
  void viewersSeeTheGraphPageButNoRebuildButton() {
    viewer();
    var settingsValue = new RagSettings(crateId, false, true, true, true, false, false, "revise-once", "hybrid", 8);
    settingsValue.updateGraphRag(true, false, "auto");
    when(settings.current(crateId)).thenReturn(settingsValue);
    when(store.status(crateId)).thenReturn(ready);
    var model = new ConcurrentModel();
    ui.crate(crateId, model);
    assertThat(ui.page(crateId, model)).isEqualTo("knowledge-graph");
    String html = render(model);
    assertThat(html).contains("Knowledge Graph").contains("x-data=\"knowledgeGraph($el.dataset.crateId)\"").contains("data-crate-id=\"" + crateId + "\"")
        .contains("2 entities · 1 edges · 1 communities on 1 level(s)").contains("Built 2026-11-20 10:15 UTC")
        .contains("Zoom in").contains("Zoom out").contains("Community level")
        .doesNotContain("Rebuild graph").doesNotContain("innerHTML").doesNotContain("Graph RAG is switched off");
    // The sidebar entry sits right after Evaluation and is active on this page.
    assertThat(html).contains("/crates/" + crateId + "/knowledge-graph");
  }

  @Test
  void ownersCanRebuildViewersCannot() {
    doThrow(new AccessDeniedException("no")).when(access).requireMutable(crateId, Role.OWNER);
    assertThatThrownBy(() -> api.rebuild(crateId)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> ui.rebuild(crateId)).isInstanceOf(AccessDeniedException.class);
    verify(store, never()).markDirty(any());
  }

  @Test
  void nonMembersCannotReadTheGraph() {
    when(access.require(crateId, Role.VIEWER)).thenThrow(new AccessDeniedException("no"));
    assertThatThrownBy(() -> api.graph(crateId, 100)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(graphs);
  }

  @Test
  void theGraphViewCapsNodesAndKeepsOnlyEdgesBetweenShownNodes() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), top = UUID.randomUUID();
    when(store.status(crateId)).thenReturn(ready);
    when(graphs.graph(crateId)).thenReturn(new Graph(
        List.of(new Entity(a, "A", "a", "T", "d", 5, 1, 2), new Entity(b, "B", "b", "T", "d", 3, 1, 1), new Entity(c, "C", "c", "T", "d", 1, 1, 1)),
        List.of(new Edge(UUID.randomUUID(), a, b, 2, 1, 0, null), new Edge(UUID.randomUUID(), a, c, 1, 1, 0, null)),
        Map.of(), List.of(new Community(top, 0, null, "Top", "s", 5, 3, "f", List.of(a, b, c)))));
    var view = api.graph(crateId, 2);
    assertThat(view.nodes()).extracting(KnowledgeGraphApiController.NodeView::name).containsExactly("A", "B");
    assertThat(view.edges()).hasSize(1);
    assertThat(view.truncated()).isTrue();
    assertThat(view.totalNodes()).isEqualTo(3);
    assertThat(view.nodes().getFirst().communities()).containsExactly(top);
  }

  private String render(ConcurrentModel model) {
    var resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setCharacterEncoding("UTF-8");
    var engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication
        .buildApplication(new org.springframework.mock.web.MockServletContext())
        .buildExchange(new org.springframework.mock.web.MockHttpServletRequest(), new org.springframework.mock.web.MockHttpServletResponse());
    var context = new org.thymeleaf.context.WebContext(exchange, Locale.ENGLISH, model.asMap());
    context.setVariable("_csrf", Map.of("parameterName", "_csrf", "token", "t", "headerName", "X-CSRF-TOKEN"));
    context.setVariable("currentPath", "/crates/" + crateId + "/knowledge-graph");
    context.setVariable("applicationVersion", "test");
    return engine.process("knowledge-graph", context);
  }
}
