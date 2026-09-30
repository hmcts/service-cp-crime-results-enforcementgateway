package uk.gov.hmcts.cp.integration;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.client.LibraClient;
import uk.gov.hmcts.cp.support.HearingResultedFixtures;
import uk.gov.hmcts.cp.support.OwnContract;
import uk.gov.hmcts.cp.support.Scenarios;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every folder under {@code src/test/resources/scenarios/hearing-resulted/} through
 * {@code POST /hearingResulted} → APIM (WireMock). The gateway is a thin pass-through (constitution
 * Principle I), so the runner always checks that APIM receives the request unchanged with the
 * subscription key, that the response is valid against the contract ({@code HearingResultedResponse}
 * for 200, {@code ErrorResponse} otherwise), that a 200 is APIM's body unchanged, and that no request
 * PII or bank details reach the logs (FR-017). The format is in the README ("Integration test scenarios").
 */
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // assertions are in the helpers
class HearingResultedScenarioIntegrationTest extends GatewayIntegrationTestBase {

    /** Request PII and response bank details in the default fixtures, which must never be logged. */
    private static final List<String> NEVER_LOGGED = List.of("Edward", "Harrison", "2002-01-10", "NH195839C", "1 High Street",
            "N17 6RT", "10246456", "148941");

    static Stream<Named<Path>> scenarios() {
        return Scenarios.folders("scenarios/hearing-resulted");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void scenario(final Path folder, final CapturedOutput output) throws Exception {
        final Scenario scenario = Scenarios.read(folder.resolve("scenario.json"), Scenario.class);
        final String requestBody = requestBody(scenario.request());
        final String apimBody = scenario.apim() == null ? null : stubApim(scenario.apim());

        final MockHttpServletResponse response = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(HEARING_RESULTED_PATH)
                                .contentType(MediaType.parseMediaType(scenario.request().contentType())).content(requestBody))
                .andReturn().getResponse();

        final Expected expected = scenario.expected();
        assertThat(response.getStatus()).as("HTTP status").isEqualTo(expected.status());
        assertForwarded(expected.apimCalls(), requestBody);
        assertResponse(expected, response.getContentAsString(), apimBody);
        NEVER_LOGGED.forEach(text -> assertThat(output.getAll()).as("log").doesNotContain(text));
    }

    private static void assertForwarded(final int apimCalls, final String requestBody) {
        final List<LoggedRequest> calls = STUBS.findAll(postRequestedFor(urlEqualTo(HEARING_RESULTED_PATH)));
        assertThat(calls).as("APIM calls").hasSize(apimCalls);
        calls.forEach(call -> {
            assertThat(equalToJson(requestBody, true, false).match(call.getBodyAsString()).isExactMatch())
                    .as("APIM receives the request unchanged: %s", call.getBodyAsString()).isTrue();
            assertThat(call.getHeader("Ocp-Apim-Subscription-Key")).isEqualTo(SUBSCRIPTION_KEY);
        });
    }

    private static void assertResponse(final Expected expected, final String body, final String apimBody) {
        if (expected.status() == 200) {
            assertThat(OwnContract.violations("HearingResultedResponse", body)).as("HearingResultedResponse violations").isEmpty();
            // APIM's body unchanged, unless the scenario states the semantically equal response expected instead
            final String expectedBody = expected.response() == null ? apimBody : expected.response().toString();
            assertThat(equalToJson(expectedBody, false, false).match(body).isExactMatch())
                    .as("the response is %s: %s", expected.response() == null ? "APIM's body unchanged" : "expected.response", body).isTrue();
        } else if (expected.error() == null) {
            assertThat(body).as("a response without an ErrorResponse body").isEmpty();
        } else {
            assertThat(OwnContract.violations("ErrorResponse", body)).as("ErrorResponse violations").isEmpty();
            final JsonNode error = LibraClient.JSON.readTree(body);
            assertThat(error.path("error").asString()).as("error").isEqualTo(expected.error());
            expected.details().forEach((name, value) ->
                    assertThat(error.path("details").path(name)).as("details." + name).isEqualTo(value));
        }
    }

