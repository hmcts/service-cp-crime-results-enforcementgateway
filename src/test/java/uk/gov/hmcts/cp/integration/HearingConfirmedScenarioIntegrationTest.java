package uk.gov.hmcts.cp.integration;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.support.OwnContract;
import uk.gov.hmcts.cp.support.Scenarios;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every folder under {@code src/test/resources/scenarios/hearing-confirmed/} through the
 * event-driven confirmedHearing flow: the event is published on {@code public.event}, Progression and
 * APIM answer as the scenario says, and the callbacks APIM receives must be exactly the expected ones,
 * each valid against the gateway contract's {@code ConfirmedHearing} and carrying the subscription key.
 * New behaviour is covered by adding a folder; the format is in the README ("Integration test scenarios").
 */
@ExtendWith(OutputCaptureExtension.class)
class HearingConfirmedScenarioIntegrationTest extends GatewayIntegrationTestBase {

    static Stream<Named<Path>> scenarios() {
        return Scenarios.folders("scenarios/hearing-confirmed");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void scenario(final Path folder, final CapturedOutput output) {
        final Scenario scenario = Scenarios.read(folder.resolve("scenario.json"), Scenario.class);
        scenario.progression().forEach(HearingConfirmedScenarioIntegrationTest::stubProgression);
        scenario.referenceData().forEach(HearingConfirmedScenarioIntegrationTest::stubCourtroomOuCode);
        STUBS.stubFor(post(urlEqualTo(CONFIRMED_HEARING_PATH)).atPriority(5)
                .willReturn(withOptionalBody(aResponse().withStatus(scenario.apim().status()).withFixedDelay(scenario.apim().delayMs()),
                        scenario.apim().body())));

        publish(scenario.cppName(), scenario.event().toString());
        awaitProcessed();

        final List<LoggedRequest> callbacks = confirmedHearingCallbacks();
        assertThat(callbacks).as("confirmedHearing callbacks").hasSize(scenario.expected().callbacks().size());
        for (int i = 0; i < callbacks.size(); i++) {
            final String body = callbacks.get(i).getBodyAsString();
            final String expected = scenario.expected().callbacks().get(i).toString();
            assertThat(equalToJson(expected).match(body).isExactMatch()).as("callback %d: %s", i, body).isTrue();
            assertThat(OwnContract.violations("ConfirmedHearing", body)).as("ConfirmedHearing contract violations").isEmpty();
            assertThat(callbacks.get(i).getHeader("Ocp-Apim-Subscription-Key")).isEqualTo(SUBSCRIPTION_KEY);
        }
        scenario.expected().logMustNotContain().forEach(text -> assertThat(output.getAll()).as("log").doesNotContain(text));
    }

    /** {@code {"ouCode": ..., "caseUrn": ...}} answers 200; {@code {"status": N}} answers with that status. */
    private static void stubProgression(final String caseId, final JsonNode answer) {
        if (answer.has("status")) {
            STUBS.stubFor(get(urlEqualTo(PROGRESSION_PATH + "/prosecutioncases/" + caseId)).atPriority(1)
                    .withHeader("Accept", equalTo("application/vnd.progression.query.case+json"))
                    .willReturn(withOptionalBody(aResponse().withStatus(answer.path("status").asInt()),
                            answer.has("body") ? answer.path("body").asString() : null)));
        } else {
            stubProgressionCase(caseId, answer.path("ouCode").asString(), answer.path("caseUrn").asString());
        }
    }

    /** {@code {"status": N}} answers with that status; anything else is the 200 body, e.g. {@code {"ouCourtRoomCodes": [...]}}. */
    private static void stubCourtroomOuCode(final String roomId, final JsonNode answer) {
        final String mediaType = "application/vnd.referencedata.query.get.police-opt-courtroom-ou-courtroom-code+json";
        STUBS.stubFor(get(urlEqualTo(REFERENCEDATA_PATH + "/police-opt-courtroom-mappings?courtRoomUuid=" + roomId)).atPriority(1)
                .withHeader("Accept", equalTo(mediaType))
                .willReturn(answer.has("status") ? aResponse().withStatus(answer.path("status").asInt())
                        : aResponse().withStatus(200).withHeader("Content-Type", mediaType).withBody(answer.toString())));
    }

    private static ResponseDefinitionBuilder withOptionalBody(final ResponseDefinitionBuilder response, final String body) {
        return body == null ? response : response.withHeader("Content-Type", "application/json").withBody(body);
    }

    /**
     * One scenario.json.
     *
     * @param cppName     the {@code CPPNAME}; defaults to {@code public.listing.hearing-confirmed}
     * @param event       the message body, as Listing publishes it
     * @param progression case id → {@code {"ouCode", "caseUrn"}} or {@code {"status": N, "body"?}}; unlisted ids answer 404
     * @param referenceData courtroom id → the {@code ouCourtRoomCodes} response or {@code {"status": N}}; unlisted ids answer 404
     * @param apim        APIM's reply to every callback ({@code status}, optional {@code delayMs} and {@code body}); default 200
     */
    private record Scenario(String description, String cppName, JsonNode event, Map<String, JsonNode> progression,
                            Map<String, JsonNode> referenceData, ApimReply apim, Expected expected) {
        Scenario {
            cppName = cppName == null ? HEARING_CONFIRMED : cppName;
            progression = progression == null ? Map.of() : progression;
            referenceData = referenceData == null ? Map.of() : referenceData;
            apim = apim == null ? new ApimReply(200, 0, null) : apim;
        }
    }

    private record ApimReply(int status, Integer delayMs, String body) {
        ApimReply {
            delayMs = delayMs == null ? 0 : delayMs;
        }
    }

    /** The exact callbacks APIM must receive, in order (an empty list means none). */
    private record Expected(List<JsonNode> callbacks, List<String> logMustNotContain) {
        Expected {
            callbacks = callbacks == null ? List.of() : callbacks;
            logMustNotContain = logMustNotContain == null ? List.of() : logMustNotContain;
        }
    }
}
