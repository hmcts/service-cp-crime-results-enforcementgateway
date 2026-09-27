# Inbound access to `POST /hearingResulted`: network isolation (iteration 1)

**Audience**: platform/deploy team onboarding this service.
**Requirement**: CIMD-4246 FR-018 / workflow research.md R21. The endpoint changes live GOB (Libra)
enforcement accounts, so it must accept requests **only from `service-cp-crime-results-enforcementworkflow`**
and must not be reachable from outside the platform's internal network.
**Status**: iteration 1 enforces this with network isolation only. There is **no request authentication in
the service yet**. Service-to-service bearer auth (Entra, the `service-cp-crime-results-pcr` pattern) is a
later step, owned by the tech lead.

> ⚠️ **Required before any environment beyond local receives this gateway version.**

## 1. No ingress / external route

Do **not** expose `/hearingResulted` through any Ingress, Application Gateway, APIM product or public
route. The service needs only a cluster-internal `Service` (ClusterIP) on port **8082**. Actuator probes stay
as today.

## 2. NetworkPolicy: admit only the workflow

Adjust the namespace, the labels and whether both services share a namespace to match the deploy repo's
conventions. The intent is: ingress to the gateway's port 8082 only from the workflow's pods, plus the
kubelet/ingress for probes if the cluster's probe traffic is subject to NetworkPolicy.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: enforcementgateway-allow-workflow-only
  namespace: <gateway-namespace>
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: service-cp-crime-results-enforcementgateway
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: <workflow-namespace>
          podSelector:
            matchLabels:
              app.kubernetes.io/name: service-cp-crime-results-enforcementworkflow
      ports:
        - protocol: TCP
          port: 8082
```

> ⚠️ **Actuator shares port 8082** (health, Prometheus). There is no `management.server.port`, so this policy on its own also blocks metrics scraping and, on some clusters, liveness/readiness probes. Before applying it, either (a) move actuator to its own management port (`management.server.port`, e.g. 8092) and allow the monitoring/probe sources on that port, or (b) add ingress rules for the monitoring namespace and the probe source on 8082. This is the platform team's choice (workflow research.md open item 15, tasks.md T089).

If the cluster runs a default-deny policy, this is the only allow rule needed for application traffic.
If it doesn't, add a default-deny ingress policy for the gateway pods as well, or the allow rule has no
effect.

## 3. Verify

From a pod that is **not** the workflow (e.g. a throwaway `curlimages/curl` pod in the same namespace):

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://<gateway-service>:8082/hearingResulted \
  -H 'Content-Type: application/json' -d '{}'
# expected: connection refused / timeout (blocked by the NetworkPolicy)
```

From the workflow pod:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://<gateway-service>:8082/hearingResulted \
  -H 'Content-Type: application/json' -d '{}'
# expected: 400 (reached the gateway; the empty payload is rejected by contract validation, and nothing is sent to Libra)
```

## 4. Later: bearer auth (tasks.md T086)

When the tech lead confirms it, the gateway validates an Entra access token on `/hearingResulted`
(audience + allowed caller = the workflow's managed identity), and the workflow sends
`Authorization: Bearer <token>`. The NetworkPolicy stays in place as defence in depth.
