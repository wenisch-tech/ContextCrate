package tech.wenisch.contextcrate.config;

import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.repository.AppUserRepository;
import tech.wenisch.contextcrate.repository.CrateMemberRepository;
import tech.wenisch.contextcrate.domain.*;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(AppUserRepository users) {
    return username -> {
      var user =
          users
              .findByEmailIgnoreCase(username)
              .orElseThrow(() -> new UsernameNotFoundException(username));
      return User.withUsername(user.getEmail())
          .password(user.getPasswordHash())
          .roles(user.getRole())
          .disabled(!user.isEnabled())
          .build();
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeys,
      AppUserRepository users, KeycloakOidcUserService oidcUsers,
      @Value("${contextcrate.security.oidc.enabled:false}") boolean oidcEnabled)
      throws Exception {
    HttpSecurity security = http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/webjars/**", "/css/**", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(apiKeys, UsernamePasswordAuthenticationFilter.class)
        .formLogin(f -> f.loginPage("/login").successHandler((request,response,authentication)->{
          boolean required=users.findByEmailIgnoreCase(authentication.getName())
              .map(AppUser::isPasswordChangeRequired).orElse(false);
          response.sendRedirect(required?"/change-password":"/");
        }).permitAll())
        .logout(l -> l.logoutSuccessUrl("/login?logout"))
        .httpBasic(c -> {})
        .csrf(c -> c.ignoringRequestMatchers("/api/v1/**"));
    if (oidcEnabled)
      security.oauth2Login(o -> o.loginPage("/login").userInfoEndpoint(u -> u.oidcUserService(oidcUsers)));
    return security.build();
  }

  @Bean
  CommandLineRunner initializeAdmin(
      AppUserRepository users, CrateMemberRepository members, PasswordEncoder encoder,
      ContextCrateProperties properties) {
    return args -> {
      AppUser admin;
      if (users.count() == 0)
        admin = users.save(
            new AppUser(
                UUID.randomUUID(),
                properties.security().initialAdminEmail(),
                encoder.encode(properties.security().initialAdminPassword())));
      else admin=users.findByEmailIgnoreCase(properties.security().initialAdminEmail())
          .orElseGet(()->users.findAll().stream().filter(u->"ADMIN".equals(u.getRole())).findFirst().orElseThrow());
      if (members.findByCrateIdAndUserId(CrateIds.LEGACY,admin.getId()).isEmpty())
        members.save(new CrateMember(CrateIds.LEGACY,admin.getId(),CrateMember.Role.OWNER,admin.getId()));
    };
  }
}
