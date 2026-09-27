# Enforcement Hearing Gateway (service)

`service-cp-crime-results-enforcementgateway`

A Common Platform (CP) Spring Boot service that owns the **outbound integration from CP to the
Libra/GoB enforcement system for court hearings**.

When an enforcement case is allocated to a court hearing — or an existing allocation is amended — this
service keeps Libra (GoB) in sync with the confirmed hearing details:

- **Hearing confirmation:** on allocation, POST a `confirmedHearing` payload
  (`caseUrn`, `courtHearingLocation`, `dateOfHearing`, `timeOfHearing`) to Libra via APIM.
- **Hearing updates:** on any amendment to an allocated enforcement hearing, re-POST the latest
  `confirmedHearing` snapshot.

It is **event-driven**: it subscribes to CP listing public events (`public.listing.hearing-confirmed`
/ `public.listing.hearing-updated`), filters to enforcement cases, enriches, maps, and calls Libra.

> Owned by the **cp-case-ingestion-and-material** team. This service is the strangler-fig successor for
> the CP↔Libra/GoB enforcement integration currently in the legacy WildFly context
> `cpp-context-staging-enforcement`; that context is unchanged for now and will be migrated
> incrementally. Distinct from the GOB Resulting Workstream service, which owns results→GoB + NOWs.

API contract: [`api-cp-crime-results-enforcementgateway`](https://github.com/hmcts/api-cp-crime-results-enforcementgateway).

Created from the HMCTS template
[`service-hmcts-crime-springboot-template`](https://github.com/hmcts/service-hmcts-crime-springboot-template).
The domain implementation is built: `HearingAllocationEventListener` consumes
`hearing-confirmed`/`hearing-updated`, `EnforcementHearingConfirmationService` enriches each case via
`ProsecutionCaseClient` (Progression's query-api) and filters to Enforcement-typed cases, and
`LibraClient` POSTs the mapped `confirmedHearing` payload to Libra via Azure APIM
(`cp.libra.apim-base-url`/`cp.libra.apim-subscription-key`, see `application.yaml`). Several
environment-specific values (Libra/APIM base URL and subscription key for prod, Progression's
query-api base URL, `CJSCPPUID`, and which deploy repo this service onboards into) are still
outstanding - see `libra-hearing-confirmation-plan.md` for the up-to-date list.

## Hearing result to Libra — `POST /hearingResulted` (CIMD-4246)

The gateway now also has a synchronous **inbound** endpoint, `POST /hearingResulted`. It is called only by
`service-cp-crime-results-enforcementworkflow`, which holds all the business logic. The gateway is a thin
outbound connection point:

- **Contract**: `postHearingResulted` in `api-cp-crime-results-enforcementgateway`. The generated
  `EnforcementHearingApi` is implemented by `HearingResultedController`. Request and response schemas are
  copied from Libra Gateway Hearing Event API v0.4.0.
- **Flow**: validate against the contract → `LibraClient.resultHearing` → Azure APIM
  (`cppi-v4`, operation `libra-hearingresulted`, `Ocp-Apim-Subscription-Key`) → Libra `POST /hearing/result`.
  Libra's `HearingResultedResponse` body is returned **unchanged**. Absent optional blocks are never
  sent as JSON nulls.
- **Responses**: `200` Libra's body; `400` payload doesn't match the contract (`error: INVALID_PAYLOAD`);
  `502` Libra/APIM rejected, failed or timed out (`error: LIBRA_CALL_FAILED`, `details.libraStatus` /
  `errorCode` / `errorDescription`; `libraStatus` null = no response). No retries.
- **Timeouts** (`cp.libra.connect-timeout-ms` 5000 / `cp.libra.read-timeout-ms` 40000, env
  `LIBRA_CONNECT_TIMEOUT_MS` / `LIBRA_READ_TIMEOUT_MS`): part of a budget where each hop gives up before
  its caller. APIM forward 35s < gateway 40s read; gateway 45s total < the workflow's 50s read. These
  also apply to the `confirmedHearing` call.
- **APIM**: the `libra-hearingresulted` operation and a body-returning mock are requested in
  [`docs/LibraApimMockPolicy.md`](docs/LibraApimMockPolicy.md).
- **Access control**: there is no request authentication in iteration 1. The endpoint must be reachable
  only from the enforcement workflow: see [`docs/InboundAccess.md`](docs/InboundAccess.md) (required
  before any deployment beyond local). Service-to-service bearer auth (the PCR Entra pattern) is a later
  step, pending the tech lead's decision.
- **Logging**: `caseUrn` and the outcome only. The payloads carry defendant PII and bank details and are never logged.

## Tech stack

- **Java 25**, **Spring Boot 4**, **Gradle**
- Observability: Spring Boot Actuator, OpenTelemetry, Prometheus
- Hosting: Azure (App Insights, ACR/AKS via the ADO mirror pipeline)

## Prerequisites

- ☕️ **Java 25 or later** on your `PATH`
- ⚙️ **Gradle** (the wrapper pins the version — `gradle/wrapper/gradle-wrapper.properties`)

```bash
java -version
gradle -v
```

## Build & test

```bash
gradle build      # compile + checks + unit/integration tests
gradle test       # unit and integration tests only
```

### Static analysis (PMD)

```bash
gradle pmdTest
```

## CI/CD

GitHub Actions workflows live in `.github/workflows`:

- `ci-draft.yml` — build/verify on PRs and branch pushes.
- `ci-released.yml` — on a **published GitHub Release** (`release: [published]`), publishes the
  artefact and triggers the Docker build/deploy via `ci-build-publish.yml` (with a Trivy image scan
  and a release-notes appender that records the published image coordinates).
- `code-analysis.yml`, `codeql.yml`, `secrets-scanner.yml`, `auto-merge-dependabot.yml`.

`main` and `team/*` branches are protected and require at least one approving review.

## Contributing

See [CONTRIBUTING.md](.github/CONTRIBUTING.md). Branch naming: `team/<topic>`.

## License

MIT — see [LICENSE](LICENSE).
