package uk.gov.hmcts.cp.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Looks up a courtroom's OU code (e.g. {@code B01LY01}) from Reference Data's
 * {@code GET /police-opt-courtroom-mappings?courtRoomUuid=} query-api endpoint
 * ({@code referencedata.query.get.police-opt-courtroom-ou-courtroom-code}) - the same lookup
 * Progression's {@code ProgressionService.transformCourtCentre} and Results'
 * {@code BaseStructureConverter.getCourtHearingLocation} use to turn a hearing's
 * {@code courtCentre.roomId} into its {@code courtHearingLocation}.
 */
@Slf4j
@Component
public class ReferenceDataClient {

    private static final MediaType COURTROOM_OU_CODE_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.referencedata.query.get.police-opt-courtroom-ou-courtroom-code+json");
    private static final String CJSCPPUID_HEADER = "CJSCPPUID";

    private final RestClient restClient;
    private final String cjscppuid;
    private final String baseUrl;

    public ReferenceDataClient(@Qualifier("restClientBuilder") final RestClient.Builder restClientBuilder,
                               @Value("${cp.referencedata.query-api.base-url}") final String baseUrl,
                               @Value("${cp.referencedata.query-api.cjscppuid}") final String cjscppuid) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.cjscppuid = cjscppuid;
        this.baseUrl = baseUrl;
    }

    /**
     * Returns the first OU code Reference Data maps the courtroom to, as Progression does; empty if
     * there is no mapping or the call fails - callers fall back to the court centre's own OU code.
     */
    public Optional<String> findCourtroomOuCode(final UUID courtRoomId) {
        Optional<String> result = Optional.empty();
        try {
            final CourtroomOuCodes response = restClient.get()
                    .uri("/police-opt-courtroom-mappings?courtRoomUuid={courtRoomId}", courtRoomId)
                    .accept(COURTROOM_OU_CODE_MEDIA_TYPE)
                    .header(CJSCPPUID_HEADER, cjscppuid)
                    .retrieve()
                    .body(CourtroomOuCodes.class);
            result = Optional.ofNullable(response)
                    .map(CourtroomOuCodes::ouCourtRoomCodes)
                    .flatMap(codes -> codes.stream().filter(code -> code != null && !code.isBlank()).findFirst());
        } catch (final RestClientResponseException e) {
            // status only, as for the Progression lookup: never the error body
            log.error("Failed to look up courtroom {} OU code from Reference Data: HTTP {} (url={}/police-opt-courtroom-mappings)",
                    courtRoomId, e.getStatusCode().value(), baseUrl);
        } catch (final RestClientException e) {
            final Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(e);
            log.error("Failed to look up courtroom {} OU code from Reference Data: {} (url={}/police-opt-courtroom-mappings, rootCause={})",
                    courtRoomId, e.getClass().getSimpleName(), baseUrl, rootCause.getClass().getName());
        }
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CourtroomOuCodes(List<String> ouCourtRoomCodes) {
    }
}
