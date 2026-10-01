package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.dto.ConfirmedHearing;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(OutputCaptureExtension.class)
class LibraClientTest {

    private static final String BASE_URL = "http://libra.test";
    private static final String APIM_SUBSCRIPTION_KEY = "test-subscription-key";
    private static final ConfirmedHearing CONFIRMED_HEARING =
            new ConfirmedHearing("12GD3456789", "B01LY", LocalDate.parse("2026-07-15"), "10:00");

    @Test
    void shouldReturnTrueWhenLibraAccepts() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/confirmedHearing"))
                .andExpect(method(POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header("Ocp-Apim-Subscription-Key", APIM_SUBSCRIPTION_KEY))
                .andRespond(withStatus(ACCEPTED));

        final boolean accepted = new LibraClient(builder, BASE_URL, APIM_SUBSCRIPTION_KEY).confirmHearing(CONFIRMED_HEARING);

        assertThat(accepted).isTrue();
        server.verify();
    }

    @Test
    void shouldReturnFalseWhenLibraFails() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/confirmedHearing")).andRespond(withStatus(INTERNAL_SERVER_ERROR));

        final boolean accepted = new LibraClient(builder, BASE_URL, APIM_SUBSCRIPTION_KEY).confirmHearing(CONFIRMED_HEARING);

        assertThat(accepted).isFalse();
    }

    // only a 2xx is accepted; the JDK client doesn't follow redirects, so a 3xx arrives here
    @Test
    void shouldReturnFalseForRedirect() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/confirmedHearing")).andRespond(withStatus(org.springframework.http.HttpStatus.FOUND));

        assertThat(new LibraClient(builder, BASE_URL, APIM_SUBSCRIPTION_KEY).confirmHearing(CONFIRMED_HEARING)).isFalse();
    }

    // constitution IV: an error body can echo payload values, so it must never be logged
    @Test
    void shouldNotLogTheErrorBody(final CapturedOutput output) {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/confirmedHearing")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"errorDescription\":\"forename Edward is invalid\"}"));

        assertThat(new LibraClient(builder, BASE_URL, APIM_SUBSCRIPTION_KEY).confirmHearing(CONFIRMED_HEARING)).isFalse();
        assertThat(output.getAll()).contains("12GD3456789").contains("HTTP 400").doesNotContain("Edward");
    }

    // QA can see that APIM accepted the callback: the caseUrn only
    @Test
    void shouldLogTheCaseUrnWhenApimAccepts(final CapturedOutput output) {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/confirmedHearing")).andRespond(withStatus(ACCEPTED));

        assertThat(new LibraClient(builder, BASE_URL, APIM_SUBSCRIPTION_KEY).confirmHearing(CONFIRMED_HEARING)).isTrue();
        assertThat(output.getAll()).contains("Libra confirmedHearing callback (via APIM) accepted for caseUrn 12GD3456789 (HTTP 202)")
                .doesNotContain("B01LY", "2026-07-15");
    }
}