    /** Stubs APIM's reply and returns its body. */
    private static String stubApim(final ApimReply reply) {
        final String body = reply.bodyResource() != null ? Scenarios.readString(resource(reply.bodyResource()))
                : reply.body() != null ? reply.body().toString() : reply.bodyText();
        ResponseDefinitionBuilder response = aResponse().withStatus(reply.status()).withFixedDelay(reply.delayMs())
                .withHeader("Content-Type", "application/json");
        if (body != null) {
            response = response.withBody(body);
        }
        for (final Map.Entry<String, String> header : reply.headers().entrySet()) {
            response = response.withHeader(header.getKey(), header.getValue());
        }
        STUBS.stubFor(post(urlEqualTo(HEARING_RESULTED_PATH)).willReturn(response));
        return body;
    }

    /** The default request fixture, sent as is, as {@code text}, or with JSON-pointer edits applied. */
    private static String requestBody(final RequestSpec spec) {
        final String body;
        if (spec.text() != null) {
            body = spec.text();
        } else {
            final ObjectNode request = (ObjectNode) LibraClient.JSON.readTree(HearingResultedFixtures.requestJson());
            spec.remove().forEach(pointer -> edit(request, pointer, null));
            spec.set().forEach((pointer, value) -> edit(request, pointer, value));
            body = request.toString();
        }
        return body;
    }

    private static void edit(final ObjectNode root, final String pointer, final JsonNode value) {
        final JsonPointer path = JsonPointer.compile(pointer);
        final JsonNode parent = root.at(path.head());
        final String last = path.last().getMatchingProperty();
        if (parent instanceof ObjectNode object) {
            if (value == null) {
                object.remove(last);
            } else {
                object.set(last, value);
            }
        } else if (parent instanceof ArrayNode array) {
            final int index = path.last().getMatchingIndex();
            if (value == null) {
                array.remove(index);
            } else {
                array.set(index, value);
            }
        } else {
            throw new IllegalArgumentException("No object or array at " + path.head() + " for " + pointer);
        }
    }

    private static Path resource(final String name) {
        try {
            return Path.of(HearingResultedScenarioIntegrationTest.class.getClassLoader().getResource(name).toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * One scenario.json.
     *
     * @param request what is posted; defaults to {@code hearingresulted/request.json} unchanged
     * @param apim    APIM's reply; omitted when the request must not reach APIM
     */
    private record Scenario(String description, RequestSpec request, ApimReply apim, Expected expected) {
        Scenario {
            request = request == null ? new RequestSpec(null, null, null, null) : request;
        }
    }

    /**
     * {@code set}/{@code remove}: JSON pointers into the default request. {@code text}: the raw body instead.
     * {@code contentType}: defaults to {@code application/json}.
     */
    private record RequestSpec(Map<String, JsonNode> set, List<String> remove, String text, String contentType) {
        RequestSpec {
            set = set == null ? Map.of() : set;
            remove = remove == null ? List.of() : remove;
            contentType = contentType == null ? MediaType.APPLICATION_JSON_VALUE : contentType;
        }
    }

    /** Give one of {@code bodyResource} (a test resource), {@code body} (JSON) or {@code bodyText} (sent as is), or none. */
    private record ApimReply(int status, String bodyResource, JsonNode body, String bodyText, Integer delayMs,
                             Map<String, String> headers) {
        ApimReply {
            delayMs = delayMs == null ? 0 : delayMs;
            headers = headers == null ? Map.of() : headers;
        }
    }

    /**
     * {@code error} and {@code details} (each listed field must equal) apply to non-200 responses; without
     * {@code error}, a non-200 must have no body (e.g. 415). {@code response}: for a 200, the expected body when it
     * is only semantically equal to APIM's (unknown fields dropped, timestamps normalised).
     */
    private record Expected(int status, int apimCalls, String error, Map<String, JsonNode> details, JsonNode response) {
        Expected {
            details = details == null ? Map.of() : details;
        }
    }
}
