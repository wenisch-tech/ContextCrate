package tech.wenisch.contextcrate.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** Ensures the OIDC login flow cannot silently fall back to plain OAuth2 when scopes are omitted. */
final class OidcClientRegistrationRepository implements ClientRegistrationRepository {
  private final ClientRegistrationRepository delegate;

  OidcClientRegistrationRepository(ClientRegistrationRepository delegate) {
    this.delegate = delegate;
  }

  @Override
  public ClientRegistration findByRegistrationId(String id) {
    ClientRegistration registration = delegate.findByRegistrationId(id);
    if (registration == null) return null;
    return ClientRegistration.withClientRegistration(registration)
        .scope(scopes(registration.getScopes())).build();
  }

  static Set<String> scopes(Collection<String> configured) {
    Set<String> scopes = new LinkedHashSet<>(List.of("openid", "profile", "email"));
    if (configured != null) {
      configured.stream().filter(value -> value != null && !value.isBlank())
          .map(String::trim).forEach(scopes::add);
    }
    return scopes;
  }
}
