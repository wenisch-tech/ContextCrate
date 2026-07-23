package tech.wenisch.contextcrate.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.service.CrateService;

@Controller
public class HomeUiController {
  private final CrateService crates;
  public HomeUiController(CrateService crates){this.crates=crates;}
  @GetMapping("/login") String login(){return "login";}
  @GetMapping("/") String home(Model model){
    var accessible=crates.accessible();
    if(accessible.size()==1)return "redirect:/crates/"+accessible.getFirst().getId();
    populate(model,accessible);return "crates";
  }
  @GetMapping("/crates") String list(Model model){populate(model,crates.accessible());return "crates";}
  @PostMapping("/crates") String create(@RequestParam String name,@RequestParam(defaultValue="")String description){
    return "redirect:/crates/"+crates.create(name,description).getId();
  }
  private void populate(Model model,java.util.List<tech.wenisch.contextcrate.domain.Crate> accessible){
    model.addAttribute("crates",accessible);
    model.addAttribute("canCreateCrate",crates.canCreate());
  }
}
