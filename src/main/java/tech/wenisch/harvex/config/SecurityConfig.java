package tech.wenisch.harvex.config;

import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tech.wenisch.harvex.domain.AppUser;
import tech.wenisch.harvex.repository.AppUserRepository;

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
  SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeys)
      throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/webjars/**", "/css/**", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(apiKeys, UsernamePasswordAuthenticationFilter.class)
        .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
        .logout(l -> l.logoutSuccessUrl("/login?logout"))
        .httpBasic(c -> {})
        .csrf(c -> c.ignoringRequestMatchers("/api/v1/**"))
        .build();
  }

  @Bean
  CommandLineRunner initializeAdmin(
      AppUserRepository users, PasswordEncoder encoder, HarvexProperties properties) {
    return args -> {
      if (users.count() == 0)
        users.save(
            new AppUser(
                UUID.randomUUID(),
                properties.security().initialAdminEmail(),
                encoder.encode(properties.security().initialAdminPassword())));
    };
  }
}
