package uk.gov.hmcts.cp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.client.LibraClient;
import uk.gov.hmcts.cp.openapi.api.EnforcementHearingApi;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;
import uk.gov.hmcts.cp.openapi.model.HearingResultedResponse;

/**
 * Inbound {@code POST /hearingResulted} from service-cp-crime-results-enforcementworkflow. It is a
 * thin pass-through (constitution Principle I): the request is validated against the contract
 * (generated Bean Validation), forwarded to APIM/Libra, and Libra's response is returned unchanged.
 * {@code postConfirmedHearing} is deliberately not exposed: that flow stays event-driven, so the
 * generated default (501) applies. Access is limited to the workflow by network isolation
 * (FR-018, workflow research.md R21).
 */
@RestController
@RequiredArgsConstructor
public class HearingResultedController implements EnforcementHearingApi {

    private final LibraClient libraClient;

    @Override
    public ResponseEntity<HearingResultedResponse> postHearingResulted(final HearingResultedRequest hearingResultedRequest) {
        return ResponseEntity.ok(libraClient.resultHearing(hearingResultedRequest));
    }
}
