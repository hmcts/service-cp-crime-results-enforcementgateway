package uk.gov.hmcts.cp.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import uk.gov.hmcts.cp.dto.ConfirmedHearing;

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
        try {
            restClient.post()
                    .uri("/confirmedHearing")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(OCP_APIM_SUBSCRIPTION_KEY_HEADER, apimSubscriptionKey)
                    .body(confirmedHearing)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (final RestClientException e) {
            log.error("Libra confirmedHearing callback (via APIM) failed for caseUrn {}", confirmedHearing.caseUrn(), e);
            return false;
        }
    }
}
