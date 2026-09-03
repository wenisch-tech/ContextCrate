package tech.wenisch.contextcrate.config;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import tech.wenisch.contextcrate.util.InsecureSsl;

/**
 * Skips TLS certificate validation for OIDC issuer discovery, token exchange, userinfo, and ID
 * token JWKS retrieval. Opt-in only, for identity providers behind an internal/self-signed CA.
 */
@Configuration
@ConditionalOnProperty(
    name = {"contextcrate.security.oidc.enabled", "contextcrate.security.oidc.trust-all-certificates"},
    havingValue = "true")
@EnableConfigurationProperties(ContextCrateOAuth2ClientProperties.class)
public class OidcTrustConfig {

  @Bean
  InsecureOidcHttpClients insecureOidcHttpClients() {
    HttpClient httpClient = HttpClient.newBuilder().sslContext(InsecureSsl.trustAllContext()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    RestTemplate restTemplate = new RestTemplate(requestFactory);
    // Replacing the token client's RestClient must preserve Spring Security's OAuth converters.
    RestClient restClient = RestClient.builder()
        .requestFactory(requestFactory)
        .configureMessageConverters(converters -> {
          converters.addCustomConverter(new FormHttpMessageConverter());
          converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
        })
        .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
        .build();
    return new InsecureOidcHttpClients(restTemplate, restClient);
  }

  @Bean
  @SuppressWarnings("unchecked")
  ClientRegistrationRepository clientRegistrationRepository(
      ContextCrateOAuth2ClientProperties properties, InsecureOidcHttpClients insecure) {
    List<ClientRegistration> registrations = new ArrayList<>();
    properties
        .getRegistration()
        .forEach(
            (id, registration) -> {
              String providerId =
                  registration.getProvider() != null ? registration.getProvider() : id;
              ContextCrateOAuth2ClientProperties.Provider provider =
                  properties.getProvider().get(providerId);
              if (provider == null || provider.getIssuerUri() == null)
                throw new IllegalStateException(
                    "contextcrate.security.oidc.trust-all-certificates requires an issuer-uri for"
                        + " registration '" + id + "'");
              String issuer = provider.getIssuerUri();
              String discoveryUrl =
                  (issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer)
                      + "/.well-known/openid-configuration";
              Map<String, Object> metadata =
                  insecure.restOperations().getForObject(discoveryUrl, Map.class);
              ClientRegistration.Builder builder =
                  ClientRegistrations.fromOidcConfiguration(metadata)
                      .registrationId(id)
                      .clientId(registration.getClientId())
                      .clientSecret(registration.getClientSecret())
                      .clientName(
                          registration.getClientName() != null ? registration.getClientName() : id);
              if (registration.getScope() != null && !registration.getScope().isEmpty())
                builder.scope(registration.getScope());
              registrations.add(builder.build());
            });
    return new InMemoryClientRegistrationRepository(registrations);
  }

  @Bean
  JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(InsecureOidcHttpClients insecure) {
    return clientRegistration -> {
      String issuerUri = clientRegistration.getProviderDetails().getIssuerUri();
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withIssuerLocation(issuerUri).restOperations(insecure.restOperations()).build();
      decoder.setJwtValidator(
          JwtValidators.createDefaultWithValidators(new OidcIdTokenValidator(clientRegistration)));
      decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
      return decoder;
    };
  }
}
