# OIDC with Keycloak

ContextCrate supports OpenID Connect login through Spring Security. On a successful OIDC login,
ContextCrate creates the local application account if it does not already exist, using the verified,
signed ID token's `email` claim as its identity. If Keycloak does not supply email, it uses
`preferred_username`.
That username must be unique and stable: changing it creates a separate local account. Local crate
memberships remain managed by ContextCrate.

## Keycloak realm

Create a realm and a confidential OIDC client. The client must use the authorization-code flow and
request `openid`, `profile`, and `email`. Configure the exact callback URL:

```text
https://app.example.com/login/oauth2/code/keycloak
```

ContextCrate does not call Keycloak's UserInfo endpoint. Configure Keycloak protocol mappers so
the signed ID token contains either `email` or `preferred_username`. To synchronize administrator
access, include assigned realm or client roles in the ID token as well.

Allow user registration in the realm when users should self-register. In Keycloak this is the
**User registration** realm setting. Configure email verification and SMTP before enabling it on
an Internet-facing deployment.

## ContextCrate configuration

Configure Spring Security's standard provider registration. The issuer URI must be externally
reachable by the user browser and by the ContextCrate control-plane pod.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: contextcrate
            client-secret: ${CONTEXTCRATE_OIDC_CLIENT_SECRET}
            scope: openid,profile,email
        provider:
          keycloak:
            issuer-uri: https://auth.example.com/realms/contextcrate
```

The login page exposes **Sign in with Keycloak** at
`/oauth2/authorization/keycloak`.

## HTTPS reverse proxies

ContextCrate automatically uses standard forwarded headers when deriving its public URL. This makes
the callback URL HTTPS when TLS terminates at an ingress or reverse proxy. Ensure the proxy sends
`X-Forwarded-Proto`, `X-Forwarded-Host`, and, when needed, `X-Forwarded-Port`; Kubernetes ingress
controllers normally provide these headers. Do not expose the application directly to untrusted
clients that can supply those headers themselves.

## Self-signed or internal CA certificates

If the issuer is served with a certificate ContextCrate's JVM does not already trust (common for
internal Keycloak instances), startup fails with a `PKIX path building failed` error while
resolving `.well-known/openid-configuration`. Prefer importing the CA into the JVM truststore.
When that is not possible, set:

```yaml
contextcrate:
  security:
    oidc:
      trust-all-certificates: ${CONTEXTCRATE_SECURITY_OIDC_TRUST_ALL_CERTIFICATES:false}
```

or the environment variable `CONTEXTCRATE_SECURITY_OIDC_TRUST_ALL_CERTIFICATES=true`. This skips
TLS certificate validation for OIDC issuer discovery, token exchange, and ID token JWKS retrieval
only — it does not affect any other outbound connection. Enable it only for identity
providers you already trust on an internal/self-signed CA; it removes protection against a
network-level attacker impersonating the identity provider.

`CONTEXTCRATE_TLS_TRUST_ALL_CERTIFICATES=true` (see [Configuration](../configuration.md#tls-certificate-validation))
implies this flag, plus the same trust-all behavior for every other outbound connection
ContextCrate makes. Prefer the OIDC-only flag above unless other integrations also need it.

With the Helm chart, set:

```yaml
security:
  oidc:
    enabled: true
    trustAllCertificates: true
```

## Global administrator mapping

Create a Keycloak role named exactly `ContextCrate_Admin` and assign it to a user. ContextCrate
reads realm roles, client roles, and a top-level `roles` claim on every login. A user holding that
role is synchronized to ContextCrate's global `ADMIN` role and active session; without it the user
is synchronized to `USER` on their next login.

Global administrators still need a crate membership, or a temporary audited administrator
elevation, to read or modify crate content. This preserves ContextCrate's crate isolation model.

## Kubernetes deployment

The supplied GitOps deployment creates `auth.contextcrate.eu`, imports a `contextcrate` realm,
enables self-registration, and creates the confidential `contextcrate` client. It stores the
Keycloak bootstrap administrator password and OIDC client secret in Kubernetes Secrets. Rotate
both after initial deployment and update the client secret in both Keycloak and the ContextCrate
deployment secret together.
