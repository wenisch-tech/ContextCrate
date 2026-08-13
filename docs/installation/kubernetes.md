# Kubernetes

Kubernetes is the recommended production installation method. The Helm chart is located in `charts/contextcrate` and supports standalone and distributed profiles.

## Prerequisites

- Kubernetes cluster and `kubectl`
- Helm 3
- An Ingress controller and TLS configuration
- A storage class for persistent volumes
- For distributed deployments: PostgreSQL, RabbitMQ, S3-compatible object storage, and OpenSearch

## Standalone Helm install

This is a compact Kubernetes installation using one replica and one persistent volume:

```bash
helm upgrade --install contextcrate charts/contextcrate \
  --namespace contextcrate --create-namespace \
  --set image.tag=latest \
  --set secrets.CONTEXTCRATE_ADMIN_PASSWORD=change-me \
  --set ingress.enabled=true \
  --set ingress.className=nginx \
  --set ingress.host=contextcrate.example.com
```

The chart mounts `/app/data` from its PVC. Do not scale standalone beyond one replica: its H2 database and Lucene index are singleton components.

## Distributed Helm install

Create a values file that points to production backing services and enables each role. Keep credentials in `secrets` or external secret management, not in a committed values file.

```yaml
profile: distributed
roles:
  all: { enabled: false, replicas: 0 }
  control-plane: { enabled: true, replicas: 1 }
  source-web: { enabled: true, replicas: 3 }
  source-git: { enabled: true, replicas: 2 }
  crawler-browser: { enabled: true, replicas: 1 }
  parser: { enabled: true, replicas: 2 }
  indexer: { enabled: true, replicas: 2 }
env:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgresql.example.internal:5432/contextcrate
  SPRING_DATASOURCE_USERNAME: contextcrate
  SPRING_RABBITMQ_HOST: rabbitmq.example.internal
  S3_ENDPOINT: https://s3.example.internal
  S3_BUCKET: contextcrate
  OPENSEARCH_ENDPOINT: https://opensearch.example.internal:9200
secrets:
  CONTEXTCRATE_ADMIN_PASSWORD: change-me
  SPRING_DATASOURCE_PASSWORD: change-me
  AWS_ACCESS_KEY_ID: change-me
  AWS_SECRET_ACCESS_KEY: change-me
```

Install it with:

```bash
helm upgrade --install contextcrate charts/contextcrate \
  --namespace contextcrate --create-namespace \
  --values values-production.yaml
```

Scale `source-web`, `source-git`, and `parser` based on workload. OpenSearch-backed indexers can scale; keep Lucene indexers at one replica. Configure queue-aware autoscaling externally, for example with KEDA and RabbitMQ queue length.

## Verify and operate

```bash
kubectl get pods,svc,ingress -n contextcrate
kubectl rollout status deployment/contextcrate-all -n contextcrate
```

For a distributed release, check the control plane instead:

```bash
kubectl rollout status deployment/contextcrate-control-plane -n contextcrate
```

Use an Ingress with TLS, enforce network policies, and give browser workers restrictive egress and minimal Kubernetes permissions. Back up PostgreSQL, object storage, and deployment secrets together; OpenSearch indexes are derived and can be rebuilt from normalized documents. See [Security](../security.md), [Operations](../operations.md), and [Deployment Notes](../deployment.md).
