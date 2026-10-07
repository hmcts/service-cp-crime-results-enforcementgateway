package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class ReferenceDataClientTest {

    private static final String BASE_URL = "http://referencedata.test";
    private static final String CJSCPPUID = "00000000-0000-0000-0000-000000000000";
    private static final String MEDIA_TYPE = "application/vnd.referencedata.query.get.police-opt-courtroom-ou-courtroom-code+json";

    // real STE response for Lavender Hill's Courtroom 01 (B01LY00)
    @Test
    void shouldReturnTheCourtroomOuCode() {
        final UUID roomId = UUID.fromString("9e4932f7-97b2-3010-b942-ddd2624e4dd8");
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/police-opt-courtroom-mappings?courtRoomUuid=" + roomId))
                .andExpect(method(GET))
                .andExpect(header("Accept", MEDIA_TYPE))
                .andExpect(header("CJSCPPUID", CJSCPPUID))
                .andRespond(withSuccess("{\"ouCourtRoomCodes\":[\"B01LY01\"]}", MediaType.parseMediaType(MEDIA_TYPE)));

        assertThat(client(builder).findCourtroomOuCode(roomId)).contains("B01LY01");
        server.verify();
    }

    // as Progression's transformCourtCentre: the first code wins
    @Test
    void shouldReturnTheFirstCodeWhenTheCourtroomMapsToSeveral() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/police-opt-courtroom-mappings")))
                .andRespond(withSuccess("{\"ouCourtRoomCodes\":[\"B54MW02\",\"B53DJ03\"]}", MediaType.APPLICATION_JSON));

        assertThat(client(builder).findCourtroomOuCode(UUID.randomUUID())).contains("B54MW02");
    }

    @Test
    void shouldReturnEmptyWhenTheCourtroomHasNoCode() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/police-opt-courtroom-mappings")))
                .andRespond(withSuccess("{\"ouCourtRoomCodes\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client(builder).findCourtroomOuCode(UUID.randomUUID())).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenTheResponseHasNoCodes() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/police-opt-courtroom-mappings")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client(builder).findCourtroomOuCode(UUID.randomUUID())).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenTheCourtroomIsUnknown() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/police-opt-courtroom-mappings")))
                .andRespond(withStatus(NOT_FOUND));

        assertThat(client(builder).findCourtroomOuCode(UUID.randomUUID())).isEmpty();
    }

    @Test
    void failedLookupShouldLogTheStatusButNotTheErrorBody(final CapturedOutput output) {
        final UUID roomId = UUID.randomUUID();
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/police-opt-courtroom-mappings")))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR).body("defendant Edward Harrison"));

        assertThat(client(builder).findCourtroomOuCode(roomId)).isEmpty();
        assertThat(output.getAll()).contains(roomId.toString()).contains("HTTP 500").doesNotContain("Edward");
    }

    @Test
    void failedLookupWithoutHttpResponseShouldLogTheRootCause(final CapturedOutput output) {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/police-opt-courtroom-mappings")))
                .andRespond(request -> {
                    throw new java.net.ConnectException("Connection refused");
                });

        assertThat(client(builder).findCourtroomOuCode(UUID.randomUUID())).isEmpty();
        assertThat(output.getAll()).contains("url=" + BASE_URL + "/police-opt-courtroom-mappings")
                .contains("ResourceAccessException").contains("rootCause=java.net.ConnectException");
    }

    private static ReferenceDataClient client(final RestClient.Builder builder) {
        return new ReferenceDataClient(builder, BASE_URL, CJSCPPUID);
    }
}
