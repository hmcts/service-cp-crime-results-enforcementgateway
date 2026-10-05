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
import org.springframework.web.client.RestClientResponseException;

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
    private final String baseUrl;

    public ProsecutionCaseClient(@Qualifier("restClientBuilder") final RestClient.Builder restClientBuilder,
                                  @Value("${cp.progression.query-api.base-url}") final String baseUrl,
                                  @Value("${cp.progression.query-api.cjscppuid}") final String cjscppuid) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.cjscppuid = cjscppuid;
        this.baseUrl = baseUrl;
    }

    /**
     * Returns empty if the case isn't found or the call fails - callers treat a failed/missing
     * lookup as "can't confirm this is Enforcement", not as a fatal error for the whole event.
     */
    public Optional<ProsecutionCaseDetails> findByCaseId(final UUID caseId) {
        Optional<ProsecutionCaseDetails> result = Optional.empty();
        try {
            final ProsecutionCaseResponse response = restClient.get()
                    .uri("/prosecutioncases/{caseId}", caseId)
                    .accept(PROGRESSION_QUERY_CASE_MEDIA_TYPE)
                    .header(CJSCPPUID_HEADER, cjscppuid)
                    .retrieve()
                    .body(ProsecutionCaseResponse.class);
            result = Optional.ofNullable(response)
                    .map(ProsecutionCaseResponse::prosecutionCase)
                    .map(ProsecutionCase::prosecutionCaseIdentifier)
                    .map(identifier -> new ProsecutionCaseDetails(identifier.prosecutionAuthorityOUCode(), identifier.caseUrn()));
        } catch (final RestClientResponseException e) {
            // status only: the error body can echo case data (constitution IV) - and so can e.getMessage(),
            // which is why the exception itself isn't passed to the logger here
            // TEMP DIAGNOSTICS: url, status text, content type and a body-free stack trace added to help
            // diagnose Progression lookup failures
            log.error("Failed to look up prosecution case {} from Progression: HTTP {} {} (url={}/prosecutioncases/{}, contentType={})",
                    caseId, e.getStatusCode().value(), e.getStatusText(), baseUrl, caseId,
                    e.getResponseHeaders() == null ? null : e.getResponseHeaders().getContentType(),
                    withoutResponseBody(e));
        } catch (final RestClientException e) {
            // TEMP DIAGNOSTICS: no HTTP response was received or it couldn't be read (connection refused, timeout,
            // DNS/TLS failure, unexpected content type, JSON mapping error...) - log the url, message and root
            // cause plus the stack trace so the actual failure is visible
            final Throwable rootCause = rootCauseOf(e);
            log.error("Failed to look up prosecution case {} from Progression: {} - {} (url={}/prosecutioncases/{}, rootCause={}: {})",
                    caseId, e.getClass().getSimpleName(), e.getMessage(), baseUrl, caseId,
                    rootCause.getClass().getName(), rootCause.getMessage(), e);
        }
        return result;
    }

    // TEMP DIAGNOSTICS: same stack trace as the original, but a message carrying only the status - the original
    // message (and so its "Caused by" chain) embeds the response body
    private static Throwable withoutResponseBody(final RestClientResponseException e) {
        final RuntimeException safeCopy = new RuntimeException(
                e.getClass().getName() + ": HTTP " + e.getStatusCode().value() + " " + e.getStatusText());
        safeCopy.setStackTrace(e.getStackTrace());
        return safeCopy;
    }

    private static Throwable rootCauseOf(final Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    // Progression's progression.query.case response wraps the case under a "prosecutionCase" key
    // (see ProsecutionCaseQuery.getCase / PROSECUTION_CASE constant in cpp-context-progression) -
    // the identifier itself is nested one level further inside that.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProsecutionCaseResponse(ProsecutionCase prosecutionCase) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProsecutionCase(ProsecutionCaseIdentifier prosecutionCaseIdentifier) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProsecutionCaseIdentifier(String prosecutionAuthorityOUCode,
                                              @JsonProperty("caseURN") String caseUrn) {
    }
}
