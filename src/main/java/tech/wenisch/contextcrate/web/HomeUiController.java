package tech.wenisch.contextcrate.web;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.CrateOverviewService;
import tech.wenisch.contextcrate.service.CrateService;

@Controller
public class HomeUiController {
  private final CrateService crates;
  private final CrateOverviewService overview;
  private final CrateAccessService access;
  public HomeUiController(CrateService crates,CrateOverviewService overview,CrateAccessService access){
    this.crates=crates;this.overview=overview;this.access=access;
  }
  @GetMapping("/login") String login(){return "login";}
  @GetMapping("/") String home(Model model){
    var accessible=crates.accessible();
    if(accessible.size()==1)return "redirect:/crates/"+accessible.getFirst().getId();
    populate(model);return "crates";
  }
  @GetMapping("/crates") String list(Model model){populate(model);return "crates";}
  @PostMapping("/crates") String create(@RequestParam String name,@RequestParam(defaultValue="")String description){
    return "redirect:/crates/"+crates.create(name,description).getId();
  }
  private void populate(Model model){
    List<CrateOverviewService.CrateCard> cards=overview.accessible();
    model.addAttribute("cards",cards);
    model.addAttribute("crates",cards.stream().map(CrateOverviewService.CrateCard::crate).toList());
    model.addAttribute("canCreateCrate",crates.canCreate());
    model.addAttribute("isAdmin",access.isAdmin());
  }
}
