# Azure APIM mock policy — simulating the Libra `confirmedHearing` response

Test-only mock for the Libra/APIM integration (`LibraClient`/`cp.libra.apim-base-url`), used while the
real Libra/APIM policy doesn't exist yet. **Test environment only — do not apply to a prod APIM
instance.** Mirrors the same idea as `cpp-context-staging-dvla`'s own mock policy
(`stagingdvla-azure-functions/.../DVLA Driver Enquiry/MockFindAPIMPolicy.txt`), which stands in for
a real backend the same way.

## Where this goes

For `confirmedHearing`, `LibraClient.confirmHearing` only checks the HTTP status code
(`.retrieve().toBodilessEntity()`), so its mock only needs to control the status. The hearing-result call
(`LibraClient.resultHearing`) **does** read the body: see the `libra-hearingresulted` section at the end.

**Important**: don't add this as a new operation inside the existing `CppGatewayService` API in
APIM — that API is labelled "SOAP" because it was imported from `CPPSoapGateway.wsdl` as a SOAP
pass-through API (its operations are auto-generated from the WSDL's `<portType>`, e.g.
`searchType`/`createFineAccounts` — see `GobClient` in `cpp-context-staging-enforcement`). It's the
wrong container for a hand-written JSON REST operation.

**Update, resolved**: this is done — the operation was added as `libra-confirmedhearing`
(`POST /confirmedHearing`) under the existing shared `cppi-v4` APIM API (base
`https://spnl-apim-int-gw.cpp.nonlive/cppi/v4` in STE, one host per environment - see
`cp.libra.apim-base-url`'s comment in `application.yaml`), not as a standalone new API as originally
suggested below. Paste the policy variant below into that operation's **inbound** processing
(Azure Portal → APIs → `cppi-v4` → the `libra-confirmedhearing` operation → Design tab → the `</>`
code-editor icon on the Inbound processing box).

## Which variant to use

- **Variant A (header-driven)** — for direct curl/Postman testing against the API, where you
  control the request headers yourself.
- **Variant B (content-driven)** — for QA testing through the real UI. The real backend
  (`LibraClient`) builds the request itself; QA has no way to add a custom header from the UI.
  Variant B branches on a field already inside the `confirmedHearing` payload instead, so QA can
  choose the simulated outcome purely by which test data they use - no header, no manual policy
  edits between test runs.

## Variant A: header-driven (direct API testing)

Controlled by an optional request header, `X-Mock-Response-Code`:
- absent, or `202` → 202 Accepted (default — matches "expect 200/202")
- `200` → 200 OK
- any 4xx value (e.g. `400`, `422`) → that status, with a plausible JSON error body

```xml
<policies>
    <inbound>
        <base />
        <!--
            Test-only mock, standing in for the real Libra/APIM policy until it exists.
            Controlled by an optional request header, X-Mock-Response-Code:
              - absent, or "202"  -> 202 Accepted (default - matches "expect 200/202")
              - "200"             -> 200 OK
              - any 4xx value (e.g. "400", "422")  -> that status, with a plausible error body
            Remove this whole policy once the real APIM->Libra integration exists.

            Gotcha (this is what Azure APIM rejects the policy for): any attribute whose
            expression embeds a C# double-quoted string literal - e.g.
            GetValueOrDefault("X-Mock-Response-Code", "202") - must use single quotes as the XML
            attribute delimiter (condition='@(...)'), not double quotes. A "-delimited attribute
            cannot itself contain a literal " - that's invalid XML, not an @()/@{} syntax error,
            even though APIM's validator reports it as one.
        -->
        <choose>
            <when condition='@(context.Request.Headers.GetValueOrDefault("X-Mock-Response-Code", "202") == "202")'>
                <return-response>
                    <set-status code="202" reason="Accepted" />
                </return-response>
            </when>
            <when condition='@(context.Request.Headers.GetValueOrDefault("X-Mock-Response-Code", "202") == "200")'>
                <return-response>
                    <set-status code="200" reason="OK" />
                </return-response>
            </when>
            <otherwise>
                <return-response>
                    <set-status code='@(int.Parse(context.Request.Headers.GetValueOrDefault("X-Mock-Response-Code", "400")))' reason="Simulated error" />
                    <set-header name="Content-Type" exists-action="override">
                        <value>application/json</value>
                    </set-header>
                    <set-body>@{
                        return new JObject(
                            new JProperty("error", "Simulated failure via X-Mock-Response-Code"),
                            new JProperty("status", context.Request.Headers.GetValueOrDefault("X-Mock-Response-Code", "400"))
                        ).ToString();
                    }</set-body>
                </return-response>
            </otherwise>
        </choose>
    </inbound>
    <backend>
        <base />
    </backend>
    <outbound>
        <base />
    </outbound>
    <on-error>
        <base />
    </on-error>
</policies>
```

### Driving Variant A

```bash
# Success - 202 (default, no header needed)
curl -i -X POST https://<your-test-apim-host>/confirmedHearing \
  -H "Content-Type: application/json" \
  -H "Ocp-Apim-Subscription-Key: <key>" \
  -d '{"caseUrn":"12GD3456789","courtHearingLocation":"B01LY","dateOfHearing":"2026-08-13","timeOfHearing":"10:00"}'

# Success - 200
curl -i -X POST https://<your-test-apim-host>/confirmedHearing \
  -H "X-Mock-Response-Code: 200" \
  -H "Content-Type: application/json" \
  -H "Ocp-Apim-Subscription-Key: <key>" \
  -d '{"caseUrn":"12GD3456789","courtHearingLocation":"B01LY","dateOfHearing":"2026-08-13","timeOfHearing":"10:00"}'

# Simulated error - any 4xx, exercises LibraClient.confirmHearing()'s failure path
curl -i -X POST https://<your-test-apim-host>/confirmedHearing \
  -H "X-Mock-Response-Code: 422" \
  -H "Content-Type: application/json" \
  -H "Ocp-Apim-Subscription-Key: <key>" \
  -d '{"caseUrn":"12GD3456789","courtHearingLocation":"B01LY","dateOfHearing":"2026-08-13","timeOfHearing":"10:00"}'
```

Notes:
- `int.Parse(...)` in the `otherwise` branch requires the header value to be a bare integer
  (`400`, not `4xx`) — anything non-numeric will throw inside the policy.

## Variant B: content-driven (QA testing through the UI)

Branches on `courtHearingLocation` (the court centre/OU code in the `confirmedHearing` payload)
instead of a header. QA chooses the simulated outcome by which court centre they allocate their
test hearing to through the UI — no header, no manual policy edit between scenarios:

- `courtHearingLocation` is a normal, real OU code → 202 Accepted (the default/happy path)
- `courtHearingLocation` == `TEST400` → simulated 400
- `courtHearingLocation` == `TEST422` → simulated 422
- (add more `<when>` blocks the same way for other codes as needed)

**Confirmed (2026-09-08): QA can freely select the court hearing location through the UI** — so
`courtHearingLocation` is the right field to branch on, and this is the recommended variant going
forward. In practice, treat the `TEST400`/`TEST422` mapping as adjustable during testing - update
the `<when>` values in the policy (Azure Portal → APIM → `cppi-v4` → `libra-confirmedhearing` →
Inbound processing) to whichever OU codes QA is actually using in a given test pass, rather than
assuming these two literal codes are permanently reserved.

```xml
<policies>
    <inbound>
        <base />
        <!--
            Test-only mock, standing in for the real Libra/APIM policy until it exists.
            Branches on courtHearingLocation (not a header - the real UI-driven flow can't set
            one) so QA can choose the simulated outcome purely through which court centre they
            allocate their test hearing to. Maintain the OU-code -> response mapping in a shared
            QA test-data note, not just this comment - it'll drift if it's not written down
            somewhere QA can see. Remove this whole policy once the real APIM->Libra integration
            exists.

            Gotcha (this is what Azure APIM rejects the policy for): As<JObject> contains a
            literal "<"/">", and XML attribute values can never contain an unescaped "<" -
            written as &lt;JObject&gt; below. Likewise, any attribute whose expression embeds a
            C# double-quoted string literal (context.Variables["requestBody"], == "TEST400",
            etc.) uses single quotes as the XML attribute delimiter instead of double quotes -
            same reasoning as the Variant A note above. APIM reports both of these as a generic
            "@()/@{} format" error, but the real problem is the XML itself, not the C# expression.
        -->
        <set-variable name="requestBody" value="@(context.Request.Body.As&lt;JObject&gt;(preserveContent: true))" />
        <set-variable name="ouCode" value='@(((JObject)context.Variables["requestBody"])["courtHearingLocation"]?.ToString() ?? "")' />
        <choose>
            <when condition='@(((string)context.Variables["ouCode"]) == "TEST400")'>
                <return-response>
                    <set-status code="400" reason="Simulated error" />
                    <set-header name="Content-Type" exists-action="override">
                        <value>application/json</value>
                    </set-header>
                    <set-body>{"error": "Simulated 400 - courtHearingLocation matched test trigger TEST400"}</set-body>
                </return-response>
            </when>
            <when condition='@(((string)context.Variables["ouCode"]) == "TEST422")'>
                <return-response>
                    <set-status code="422" reason="Simulated error" />
                    <set-header name="Content-Type" exists-action="override">
                        <value>application/json</value>
                    </set-header>
                    <set-body>{"error": "Simulated 422 - courtHearingLocation matched test trigger TEST422"}</set-body>
                </return-response>
            </when>
            <otherwise>
                <return-response>
                    <set-status code="202" reason="Accepted" />
                </return-response>
            </otherwise>
        </choose>
    </inbound>
    <backend>
        <base />
    </backend>
    <outbound>
        <base />
    </outbound>
    <on-error>
        <base />
    </on-error>
</policies>
```

## Fallback: single global switch (if neither field is QA-controllable)

If it turns out QA can't freely choose either `courtHearingLocation` or `caseUrn`, the next-best
option (still better than manually editing/re-saving this whole policy each time) is an APIM
**Named Value** the policy reads instead of a hardcoded status - e.g. a Named Value
`libra-mock-status-code` (default `202`), referenced in the policy as `{{libra-mock-status-code}}`
inside `<set-status code="{{libra-mock-status-code}}" ...>`. Changing the simulated outcome then
means editing one value in the APIM portal's "Named values" blade - no XML, no redeploying the
operation - at the cost of only one mode being active for every call at a time (can't run a success
scenario and a failure scenario in parallel this way).

## Notes

- Remove this whole policy (or delete the temporary API/operation) once the real Libra/APIM
  integration exists, so it doesn't get mistaken for real behaviour later.
- Point `cp.libra.apim-base-url` (in `service-cp-crime-results-enforcementgateway`'s config) at this
  test APIM API's URL to exercise `LibraClient` end-to-end against it.

---

# `libra-hearingresulted`: request to the APIM/platform team, and mock policy (CIMD-4246)

The hearing-result call **needs a response body**. Unlike `confirmedHearing`, the gateway's
`LibraClient.resultHearing` reads the `HearingResultedResponse` (the NOWS data items) and returns it to
`service-cp-crime-results-enforcementworkflow`. Contract detail:
`service-cp-crime-results-enforcementworkflow/specs/001-cimd-4246-hearing-resulted-to-libra/contracts/apim-libra-hearingresulted.md`.

## Request to the APIM/platform team

| Item | Value |
|---|---|
| API | existing `cppi-v4`, the same product/subscription as `libra-confirmedhearing` |
| Operation | `libra-hearingresulted`: `POST /hearingResulted` |
| Backend | Libra `POST /hearing/result` (Libra Gateway Hearing Event API v0.4.0, `resultHearing`) |
| Inbound auth to APIM | `Ocp-Apim-Subscription-Key` (as `libra-confirmedhearing`) |
| Onward auth | OAuth2 client credentials to Libra (`/auth/token`), handled in the policy (as `libra-confirmedhearing`) |
| Response | **pass the Libra status and JSON body through unchanged** (200 `HearingResultedResponse`; 4xx/5xx `{errorCode, errorDescription}`) |
| Timeout | **`<forward-request timeout="35" />`**. It must stay below the gateway's `cp.libra.read-timeout-ms` (40s), so APIM returns a clean 504 instead of the caller timing out first (timeout budget R20). |

## Mock policy: `libra-hearingresulted` (test environments only)

Header-driven, like Variant A above. `X-Mock-Response-Code` absent or `200` → 200 with a sample
`HearingResultedResponse` body. Any 4xx/5xx value → that status with a Libra-style error body.

```xml
<policies>
    <inbound>
        <base />
        <!-- Test-only mock for libra-hearingresulted. Remove once the real APIM->Libra integration exists. -->
        <choose>
            <when condition='@(context.Request.Headers.GetValueOrDefault("X-Mock-Response-Code", "200") == "200")'>
                <return-response>
                    <set-status code="200" reason="OK" />
                    <set-header name="Content-Type" exists-action="override">
                        <value>application/json</value>
                    </set-header>
                    <set-body>@{
                        var caseUrn = (string)context.Request.Body.As<Newtonsoft.Json.Linq.JObject>(preserveContent: true)["caseUrn"];
                        return new Newtonsoft.Json.Linq.JObject(
                            new Newtonsoft.Json.Linq.JProperty("caseUrn", caseUrn),
                            new Newtonsoft.Json.Linq.JProperty("timestamp", DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")),
                            new Newtonsoft.Json.Linq.JProperty("correlationId", Guid.NewGuid().ToString()),
                            new Newtonsoft.Json.Linq.JProperty("nowsDataItems", new Newtonsoft.Json.Linq.JObject(
                                new Newtonsoft.Json.Linq.JProperty("accountBalance", 125.5),
                                new Newtonsoft.Json.Linq.JProperty("accountNumber", "1234567890")))
                        ).ToString();
                    }</set-body>
                </return-response>
            </when>
            <otherwise>
                <return-response>
                    <set-status code='@(int.Parse(context.Request.Headers.GetValueOrDefault("X-Mock-Response-Code", "400")))' reason="Simulated error" />
                    <set-header name="Content-Type" exists-action="override">
                        <value>application/json</value>
                    </set-header>
                    <set-body>{"errorCode":"MOCK_ERROR","errorDescription":"Simulated Libra error from APIM mock policy"}</set-body>
                </return-response>
            </otherwise>
        </choose>
    </inbound>
    <backend>
        <base />
    </backend>
    <outbound>
        <base />
    </outbound>
    <on-error>
        <base />
    </on-error>
</policies>
```

Note the sample body follows the **schema** (`accountBalance` is a number, `accountNumber` is a string),
not Libra's v0.4.0 example, which wraps both in objects and is invalid against its own schema.
