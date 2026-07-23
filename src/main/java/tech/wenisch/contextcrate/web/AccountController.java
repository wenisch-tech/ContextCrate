package tech.wenisch.contextcrate.web;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.repository.AppUserRepository;
import tech.wenisch.contextcrate.service.CrateAccessService;

@Controller
public class AccountController {
  private final CrateAccessService access;private final AppUserRepository users;
  private final PasswordEncoder passwords;
  public AccountController(CrateAccessService access,AppUserRepository users,PasswordEncoder passwords){
    this.access=access;this.users=users;this.passwords=passwords;
  }
  @GetMapping("/change-password") String form(){return "change-password";}
  @PostMapping("/change-password") String change(@RequestParam String currentPassword,
      @RequestParam String newPassword,@RequestParam String confirmation){
    var user=access.currentUser();
    if(!passwords.matches(currentPassword,user.getPasswordHash()))
      throw new IllegalArgumentException("Current password is incorrect");
    if(newPassword.length()<12)throw new IllegalArgumentException("New password must contain at least 12 characters");
    if(!newPassword.equals(confirmation))throw new IllegalArgumentException("Password confirmation does not match");
    user.changePassword(passwords.encode(newPassword));users.save(user);return "redirect:/";
  }
}
