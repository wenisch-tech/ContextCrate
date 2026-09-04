package tech.wenisch.contextcrate.config;

import java.util.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.repository.AppUserRepository;
import tech.wenisch.contextcrate.service.OnboardingService;

/** Creates local accounts for OIDC users and maps Keycloak's ContextCrate_Admin role. */
@Service
public class KeycloakOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
  static final String ADMIN_ROLE = "ContextCrate_Admin";
  private final AppUserRepository users;
  private final OnboardingService onboarding;

  public KeycloakOidcUserService(AppUserRepository users, OnboardingService onboarding) {
    this.users = users; this.onboarding = onboarding;
  }

  @Override
  @Transactional
  public OidcUser loadUser(OidcUserRequest request) {
    // The ID token was signature- and claim-validated by Spring Security before this service is
    // invoked. Keycloak deployments commonly disable the UserInfo endpoint, so do not make a
    // second request for the same identity data here.
    Map<String, Object> claims = new LinkedHashMap<>(request.getIdToken().getClaims());
    String identifier = identifier(claims);
    boolean admin = hasContextCrateAdminRole(claims);
    Optional<AppUser> existing = users.findByEmailIgnoreCase(identifier);
    AppUser user = existing.orElseGet(() -> new AppUser(UUID.randomUUID(), identifier, "{noop}oidc", "USER", false));
    user.role(admin ? "ADMIN" : "USER");
    users.save(user);
    if (existing.isEmpty()) onboarding.applyToNewUser(user);

    Set<GrantedAuthority> authorities = new LinkedHashSet<>();
    // The local user model identifies users by its email field. Give the OIDC principal the same
    // stable name when Keycloak supplied preferred_username instead of an email address.
    if (stringClaim(claims, "email") == null) claims.put("email", identifier);
    authorities.add(new OAuth2UserAuthority(claims));
    authorities.add(new SimpleGrantedAuthority(admin ? "ROLE_ADMIN" : "ROLE_USER"));
    return new DefaultOidcUser(authorities, request.getIdToken(), new OidcUserInfo(claims), "email");
  }

  static boolean hasContextCrateAdminRole(Map<String, Object> claims) {
    return roles(claims).stream().anyMatch(ADMIN_ROLE::equalsIgnoreCase);
  }

  static String identifier(Map<String, Object> claims) {
    String email = stringClaim(claims, "email");
    if (email != null) return email;
    String username = stringClaim(claims, "preferred_username");
    if (username != null) return username;
    throw new IllegalArgumentException(
        "OIDC provider did not supply an email or preferred_username claim");
  }

  private static String stringClaim(Map<String, Object> claims, String name) {
    Object value = claims.get(name);
    return value instanceof String text && !text.isBlank() ? text.trim() : null;
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
