package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;

import java.util.Collection;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The event-driven confirmedHearing flow end to end (workflow research.md R25): a Listing public event
 * on {@code public.event} → the durable, selector-filtered allocation subscription → the Progression
 * lookup (WireMock) → the APIM callback (WireMock).
 */
@ExtendWith(OutputCaptureExtension.class)
class JmsListenersIntegrationTest extends GatewayIntegrationTestBase {

    private static final String CASE_ID = "5b1f6c1e-1111-4a2b-9c3d-000000000001";
    private static final String CONFIRMED_EVENT = """
            {"confirmedHearing":{"courtCentre":{"code":"B01LY00"},"hearingDays":[{"sittingDay":"2026-07-15T09:30:00.000Z"}],\
            "prosecutionCases":[{"id":"%s"}]},"sendNotificationToParties":true}""".formatted(CASE_ID);
    private static final String EXPECTED_CALLBACK = """
            {"caseUrn":"12GD3456789","courtHearingLocation":"B01LY00","dateOfHearing":"2026-07-15","timeOfHearing":"10:30"}""";

    @Autowired
    private JmsListenerEndpointRegistry listenerRegistry;

    @Test
    void everyListenerShouldBeConnectedToTheBroker() {
        final Collection<MessageListenerContainer> containers = listenerRegistry.getListenerContainers();

        // the connectivity logger and the hearing-allocation listener: both durable, so each needs its own client id
        assertThat(containers).hasSize(2);
        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() -> assertThat(containers).allSatisfy(container ->
                assertThat(((DefaultMessageListenerContainer) container).isRegisteredWithDestination()).isTrue()));
    }

    @Test
    void hearingConfirmedForAnEnforcementCaseShouldSendOneCallback() {
        stubProgressionCase(CASE_ID, ENFORCEMENT_OU_CODE, "12GD3456789");
        STUBS.stubFor(post(urlEqualTo(CONFIRMED_HEARING_PATH)).withHeader("Ocp-Apim-Subscription-Key", equalTo(SUBSCRIPTION_KEY))
                .willReturn(aResponse().withStatus(200)));

        publish(HEARING_CONFIRMED, CONFIRMED_EVENT);
        awaitProcessed();

        assertThat(confirmedHearingCallbacks()).singleElement().satisfies(callback ->
                assertThat(equalToJson(EXPECTED_CALLBACK).match(callback.getBodyAsString()).isExactMatch()).isTrue());
    }

    @Test
    void hearingListedShouldNotBeConsumedByTheAllocationListener() {
        stubProgressionCase(CASE_ID, ENFORCEMENT_OU_CODE, "12GD3456789");

        publish("public.listing.hearing-listed", CONFIRMED_EVENT);
        awaitProcessed();

        assertThat(confirmedHearingCallbacks()).isEmpty();
    }

    @Test
    void malformedMessageShouldNotStopTheNextEvent(final CapturedOutput output) {
        stubProgressionCase(CASE_ID, ENFORCEMENT_OU_CODE, "12GD3456789");

        publish(HEARING_CONFIRMED, "{\"confirmedHearing\": not-json");
        publish(HEARING_CONFIRMED, CONFIRMED_EVENT);
        awaitProcessed();

        assertThat(confirmedHearingCallbacks()).hasSize(1);
        assertThat(output.getAll()).contains("Failed to process " + HEARING_CONFIRMED + " event");
    }

    // a non-text message is logged and dropped, not rolled back and redelivered (constitution V)
    @Test
    void nonTextMessageShouldBeLoggedOnceAndNotStopTheNextEvent(final CapturedOutput output) {
        stubProgressionCase(CASE_ID, ENFORCEMENT_OU_CODE, "12GD3456789");

        jmsTemplate.send("public.event", session -> {
            final jakarta.jms.BytesMessage message = session.createBytesMessage();
            message.writeBytes(CONFIRMED_EVENT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            message.setStringProperty("CPPNAME", HEARING_CONFIRMED);
            return message;
        });
        publish(HEARING_CONFIRMED, CONFIRMED_EVENT);
        awaitProcessed();

        assertThat(confirmedHearingCallbacks()).hasSize(1);
        assertThat(output.getAll().split("Failed to process " + HEARING_CONFIRMED + " event", -1)).hasSize(2);
        assertThat(output.getAll()).contains("MessageFormatException");
    }
}
