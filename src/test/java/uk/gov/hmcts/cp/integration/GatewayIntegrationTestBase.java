package uk.gov.hmcts.cp.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;

/**
 * Both gateway flows end to end (workflow research.md R25): CP public events published on
 * {@code public.event} to an embedded, non-persistent Artemis broker and consumed by the real
 * listeners (the {@code docker} profile), and {@code POST /hearingResulted} through {@link MockMvc}.
 * One WireMock server stands in for both Azure APIM and the Progression query API.
 *
 * <p>Subclasses must not add their own properties or profiles: every class extending this one has to
 * share one Spring context, because a second cached context would start a second in-VM broker with
 * the same server id. The timeouts are shortened here, for the timeout scenarios.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("docker")
@TestPropertySource(properties = {
    "spring.artemis.mode=embedded",
    "spring.artemis.embedded.persistent=false",
    "spring.artemis.embedded.topics=public.event",
    "spring.jms.listener.auto-startup=true",
    "cp.libra.read-timeout-ms=1000",
    "cp.http-client.read-timeout-ms=1000"
})
public abstract class GatewayIntegrationTestBase {

    /**
     * HTTP/1.1 only: the JDK client upgrades plain http to HTTP/2 (h2c), and WireMock then resets the
     * stream of a second POST on the same connection ("RST_STREAM: Stream cancelled"). Real APIM
     * negotiates HTTP/2 over TLS, so this is a stub limitation, not gateway behaviour.
     */
    protected static final WireMockServer STUBS = new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));
    protected static final String SUBSCRIPTION_KEY = "it-subscription-key";
    protected static final String PROGRESSION_PATH = "/progression-query-api/query/api/rest/progression";
    protected static final String CONFIRMED_HEARING_PATH = "/confirmedHearing";
    protected static final String HEARING_RESULTED_PATH = "/hearingResulted";
    protected static final String HEARING_CONFIRMED = "public.listing.hearing-confirmed";
    protected static final String HEARING_UPDATED = "public.listing.hearing-updated";
    protected static final String ENFORCEMENT_OU_CODE = "GAPGD00";
    protected static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(15);
    private static final String MARKER_URN_PREFIX = "MARKER-";

    static {
        STUBS.start();
        Runtime.getRuntime().addShutdownHook(new Thread(STUBS::stop));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JmsTemplate jmsTemplate;

    @Value("${cp.messaging.public-event-topic}")
    private String publicEventTopic;

    @DynamicPropertySource
    static void stubProperties(final DynamicPropertyRegistry registry) {
        registry.add("cp.libra.apim-base-url", STUBS::baseUrl);
        registry.add("cp.libra.apim-subscription-key", () -> SUBSCRIPTION_KEY);
        registry.add("cp.progression.query-api.base-url", () -> STUBS.baseUrl() + PROGRESSION_PATH);
    }

    @AfterEach
    void resetStubs() {
        STUBS.resetAll();
    }

    /** Publishes a CP public event: a text message with the event name in {@code CPPNAME}. */
    protected void publish(final String cppName, final String body) {
        jmsTemplate.send(publicEventTopic, session -> {
            final TextMessage message = session.createTextMessage(body);
            message.setStringProperty("CPPNAME", cppName);
            return message;
        });
    }

    /** Progression knows the case: 200 with its prosecuting-authority OU code and case URN. */
    protected static void stubProgressionCase(final String caseId, final String ouCode, final String caseUrn) {
        STUBS.stubFor(get(urlEqualTo(PROGRESSION_PATH + "/prosecutioncases/" + caseId)).atPriority(1)
                .withHeader("Accept", equalTo("application/vnd.progression.query.case+json"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/vnd.progression.query.case+json")
                        .withBody("{\"prosecutionCase\":{\"prosecutionCaseIdentifier\":{\"prosecutionAuthorityOUCode\":\""
                                + ouCode + "\",\"caseURN\":\"" + caseUrn + "\"}}}")));
    }

    /**
     * Waits until every message published so far has been fully processed. The gateway stores nothing,
     * so it publishes a marker hearing-confirmed event (an enforcement case of its own) and waits for the
     * marker's APIM callback. The allocation listener has a single consumer, so earlier messages are done
     * by then. This also proves that a message expected to cause no callback was consumed, not still in flight.
     */
    protected void awaitProcessed() {
        final String caseId = UUID.randomUUID().toString();
        final String markerUrn = MARKER_URN_PREFIX + caseId.substring(0, 8);
        stubProgressionCase(caseId, ENFORCEMENT_OU_CODE, markerUrn);
        publish(HEARING_CONFIRMED, "{\"confirmedHearing\":{\"courtCentre\":{\"code\":\"B01LY00\"},"
                + "\"hearingDays\":[{\"sittingDay\":\"2026-07-15T10:00:00.000Z\"}],\"prosecutionCases\":[{\"id\":\"" + caseId + "\"}]}}");
        await().atMost(PROCESSING_TIMEOUT).until(() -> !STUBS.findAll(postRequestedFor(urlEqualTo(CONFIRMED_HEARING_PATH))
                .withRequestBody(containing(markerUrn))).isEmpty());
    }

    /** The confirmedHearing callbacks APIM received, without the markers. */
    protected static List<LoggedRequest> confirmedHearingCallbacks() {
        return STUBS.findAll(postRequestedFor(urlEqualTo(CONFIRMED_HEARING_PATH))).stream()
                .filter(request -> !request.getBodyAsString().contains(MARKER_URN_PREFIX))
                .toList();
    }
}
