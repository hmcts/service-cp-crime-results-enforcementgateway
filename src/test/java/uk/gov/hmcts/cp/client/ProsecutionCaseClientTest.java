package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProsecutionCaseClientTest {

    private static final String BASE_URL = "http://progression.test";
    private static final String CJSCPPUID = "00000000-0000-0000-0000-000000000000";

    @Test
    void shouldReturnProsecutionCaseDetailsWhenFound() {
        final UUID caseId = UUID.randomUUID();
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/prosecutioncases/" + caseId))
                .andExpect(method(GET))
                .andExpect(header("CJSCPPUID", CJSCPPUID))
                .andRespond(withSuccess(
                        // Matches Progression's real progression.query.case response shape - the
                        // case is wrapped under "prosecutionCase" (see ProsecutionCaseQuery.getCase
                        // in cpp-context-progression), not a flat top-level prosecutionCaseIdentifier.
                        "{\"prosecutionCase\":{\"prosecutionCaseIdentifier\":{\"prosecutionAuthorityOUCode\":\"GAPGD00\",\"prosecutionAuthorityId\":\"x\",\"caseURN\":\"12GD3456789\"}}}",
                        MediaType.APPLICATION_JSON));

        final Optional<ProsecutionCaseDetails> result = new ProsecutionCaseClient(builder, BASE_URL, CJSCPPUID).findByCaseId(caseId);

        assertThat(result).contains(new ProsecutionCaseDetails("GAPGD00", "12GD3456789"));
        server.verify();
    }

    @Test
    void shouldReturnEmptyWhenCaseNotFound() {
        final UUID caseId = UUID.randomUUID();
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/prosecutioncases/" + caseId)).andRespond(withStatus(NOT_FOUND));

        final Optional<ProsecutionCaseDetails> result = new ProsecutionCaseClient(builder, BASE_URL, CJSCPPUID).findByCaseId(caseId);

        assertThat(result).isEmpty();
    }
}
