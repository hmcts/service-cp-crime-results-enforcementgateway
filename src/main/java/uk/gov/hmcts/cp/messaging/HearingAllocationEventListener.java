package uk.gov.hmcts.cp.messaging;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.event.ConfirmedHearingEvent;
import uk.gov.hmcts.cp.service.EnforcementHearingConfirmationService;

/**
 * Consumes {@code public.listing.hearing-confirmed}/{@code hearing-updated} (own subscription,
 * separate from {@link PublicEventLoggingListener}'s connectivity-only one) and triggers the
 * Libra confirmedHearing callback for any Enforcement-typed case on the event. {@code
 * public.listing.hearing-listed} is deliberately not subscribed to here - not related to this
 * flow. Active only under the {@code docker} profile, same as the connectivity listener, so it
 * never tries to reach a real broker outside a deployed environment (including in
 * {@code @SpringBootTest}s).
 */
@Slf4j
@Component
@Profile("docker")
public class HearingAllocationEventListener {

    /** hearing-confirmed wraps the payload under this key. */
    private static final String CONFIRMED_HEARING_KEY = "confirmedHearing";
    /** hearing-updated wraps the (identically-shaped) payload under this different key. */
    private static final String UPDATED_HEARING_KEY = "updatedHearing";
    /**
     * hearing-updated fires for ANY update to an already-allocated hearing (judiciary,
     * public-list-note, video link, etc.), not just allocation changes - this flag is the only
     * signal distinguishing a genuine date/time/location change from an unrelated one. Must gate
     * on it, otherwise every unrelated hearing edit would send a spurious duplicate Libra
     * callback. Not present on hearing-confirmed (always a genuine first allocation).
     */
    private static final String ALLOCATION_FIELD_UPDATED_KEY = "isNotificationAllocationFieldUpdated";

    private final ObjectMapper objectMapper;
    private final EnforcementHearingConfirmationService confirmationService;

    public HearingAllocationEventListener(final ObjectMapper objectMapper,
                                           final EnforcementHearingConfirmationService confirmationService) {
        this.objectMapper = objectMapper;
        this.confirmationService = confirmationService;
    }

    @JmsListener(
            destination = "${cp.messaging.public-event-topic}",
            subscription = "${cp.messaging.hearing-allocation-subscription-name}",
            selector = "${cp.messaging.hearing-allocation-selector}")
    public void onHearingAllocationEvent(final Message message) throws JMSException {
        final String eventName = message.getStringProperty("CPPNAME");
        try {
            final JsonNode root = objectMapper.readTree(message.getBody(String.class));
            if (root.has(CONFIRMED_HEARING_KEY)) {
                processHearingNode(root.get(CONFIRMED_HEARING_KEY));
            } else if (root.has(UPDATED_HEARING_KEY)) {
                if (root.path(ALLOCATION_FIELD_UPDATED_KEY).asBoolean(true)) {
                    processHearingNode(root.get(UPDATED_HEARING_KEY));
                } else {
                    log.debug("Skipping {} event - allocation fields unchanged (jmsMessageId={})", eventName, message.getJMSMessageID());
                }
            } else {
                log.warn("{} event had neither '{}' nor '{}' key (jmsMessageId={})", eventName, CONFIRMED_HEARING_KEY, UPDATED_HEARING_KEY, message.getJMSMessageID());
            }
            // deliberately broad: any failure processing one message must not kill this listener thread or block the JMS session
        } catch (@SuppressWarnings("PMD.AvoidCatchingGenericException") final RuntimeException e) {
            log.error("Failed to process {} event (jmsMessageId={})", eventName, message.getJMSMessageID(), e);
        }
    }

    private void processHearingNode(final JsonNode hearingNode) {
        final ConfirmedHearingEvent event = objectMapper.treeToValue(hearingNode, ConfirmedHearingEvent.class);
        confirmationService.processConfirmedHearing(event);
    }
}
