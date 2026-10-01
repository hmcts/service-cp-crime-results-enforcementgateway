package uk.gov.hmcts.cp.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.support.HearingResultedFixtures;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /hearingResulted} traffic is logged at INFO as metadata only — never the request or
 * response body (logging standard "Never log"; PII absence is asserted in
 * {@link HearingResultedGatewayIntegrationTest}). Asserted on the real stdout JSON lines the logback
 * encoder writes, so the {@code arguments} provider wiring is covered too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class HearingResultedTrafficLoggingIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WireMockServer APIM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        APIM.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void apimProperties(final DynamicPropertyRegistry registry) {
        registry.add("cp.libra.apim-base-url", APIM::baseUrl);
        registry.add("cp.libra.apim-subscription-key", () -> "it-subscription-key");
    }

    @AfterEach
    void resetStubs() {
        APIM.resetAll();
    }

    @AfterAll
    static void stopApim() {
        APIM.stop();
    }

    @Test
    void logs_request_and_response_metadata_without_bodies(final CapturedOutput output) throws Exception {
        APIM.stubFor(post(urlEqualTo("/hearingResulted"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(HearingResultedFixtures.responseJson())));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/hearingResulted")
                        .contentType(MediaType.APPLICATION_JSON).content(HearingResultedFixtures.requestJson()))
                .andExpect(status().isOk());

        final JsonNode request = onlyLine(output, "Hearing resulted request received");
        assertThat(request.path("level").asString()).isEqualTo("INFO");
        assertThat(request.path("httpMethod").asString()).isEqualTo("POST");
        assertThat(request.path("path").asString()).isEqualTo("/hearingResulted");
        assertThat(request.has("requestBody")).isFalse();

        final JsonNode response = onlyLine(output, "Hearing resulted response sent");
        assertThat(response.path("level").asString()).isEqualTo("INFO");
        assertThat(response.path("httpMethod").asString()).isEqualTo("POST");
        assertThat(response.path("path").asString()).isEqualTo("/hearingResulted");
        assertThat(response.path("status").asInt()).isEqualTo(200);
        assertThat(response.path("durationMs").isNumber()).isTrue();
        assertThat(response.has("responseBody")).isFalse();
    }

    /** Rejections happen before the controller runs; the filter must still log them. */
    @Test
    void logs_a_rejected_request_without_its_body(final CapturedOutput output) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/hearingResulted")
                        .contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());

        onlyLine(output, "Hearing resulted request received");
        assertThat(onlyLine(output, "Hearing resulted response sent").path("status").asInt()).isEqualTo(400);
        assertThat(output.getAll()).doesNotContain("not json");
    }

    private static JsonNode onlyLine(final CapturedOutput output, final String message) {
        final List<JsonNode> matches = output.getOut().lines()
                .filter(line -> line.startsWith("{"))
                .map(JSON::readTree)
                .filter(node -> message.equals(node.path("message").asString()))
                .toList();
        assertThat(matches).as("exactly one '%s' log line", message).hasSize(1);
        return matches.get(0);
    }
}
