package uk.gov.hmcts.cp.client;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.dto.ConfirmedHearing;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;
import uk.gov.hmcts.cp.openapi.model.HearingResultedResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Sends the {@code confirmedHearing} payload to Azure APIM, which forwards the same payload on to
 * Libra - this service never calls Libra directly. Deliberately simple, matching
 * {@code cpp-context-staging-dvla}'s equivalent outbound-to-APIM calls
 * ({@code RestEasyClientService}/{@code DriverService}): a plain POST with an APIM subscription
 * key, nothing else - no OAuth2/mTLS on this leg. APIM's own policy is responsible for
 * authenticating onward to Libra (confirmed OAuth2, per the architect) exactly as DVLA's APIM
 * policy authenticates onward to the real DVLA backend - that policy is owned by the APIM/platform
 * team, not this service.
 */
@Slf4j
@Component
public class LibraClient {

    private static final String OCP_APIM_SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key";

    /**
     * Libra's schemas are {@code additionalProperties: false} and don't allow JSON nulls for absent
     * optional blocks. The generated models don't mark those fields NON_NULL, so requests are
     * serialised with this mapper rather than the default one. Unknown response properties are
     * tolerated, because Libra may add fields before the contract copy is updated.
     */
    public static final JsonMapper JSON = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final RestClient restClient;
    private final String apimSubscriptionKey;

    public LibraClient(@Qualifier("libraRestClientBuilder") final RestClient.Builder restClientBuilder,
                        @Value("${cp.libra.apim-base-url}") final String baseUrl,
                        @Value("${cp.libra.apim-subscription-key}") final String apimSubscriptionKey) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apimSubscriptionKey = apimSubscriptionKey;
    }

    /** Returns true if APIM accepted the callback (200/202) for onward delivery to Libra, false otherwise - never throws. */
    public boolean confirmHearing(final ConfirmedHearing confirmedHearing) {
        boolean accepted = false;
        try {
            restClient.post()
                    .uri("/confirmedHearing")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(OCP_APIM_SUBSCRIPTION_KEY_HEADER, apimSubscriptionKey)
                    .body(confirmedHearing)
                    .retrieve()
                    .toBodilessEntity();
            accepted = true;
        } catch (final RestClientException e) {
            log.error("Libra confirmedHearing callback (via APIM) failed for caseUrn {}", confirmedHearing.caseUrn(), e);
        }
        return accepted;
    }

    /**
     * Forwards the hearing result to APIM ({@code libra-hearingresulted}, onward to Libra
     * {@code POST /hearing/result}) and returns Libra's response body unchanged. Unlike
     * {@link #confirmHearing}, failures are not swallowed: the caller needs Libra's status.
     * Only {@code caseUrn} and the outcome are logged. The payloads carry PII (FR-017).
     *
     * @throws LibraCallException on a non-2xx response ({@code libraStatus} set) or when no response was received
     */
    public HearingResultedResponse resultHearing(final HearingResultedRequest request) {
        final String caseUrn = request.getCaseUrn();
        try {
            final String responseBody = restClient.post()
                    .uri("/hearingResulted")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(OCP_APIM_SUBSCRIPTION_KEY_HEADER, apimSubscriptionKey)
                    .body(JSON.writeValueAsString(request))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw LibraCallException.fromResponse(res.getStatusCode().value(), readBody(res.getBody()));
                    })
                    .body(String.class);
            log.info("Libra hearingResulted (via APIM) accepted for caseUrn {}", caseUrn);
            return JSON.readValue(responseBody, HearingResultedResponse.class);
        } catch (final LibraCallException e) {
            log.warn("Libra hearingResulted (via APIM) rejected for caseUrn {} with HTTP {}", caseUrn, e.getLibraStatus());
            throw e;
        } catch (final ResourceAccessException e) {
            log.warn("Libra hearingResulted (via APIM) got no response for caseUrn {}: {}", caseUrn, e.getClass().getSimpleName());
            throw LibraCallException.noResponse(e);
        }
    }

    private static String readBody(final InputStream body) {
        String text = null;
        try {
            text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not read Libra error body: {}", e.getClass().getSimpleName());
        }
        return text;
    }
}
