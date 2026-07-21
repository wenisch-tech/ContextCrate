# Security

- Change the generated administrator password immediately and terminate TLS at a trusted ingress or reverse proxy.
- Keep SSRF blocking enabled. It is re-evaluated after DNS resolution and on every redirect.
- Keep robots enforcement enabled unless you own or are explicitly authorized to crawl the target.
- Run browser workers with minimal Kubernetes permissions, no service-account token, constrained egress, and separate resource limits.
- Artifact keys are normalized and cannot escape the configured filesystem root.
- Queue messages contain references and identifiers, never raw content or credentials.
- Do not store secrets in job JSON. S3, database, RabbitMQ, and OpenSearch credentials come from environment/secret stores.
- Embedding API keys must be injected from a secret store. Remote embedding providers receive the indexed/query text, so use TLS and approve the endpoint as a data processor.
- Answer-generation API keys may come from a secret store or the authenticated Settings page. Settings-entered keys are database-resident overrides, so restrict administrative access and protect database backups with encryption. Answer providers receive the question, caller-supplied history, and retrieved chunk context; Harvex treats that context as untrusted and does not persist it in audit logs.

Local users are BCrypt-backed through Spring Security. The initial user is created only when the user table is empty. OIDC dependencies are present for deployment integration; provider registration is configured through standard Spring Security properties.
