package uk.gov.hmcts.cp.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A passive deployment: the deployed profile ({@code docker}) with {@code ARTEMIS_LISTENER_ENABLED} not set, so
 * {@code spring.jms.listener.auto-startup} takes its default from {@code application.yaml}, which must be off. A
 * forgotten setting in a higher environment therefore leaves the gateway passive. The gateway runs, but creates no subscription on
 * {@code public.event}, consumes no event and calls nothing (Progression, Reference Data, APIM), even when an
 * Enforcement-shaped hearing-confirmed event is published. It also starts without system user ids.
 *
 * <p>Own context and own embedded broker (a different server id), separate from {@link GatewayIntegrationTestBase}.
 */
@SpringBootTest
@ActiveProfiles("docker")
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "spring.artemis.mode=embedded",
    "spring.artemis.embedded.server-id=7",
    "spring.artemis.embedded.persistent=false",
    "spring.artemis.embedded.topics=public.event"
})
class PassiveDeploymentIntegrationTest {

    private static final WireMockServer STUBS = new WireMockServer(wireMockConfig().dynamicPort());
    private static final String PUBLIC_EVENT = "public.event";
    // ids generated per run, so none is held in source
    private static final String CONFIRMED_EVENT = """
            {"confirmedHearing":{"courtCentre":{"code":"B01LY00","roomId":"%s"},\
            "hearingDays":[{"sittingDay":"2026-07-15T09:30:00.000Z"}],\
            "prosecutionCases":[{"id":"%s"}]}}""".formatted(UUID.randomUUID(), UUID.randomUUID());

    static {
        STUBS.start();
    }

    @Autowired
    private JmsListenerEndpointRegistry listenerRegistry;

    @Autowired
    private EmbeddedActiveMQ broker;

    @Autowired
    private JmsTemplate jmsTemplate;

    @DynamicPropertySource
    static void stubProperties(final DynamicPropertyRegistry registry) {
        registry.add("cp.libra.apim-base-url", STUBS::baseUrl);
        registry.add("cp.progression.query-api.base-url", STUBS::baseUrl);
        registry.add("cp.referencedata.query-api.base-url", STUBS::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        STUBS.stop();
    }

    @Test
    void theListenerShouldNotRunNorSubscribe(final CapturedOutput output) {
        assertThat(listenerRegistry.getListenerContainers()).isNotEmpty()
                .allSatisfy(container -> assertThat(container.isRunning()).isFalse());
        assertThat(subscriptionsOnPublicEvent()).isEmpty();
        assertThat(output.getAll()).contains("hearingAllocationListener=off");
    }

    @Test
    void aHearingConfirmedEventShouldCauseNoCalls() {
        jmsTemplate.send(PUBLIC_EVENT, session -> {
            final TextMessage message = session.createTextMessage(CONFIRMED_EVENT);
            message.setStringProperty("CPPNAME", "public.listing.hearing-confirmed");
            return message;
        });

        // nothing should happen for a while: no Progression lookup, no Reference Data lookup, no APIM call
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .until(() -> STUBS.getAllServeEvents().isEmpty());
        assertThat(subscriptionsOnPublicEvent()).isEmpty();
    }

    private java.util.List<SimpleString> subscriptionsOnPublicEvent() {
        try {
            return broker.getActiveMQServer().bindingQuery(SimpleString.of(PUBLIC_EVENT)).getQueueNames();
        } catch (Exception e) {
            throw new IllegalStateException("could not query the embedded broker", e);
        }
    }
}
