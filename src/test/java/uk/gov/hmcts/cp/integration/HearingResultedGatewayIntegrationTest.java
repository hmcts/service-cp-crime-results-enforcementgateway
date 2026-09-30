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
import uk.gov.hmcts.cp.support.HearingResultedFixtures;
import uk.gov.hmcts.cp.support.OwnContract;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller → LibraClient → WireMock (as APIM), end to end (constitution Principle VII). It also asserts
 * that no request PII or response bank details reach the logs (Principle IV, FR-017).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class HearingResultedGatewayIntegrationTest {

    private static final String SUBSCRIPTION_KEY = "it-subscription-key";
    private static final int READ_TIMEOUT_MS = 500;
    private static final WireMockServer APIM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        APIM.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void apimProperties(final DynamicPropertyRegistry registry) {
        registry.add("cp.libra.apim-base-url", APIM::baseUrl);
        registry.add("cp.libra.apim-subscription-key", () -> SUBSCRIPTION_KEY);
        registry.add("cp.libra.read-timeout-ms", () -> READ_TIMEOUT_MS);
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
    void successShouldPassLibraBodyThroughUnchanged(final CapturedOutput output) throws Exception {
        APIM.stubFor(post(urlEqualTo("/hearingResulted"))
                .withHeader("Ocp-Apim-Subscription-Key", equalTo(SUBSCRIPTION_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(HearingResultedFixtures.responseJson())));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/hearingResulted")
                        .contentType(MediaType.APPLICATION_JSON).content(HearingResultedFixtures.requestJson()))
                .andExpect(status().isOk())
                .andExpect(content().json(HearingResultedFixtures.responseJson()))
                .andExpect(result -> assertThat(OwnContract.violations("HearingResultedResponse",
                        result.getResponse().getContentAsString())).isEmpty());

        APIM.verify(postRequestedFor(urlEqualTo("/hearingResulted"))
                .withRequestBody(equalToJson(HearingResultedFixtures.requestJson(), true, false))); // nowsDataItems is a set: order-free, no extra fields
        assertNoPiiLogged(output);
    }

    @Test
    void libra404ShouldBecome502WithDetails(final CapturedOutput output) throws Exception {
        APIM.stubFor(post(urlEqualTo("/hearingResulted"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"errorCode\":\"E404\",\"errorDescription\":\"No GoB enforcement record\"}")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/hearingResulted")
                        .contentType(MediaType.APPLICATION_JSON).content(HearingResultedFixtures.requestJson()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.details.libraStatus").value(404))
                .andExpect(jsonPath("$.details.errorCode").value("E404"))
                .andExpect(result -> assertThat(OwnContract.violations("ErrorResponse",
                        result.getResponse().getContentAsString())).isEmpty());
        assertNoPiiLogged(output);
    }

    @Test
    void apimSlowerThanReadTimeoutShouldBecome502(final CapturedOutput output) throws Exception {
        APIM.stubFor(post(urlEqualTo("/hearingResulted"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(READ_TIMEOUT_MS * 4)
                        .withHeader("Content-Type", "application/json").withBody(HearingResultedFixtures.responseJson())));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/hearingResulted")
                        .contentType(MediaType.APPLICATION_JSON).content(HearingResultedFixtures.requestJson()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("LIBRA_CALL_FAILED"));
        assertNoPiiLogged(output);
    }

    @Test
    void emptyLibraReplyObjectShouldNotGainNullFields() throws Exception {
        APIM.stubFor(post(urlEqualTo("/hearingResulted"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"caseUrn\":\"E012345678\",\"timestamp\":\"2026-05-03T14:30:00Z\",\"nowsDataItems\":{}}")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/hearingResulted")
                        .contentType(MediaType.APPLICATION_JSON).content(HearingResultedFixtures.requestJson()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("null"))));
    }

    private static void assertNoPiiLogged(final CapturedOutput output) {
        assertThat(output.getAll())
                .contains("E012345678") // caseUrn is logged: the correlation key, not PII
                .doesNotContain("Edward", "Harrison", "2002-01-10", "NH195839C", "1 High Street", "N17 6RT",
                        "10246456", "148941");
    }
}
