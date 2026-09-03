package tech.wenisch.contextcrate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.mcp.McpCrateAccessFilter;
import tech.wenisch.contextcrate.repository.ApiKeyRepository;
import tech.wenisch.contextcrate.repository.AppUserRepository;
import tech.wenisch.contextcrate.repository.CrateMemberRepository;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the real authorization redirect and callback filters, without mocking OAuth login. */
class OidcLoginIntegrationTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private HttpServer provider;
  private AnnotationConfigWebApplicationContext context;
  private MockMvc mvc;
  private RSAKey signingKey;
  private String issuer;
  private volatile String idToken;
  private volatile boolean rejectCode;
  private volatile String idTokenEmail = "oidc@example.com";
  private volatile String idTokenUsername;
  private final AtomicInteger userInfoRequests = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://127.0.0.1:" + provider.getAddress().getPort();
    provider.createContext("/.well-known/openid-configuration", exchange -> reply(exchange, 200,
        Map.of("issuer", issuer, "authorization_endpoint", issuer + "/authorize",
            "token_endpoint", issuer + "/token", "userinfo_endpoint", issuer + "/userinfo",
            "jwks_uri", issuer + "/jwks", "response_types_supported", List.of("code"),
            "subject_types_supported", List.of("public"),
            "id_token_signing_alg_values_supported", List.of("RS256"))));
    provider.createContext("/jwks", exchange -> reply(exchange, 200,
        new JWKSet(signingKey.toPublicJWK()).toJSONObject()));
    provider.createContext("/token", exchange -> {
      exchange.getRequestBody().readAllBytes();
      if (rejectCode) {
        reply(exchange, 400, Map.of("error", "invalid_grant", "error_description", "Code not valid"));
      } else {
        reply(exchange, 200, Map.of("access_token", "test-access-token", "token_type", "Bearer",
            "expires_in", 300, "scope", "openid profile email", "id_token", idToken));
      }
    });
    provider.createContext("/userinfo", exchange -> {
      userInfoRequests.incrementAndGet();
      // This reproduces Keycloak's disabled/forbidden UserInfo endpoint. A valid callback must
      // still authenticate from its signed ID token without touching this endpoint.
      exchange.sendResponseHeaders(403, -1);
      exchange.close();
    });
    provider.start();

    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
        "contextcrate.security.oidc.enabled=true",
        "contextcrate.security.oidc.trust-all-certificates=true",
        "spring.security.oauth2.client.registration.keycloak.client-id=contextcrate",
        "spring.security.oauth2.client.registration.keycloak.client-secret=test-secret",
        "spring.security.oauth2.client.registration.keycloak.scope=openid,profile,email",
        "spring.security.oauth2.client.provider.keycloak.issuer-uri=" + issuer);
    context.register(LoginTestConfiguration.class);
    context.refresh();
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new ForwardedHeaderFilter())
        .apply(springSecurity()).build();
  }

  @AfterEach
  void tearDown() {
    if (context != null) context.close();
    if (provider != null) provider.stop(0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void callbackAuthenticatesAndSynchronizesLocalRoleWithoutCallingForbiddenUserInfo(boolean admin)
      throws Exception {
    var login = mvc.perform(get("/oauth2/authorization/keycloak").with(httpsProxy()))
        .andExpect(status().is3xxRedirection()).andReturn();
    var parameters = UriComponentsBuilder.fromUriString(login.getResponse().getRedirectedUrl())
        .build().getQueryParams();
    assertThat(parameters.getFirst("redirect_uri"))
        .isEqualTo("https://harvex.example.com/login/oauth2/code/keycloak");
    idToken = signedIdToken(UriUtils.decode(parameters.getFirst("nonce"), StandardCharsets.UTF_8), admin);
    var session = (MockHttpSession) login.getRequest().getSession(false);

    mvc.perform(get("/login/oauth2/code/keycloak").session(session)
            .param("code", "test-code")
            .param("state", UriUtils.decode(parameters.getFirst("state"), StandardCharsets.UTF_8)))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));

    var security = (SecurityContext) session.getAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(security).isNotNull();
    assertThat(security.getAuthentication().isAuthenticated()).isTrue();
    assertThat(security.getAuthentication().getName()).isEqualTo("oidc@example.com");
    assertThat(security.getAuthentication().getAuthorities()).extracting("authority")
        .contains(admin ? "ROLE_ADMIN" : "ROLE_USER")
        .doesNotContain(admin ? "ROLE_USER" : "ROLE_ADMIN");
    var saved = ArgumentCaptor.forClass(AppUser.class);
    verify(context.getBean(AppUserRepository.class)).save(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo("oidc@example.com");
    assertThat(saved.getValue().getRole()).isEqualTo(admin ? "ADMIN" : "USER");
    assertThat(userInfoRequests.get()).isZero();
  }

  @Test
  void rejectedCodeReturnsToLoginWithoutCreatingAnAccount() throws Exception {
    rejectCode = true;
    var login = mvc.perform(get("/oauth2/authorization/keycloak")).andReturn();
    var state = UriComponentsBuilder.fromUriString(login.getResponse().getRedirectedUrl())
        .build().getQueryParams().getFirst("state");
    var session = (MockHttpSession) login.getRequest().getSession(false);

    mvc.perform(get("/login/oauth2/code/keycloak").session(session)
            .param("code", "expired-code").param("state", UriUtils.decode(state, StandardCharsets.UTF_8)))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?oidcError"));

    assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
        .isNull();
    verifyNoInteractions(context.getBean(AppUserRepository.class));
  }

  @Test
  void callbackCreatesUserFromPreferredUsernameWhenEmailIsAbsent() throws Exception {
    idTokenEmail = null;
    idTokenUsername = "keycloak-user";
    var login = mvc.perform(get("/oauth2/authorization/keycloak")).andReturn();
    var parameters = UriComponentsBuilder.fromUriString(login.getResponse().getRedirectedUrl())
        .build().getQueryParams();
    idToken = signedIdToken(UriUtils.decode(parameters.getFirst("nonce"), StandardCharsets.UTF_8), false);
    var session = (MockHttpSession) login.getRequest().getSession(false);

    mvc.perform(get("/login/oauth2/code/keycloak").session(session).param("code", "test-code")
            .param("state", UriUtils.decode(parameters.getFirst("state"), StandardCharsets.UTF_8)))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));

    var security = (SecurityContext) session.getAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(security.getAuthentication().getName()).isEqualTo("keycloak-user");
    var saved = ArgumentCaptor.forClass(AppUser.class);
    verify(context.getBean(AppUserRepository.class)).save(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo("keycloak-user");
    assertThat(saved.getValue().getRole()).isEqualTo("USER");
  }

  private String signedIdToken(String nonce, boolean admin) throws JOSEException {
    Instant now = Instant.now();
    var builder = new JWTClaimsSet.Builder().issuer(issuer).subject("keycloak-user")
        .audience("contextcrate").issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
        .claim("nonce", nonce)
        .claim("realm_access", Map.of("roles", List.of(admin ? "ContextCrate_Admin" : "user")));
    if (idTokenEmail != null) builder.claim("email", idTokenEmail);
    if (idTokenUsername != null) builder.claim("preferred_username", idTokenUsername);
    var claims = builder.build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  private static RequestPostProcessor httpsProxy() {
    return request -> {
      request.addHeader("X-Forwarded-Proto", "https");
      request.addHeader("X-Forwarded-Host", "harvex.example.com");
      request.addHeader("X-Forwarded-Port", "443");
      return request;
    };
  }

  private void reply(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
    byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  @EnableWebMvc
  @Import({SecurityConfig.class, OidcTrustConfig.class, KeycloakOidcUserService.class})
  static class LoginTestConfiguration {
    @Bean AppUserRepository users() { return mock(AppUserRepository.class); }
    @Bean CrateMemberRepository members() { return mock(CrateMemberRepository.class); }
    @Bean ContextCrateProperties properties() { return mock(ContextCrateProperties.class); }
    @Bean ApiKeyAuthenticationFilter apiKeys() {
      return new ApiKeyAuthenticationFilter(mock(ApiKeyRepository.class));
    }
    @Bean McpCrateAccessFilter mcpCrateAccess() {
      return new McpCrateAccessFilter(mock(CrateAccessService.class));
    }
  }
}
