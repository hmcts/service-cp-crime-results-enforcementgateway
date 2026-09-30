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
outstanding - see open item 9 in the workflow repo's `specs/001-cimd-4246-hearing-resulted-to-libra/research.md`.

Both JMS listeners (and so the whole confirmedHearing flow) run only under the `docker` Spring profile: a
deployment must set `SPRING_PROFILES_ACTIVE=docker`, or the service starts without consuming any events.

## Hearing result to Libra — `POST /hearingResulted` (CIMD-4246)

The gateway now also has a synchronous **inbound** endpoint, `POST /hearingResulted`. It is called only by
`service-cp-crime-results-enforcementworkflow`, which holds all the business logic. The gateway is a thin
outbound connection point:

- **Contract**: `postHearingResulted` in `api-cp-crime-results-enforcementgateway`. The generated
  `EnforcementHearingApi` is implemented by `HearingResultedController`. Request and response schemas are
  copied from Libra Gateway Hearing Event API v0.4.0.
- **Flow**: validate against the contract → `LibraClient.resultHearing` → Azure APIM
  (`cppi-v4`, operation `libra-hearingresulted`, `Ocp-Apim-Subscription-Key`) → Libra `POST /hearing/result`.
  Libra's `HearingResultedResponse` is returned **semantically unchanged**: null fields are left out, unknown
  fields are dropped, and timestamps are normalised. Absent optional blocks are never sent as JSON nulls.
- **Responses**: `200` Libra's body; `400` payload doesn't match the contract (`error: INVALID_PAYLOAD`); `415`
  wrong content type; `502` Libra/APIM rejected (any non-2xx, incl. 3xx), failed or timed out (`error:
  LIBRA_CALL_FAILED`, `details.libraStatus` / `errorCode` / `errorDescription`; `libraStatus` null = no
  response), **or** Libra returned 2xx with an empty or invalid body (`libraStatus` 200, `errorCode`
  `INVALID_RESPONSE`: GOB accepted it). No retries. Unknown request fields are dropped, and the request's
  `uniqueItems` / `additionalProperties: false` aren't enforced (workflow research.md open item 16).
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

### Integration test scenarios

The integration tests need no external services. Listing events are published on `public.event` to an
embedded Artemis broker and consumed by the real listeners (`docker` profile), and `POST /hearingResulted`
is called through MockMvc. One WireMock server stands in for both APIM and the Progression query API.
`JmsListenersIntegrationTest` checks that every listener connects (each durable listener has its own
client id: `HearingAllocationJmsConfig`), that `hearing-listed` isn't consumed by the allocation listener,
and that a malformed message doesn't stop the next one. Most cases are data-driven: **add a folder, not
test code**. Folders run in name order, and an unknown field in a `scenario.json` fails the test.

**`src/test/resources/scenarios/hearing-confirmed/<NN-name>/scenario.json`** (the confirmedHearing flow)

| Field | Meaning |
|---|---|
| `description` | What the scenario proves |
| `cppName` | Optional; default `public.listing.hearing-confirmed` |
| `event` | The message body as Listing publishes it (`confirmedHearing` or `updatedHearing` wrapper) |
| `progression` | Case id → `{"ouCode", "caseUrn"}`, or `{"status": N, "body"?}`. Unlisted ids answer 404 |
| `apim` | Optional `{"status", "delayMs", "body"}` for every callback; default 200 |
| `expected.callbacks` | The exact `ConfirmedHearing` bodies APIM must receive, in order (`[]` = none) |
| `expected.logMustNotContain` | Optional text that must not be logged |

Every callback is also validated against the contract's `ConfirmedHearing` and must carry the
subscription key. Scenarios that expect no callback are safe: the runner waits for a marker event
published after the scenario's, so the scenario's own message is known to have been processed.

**`src/test/resources/scenarios/hearing-resulted/<NN-name>/scenario.json`** (`POST /hearingResulted`)

| Field | Meaning |
|---|---|
| `description` | What the scenario proves |
| `request` | Optional. `set` (JSON pointer → value) and `remove` (JSON pointers) edit `hearingresulted/request.json`; `text` sends a raw body instead; `contentType` defaults to `application/json` |
| `apim` | APIM's reply: `status`, then one of `bodyResource` (a test resource), `body` (JSON) or `bodyText`, and optional `delayMs` (over 1000 ms times out) and `headers`. Omit it when the request must not reach APIM |
| `expected.status`, `expected.apimCalls` | Required: the HTTP status returned and the number of APIM calls |
| `expected.error`, `expected.details` | For non-200: the `error` code, and `details` fields that must be equal (`null` included). Without `error`, the response must have no body (e.g. 415) |
| `expected.response` | For a 200 that is only semantically equal to APIM's body (unknown fields dropped, timestamps normalised): the exact body expected instead |

The runner always checks that APIM receives the request unchanged (array order aside: `nowsDataItems` is a set) with the subscription key, that the
response is valid against the contract (`HearingResultedResponse` for 200, `ErrorResponse` otherwise),
that a 200 is APIM's body unchanged, and that the fixtures' PII and bank details are never logged.

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
