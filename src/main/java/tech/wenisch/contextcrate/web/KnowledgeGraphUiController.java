package tech.wenisch.contextcrate.web;

import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.graph.GraphStore;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.CrateService;

/** The knowledge graph explorer of a crate. Every crate member can open it; the graph data is loaded by the page. */
@Controller
@RequestMapping("/crates/{crateId}/knowledge-graph")
public class KnowledgeGraphUiController {
  private final CrateAccessService access;
  private final CrateService crateService;
  private final GraphStore store;
  private final RagSettingsService settings;

  public KnowledgeGraphUiController(CrateAccessService access, CrateService crateService, GraphStore store, RagSettingsService settings) {
    this.access = access; this.crateService = crateService; this.store = store; this.settings = settings;
  }

  @ModelAttribute
  void crate(@PathVariable UUID crateId, Model model) {
    model.addAttribute("crate", crateService.require(crateId, CrateMember.Role.VIEWER));
    model.addAttribute("crateRole", access.require(crateId, CrateMember.Role.VIEWER));
    model.addAttribute("crates", crateService.accessible());
    model.addAttribute("adminElevation", access.activeElevation(crateId).orElse(null));
    model.addAttribute("isAdmin", access.isAdmin());
    model.addAttribute("currentUser", access.currentUser());
  }

  @GetMapping
  String page(@PathVariable UUID crateId, Model model) {
    model.addAttribute("graphStatus", store.status(crateId));
    model.addAttribute("graphEnabled", settings.current(crateId).isGraphExtractionEnabled());
    return "knowledge-graph";
  }

  @PostMapping("/rebuild")
  String rebuild(@PathVariable UUID crateId) {
    access.requireMutable(crateId, CrateMember.Role.OWNER);
    store.markDirty(crateId);
    return "redirect:/crates/" + crateId + "/knowledge-graph?rebuildScheduled";
  }
}
