package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.openapi.model.HearingResultedResponse;
import uk.gov.hmcts.cp.support.HearingResultedFixtures;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LibraClientResultHearingTest {

    private static final String BASE_URL = "http://libra.test";
    private static final String APIM_SUBSCRIPTION_KEY = "test-subscription-key";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void should_post_to_apim_and_return_the_response_body() {
        server.expect(requestTo(BASE_URL + "/hearingResulted"))
                .andExpect(method(POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header("Ocp-Apim-Subscription-Key", APIM_SUBSCRIPTION_KEY))
                .andExpect(content().json(HearingResultedFixtures.requestJson()))
                // absent optional blocks must not be sent as JSON nulls (Libra schemas: additionalProperties false, not nullable)
                .andExpect(content().string(not(containsString("null"))))
                .andRespond(withSuccess(HearingResultedFixtures.responseJson(), MediaType.APPLICATION_JSON));

        final HearingResultedResponse response = client().resultHearing(HearingResultedFixtures.request());

        assertThat(response.getCaseUrn()).isEqualTo("E012345678");
        assertThat(response.getCorrelationId()).isEqualTo("9f3d2e42-8d30-4d16-9dd6-6d4e26889d5c");
        assertThat(response.getNowsDataItems().getAccountBalance()).isEqualByComparingTo(new BigDecimal("125.5"));
        server.verify();
    }

    @Test
    void should_throw_libra_call_exception_with_libra_error_fields() {
        server.expect(requestTo(BASE_URL + "/hearingResulted"))
                .andRespond(withStatus(NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorCode\":\"E404\",\"errorDescription\":\"No GoB enforcement record\"}"));

        assertThatThrownBy(() -> client().resultHearing(HearingResultedFixtures.request()))
                .isInstanceOfSatisfying(LibraCallException.class, e -> {
                    assertThat(e.getLibraStatus()).isEqualTo(404);
                    assertThat(e.getErrorCode()).isEqualTo("E404");
                    assertThat(e.getErrorDescription()).isEqualTo("No GoB enforcement record");
                });
    }

    @Test
    void should_keep_status_when_error_body_is_not_libra_json() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withStatus(INTERNAL_SERVER_ERROR).body("upstream down"));

        assertThatThrownBy(() -> client().resultHearing(HearingResultedFixtures.request()))
                .isInstanceOfSatisfying(LibraCallException.class, e -> {
                    assertThat(e.getLibraStatus()).isEqualTo(500);
                    assertThat(e.getErrorCode()).isNull();
                });
    }

    @Test
    void should_throw_libra_call_exception_for_bad_request() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withStatus(BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"errorCode\":\"E400\",\"errorDescription\":\"Validation error\"}"));

        assertThatThrownBy(() -> client().resultHearing(HearingResultedFixtures.request()))
                .isInstanceOfSatisfying(LibraCallException.class, e -> {
                    assertThat(e.getLibraStatus()).isEqualTo(400);
                    assertThat(e.getErrorCode()).isEqualTo("E400");
                });
    }

    @Test
    void should_throw_libra_call_exception_without_status_on_transport_failure() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> client().resultHearing(HearingResultedFixtures.request()))
                .isInstanceOfSatisfying(LibraCallException.class, e -> assertThat(e.getLibraStatus()).isNull());
    }

    private LibraClient client() {
        return new LibraClient(builder, BASE_URL, APIM_SUBSCRIPTION_KEY);
    }

    @Test
    void accepted_with_empty_body_should_be_invalid_response_not_a_crash() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withSuccess());

        assertThatThrownBy(() -> client().resultHearing(HearingResultedFixtures.request()))
                .isInstanceOfSatisfying(LibraCallException.class, e -> {
                    assertThat(e.getLibraStatus()).isEqualTo(200);
                    assertThat(e.getErrorCode()).isEqualTo(LibraCallException.INVALID_RESPONSE);
                });
    }

    @Test
    void accepted_with_non_json_or_invalid_body_should_be_invalid_response() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withSuccess("<html>ok</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client().resultHearing(HearingResultedFixtures.request()))
                .isInstanceOfSatisfying(LibraCallException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LibraCallException.INVALID_RESPONSE));
    }

    @Test
    void accepted_with_unknown_enum_value_should_be_invalid_response() {
        final String body = HearingResultedFixtures.responseJson().replace("\"accountBalance\": 125.5",
                "\"accountBalance\": 125.5, \"defendant\": {\"parentGuardian\": {\"parentToPayFlag\": \"XX\"}}");
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().resultHearing(HearingResultedFixtures.request()))
                .isInstanceOfSatisfying(LibraCallException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LibraCallException.INVALID_RESPONSE));
    }

    @Test
    void redirect_should_be_a_libra_failure() {
        server.expect(requestTo(BASE_URL + "/hearingResulted")).andRespond(withStatus(org.springframework.http.HttpStatus.FOUND));

        assertThatThrownBy(() -> client().resultHearing(HearingResultedFixtures.request()))
                .isInstanceOfSatisfying(LibraCallException.class, e -> assertThat(e.getLibraStatus()).isEqualTo(302));
    }
}
