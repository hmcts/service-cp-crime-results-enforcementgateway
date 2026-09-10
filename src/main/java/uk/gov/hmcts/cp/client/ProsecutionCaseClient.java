package uk.gov.hmcts.cp.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * Looks up a case's prosecuting-authority code and case URN from Progression's existing
 * {@code GET /prosecutioncases/{caseId}} query-api endpoint - the case UUID is the same one
 * carried on Listing's {@code hearing-confirmed}/{@code hearing-updated} public event.
 */
@Slf4j
@Component
public class ProsecutionCaseClient {

    private static final MediaType PROGRESSION_QUERY_CASE_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.progression.query.case+json");
    private static final String CJSCPPUID_HEADER = "CJSCPPUID";

    private final RestClient restClient;
    private final String cjscppuid;

    public ProsecutionCaseClient(@Qualifier("restClientBuilder") final RestClient.Builder restClientBuilder,
                                  @Value("${cp.progression.query-api.base-url}") final String baseUrl,
                                  @Value("${cp.progression.query-api.cjscppuid}") final String cjscppuid) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.cjscppuid = cjscppuid;
    }

    /**
     * Returns empty if the case isn't found or the call fails - callers treat a failed/missing
     * lookup as "can't confirm this is Enforcement", not as a fatal error for the whole event.
     */
    public Optional<ProsecutionCaseDetails> findByCaseId(final UUID caseId) {
        try {
            final ProsecutionCaseResponse response = restClient.get()
                    .uri("/prosecutioncases/{caseId}", caseId)
                    .accept(PROGRESSION_QUERY_CASE_MEDIA_TYPE)
                    .header(CJSCPPUID_HEADER, cjscppuid)
                    .retrieve()
                    .body(ProsecutionCaseResponse.class);
            return Optional.ofNullable(response)
                    .map(ProsecutionCaseResponse::prosecutionCaseIdentifier)
                    .map(identifier -> new ProsecutionCaseDetails(identifier.prosecutionAuthorityCode(), identifier.caseUrn()));
        } catch (final RestClientException e) {
            log.error("Failed to look up prosecution case {} from Progression", caseId, e);
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProsecutionCaseResponse(ProsecutionCaseIdentifier prosecutionCaseIdentifier) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record ProsecutionCaseIdentifier(String prosecutionAuthorityCode,
                                                  @JsonProperty("caseURN") String caseUrn) {
        }
    }
}
