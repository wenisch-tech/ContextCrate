package tech.wenisch.contextcrate.config;

import java.util.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.repository.AppUserRepository;

/** Creates local accounts for OIDC users and maps Keycloak's ContextCrate_Admin role. */
@Service
public class KeycloakOidcUserService extends OidcUserService {
  static final String ADMIN_ROLE = "ContextCrate_Admin";
  private final AppUserRepository users;

  public KeycloakOidcUserService(AppUserRepository users) {
    this.users = users;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    OidcUser oidcUser = super.loadUser(request);
    String email = email(oidcUser.getClaims());
    boolean admin = hasContextCrateAdminRole(oidcUser.getClaims());
    AppUser user = users.findByEmailIgnoreCase(email)
        .orElseGet(() -> new AppUser(UUID.randomUUID(), email, "{noop}oidc", "USER", false));
    user.role(admin ? "ADMIN" : "USER");
    users.save(user);

    Set<GrantedAuthority> authorities = new LinkedHashSet<>(oidcUser.getAuthorities());
    authorities.add(new OAuth2UserAuthority(oidcUser.getClaims()));
    authorities.add(new SimpleGrantedAuthority(admin ? "ROLE_ADMIN" : "ROLE_USER"));
    return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), "email");
  }

  static boolean hasContextCrateAdminRole(Map<String, Object> claims) {
    return roles(claims).stream().anyMatch(ADMIN_ROLE::equalsIgnoreCase);
  }

  private static String email(Map<String, Object> claims) {
    Object value = claims.get("email");
    if (value instanceof String email && !email.isBlank()) return email;
    throw new IllegalArgumentException("OIDC provider did not supply an email claim");
  }

  @SuppressWarnings("unchecked")
  private static Set<String> roles(Map<String, Object> claims) {
    Set<String> result = new HashSet<>();
    addRoles(result, claims.get("roles"));
    if (claims.get("realm_access") instanceof Map<?, ?> realm) addRoles(result, realm.get("roles"));
    if (claims.get("resource_access") instanceof Map<?, ?> resources)
      resources.values().forEach(value -> {
        if (value instanceof Map<?, ?> client) addRoles(result, client.get("roles"));
      });
    return result;
  }

  private static void addRoles(Set<String> target, Object roles) {
    if (roles instanceof Collection<?> values)
      values.stream().filter(String.class::isInstance).map(String.class::cast).forEach(target::add);
  }
}
