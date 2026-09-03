package tech.wenisch.contextcrate.config;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.repository.AppUserRepository;
import tech.wenisch.contextcrate.repository.CrateMemberRepository;
import tech.wenisch.contextcrate.domain.*;

@Configuration
public class SecurityConfig {
  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
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
      tech.wenisch.contextcrate.mcp.McpCrateAccessFilter mcpCrateAccess,
      AppUserRepository users, KeycloakOidcUserService oidcUsers,
      ObjectProvider<InsecureOidcHttpClients> insecureOidc,
      @Value("${contextcrate.security.oidc.enabled:false}") boolean oidcEnabled)
      throws Exception {
    HttpSecurity security = http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/css/**", "/js/**", "/img/**", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(apiKeys, UsernamePasswordAuthenticationFilter.class)
        // Inside the chain so a denial becomes the API's 403 rather than a server error.
        .addFilterAfter(mcpCrateAccess,
            org.springframework.security.web.access.intercept.AuthorizationFilter.class)
        .formLogin(f -> f.loginPage("/login").successHandler((request,response,authentication)->{
          boolean required=users.findByEmailIgnoreCase(authentication.getName())
              .map(AppUser::isPasswordChangeRequired).orElse(false);
          response.sendRedirect(required?"/change-password":"/");
        }).permitAll())
        .logout(l -> l.logoutSuccessUrl("/login?logout"))
        .httpBasic(c -> {})
        .exceptionHandling(
            e ->
                e.defaultAuthenticationEntryPointFor(
                        apiAuthenticationEntryPoint(),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**"))
                    .defaultAccessDeniedHandlerFor(
                        apiAccessDeniedHandler(),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**")))
        .csrf(c -> c.ignoringRequestMatchers("/api/v1/**"));
    if (oidcEnabled)
      security.oauth2Login(o -> {
        o.loginPage("/login")
            .failureHandler((request, response, exception) -> {
              log.warn("OIDC login failed: {}", exception.getMessage());
              response.sendRedirect("/login?oidcError");
            })
            .userInfoEndpoint(u -> u.oidcUserService(oidcUsers));
        insecureOidc.ifAvailable(insecure -> {
          var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();
          tokenResponseClient.setRestClient(insecure.restClient());
          o.tokenEndpoint(t -> t.accessTokenResponseClient(tokenResponseClient));
        });
      });
    return security.build();
  }

  /**
   * Answers unauthenticated API requests with 401 instead of the form-login redirect. Without this
   * an API or MCP client receives a 302 to an HTML sign-in page and cannot tell that its credential
   * was missing or rejected.
   */
  private static AuthenticationEntryPoint apiAuthenticationEntryPoint() {
    return (request, response, exception) -> {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setHeader("WWW-Authenticate", "Bearer realm=\"ContextCrate\"");
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"error\":\"Authentication required\"}");
    };
  }

  /** Answers an authorization failure on the API with 403 and JSON rather than an HTML page. */
  private static AccessDeniedHandler apiAccessDeniedHandler() {
    return (request, response, exception) -> {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"error\":\"Access denied\"}");
    };
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
