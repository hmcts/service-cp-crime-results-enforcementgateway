package uk.gov.hmcts.cp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.client.LibraClient;
import uk.gov.hmcts.cp.client.ProsecutionCaseClient;
import uk.gov.hmcts.cp.client.ProsecutionCaseDetails;
import uk.gov.hmcts.cp.dto.ConfirmedHearing;
import uk.gov.hmcts.cp.event.ConfirmedHearingEvent;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a {@code hearing-confirmed}/{@code hearing-updated} event into zero or more Libra
 * {@code confirmedHearing} callbacks - one per Enforcement-typed case in the group, not one per
 * hearing (a group hearing can mix Enforcement and non-Enforcement cases).
 */
@Slf4j
@Component
public class EnforcementHearingConfirmationService {

    private static final DateTimeFormatter TIME_OF_HEARING_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    /** sittingDay arrives as literal UTC on the wire - must convert to real UK court local time. */
    private static final ZoneId COURT_ZONE = ZoneId.of("Europe/London");

    private final ProsecutionCaseClient prosecutionCaseClient;
    private final LibraClient libraClient;
    private final String enforcementAuthorityCode;

    public EnforcementHearingConfirmationService(final ProsecutionCaseClient prosecutionCaseClient,
                                                  final LibraClient libraClient,
                                                  @Value("${cp.enforcement.authority-code}") final String enforcementAuthorityCode) {
        this.prosecutionCaseClient = prosecutionCaseClient;
        this.libraClient = libraClient;
        this.enforcementAuthorityCode = enforcementAuthorityCode;
    }

    /**
     * A lookup/POST failure for one case is logged and does not affect the others in the same
     * event - retry/error-handling strategy is a separate, not-yet-designed piece of work.
     */
    public void processConfirmedHearing(final ConfirmedHearingEvent event) {
        final Optional<ZonedDateTime> sittingDay = earliestSittingDay(event);
        final String courtHearingLocation = event.courtCentre() != null ? event.courtCentre().code() : null;

        if (sittingDay.isEmpty() || courtHearingLocation == null) {
            log.warn("Ignoring confirmedHearing event with no sitting day / court centre code - nothing to confirm");
            return;
        }

        for (final ConfirmedHearingEvent.ConfirmedProsecutionCase prosecutionCase : safeCases(event)) {
            try {
                prosecutionCaseClient.findByCaseId(prosecutionCase.id())
                        .filter(this::isEnforcement)
                        .ifPresent(details -> libraClient.confirmHearing(
                                toConfirmedHearing(details, courtHearingLocation, sittingDay.get())));
            } catch (final RuntimeException e) {
                log.error("Failed to process confirmedHearing callback for case {}", prosecutionCase.id(), e);
            }
        }
    }

    private boolean isEnforcement(final ProsecutionCaseDetails details) {
        return enforcementAuthorityCode.equals(details.prosecutionAuthorityOUCode());
    }

    private static ConfirmedHearing toConfirmedHearing(final ProsecutionCaseDetails details,
                                                        final String courtHearingLocation,
                                                        final ZonedDateTime sittingDay) {
        final ZonedDateTime courtLocalSittingDay = sittingDay.withZoneSameInstant(COURT_ZONE);
        return new ConfirmedHearing(details.caseUrn(), courtHearingLocation, courtLocalSittingDay.toLocalDate(),
                courtLocalSittingDay.format(TIME_OF_HEARING_FORMAT));
    }

    /**
     * A hearing can span multiple days (multi-day trials, adjournments) - take the earliest, not
     * whichever happens to be first in the array, matching the convention Progression's own
     * consumers of this same event already use.
     */
    private static Optional<ZonedDateTime> earliestSittingDay(final ConfirmedHearingEvent event) {
        return safeHearingDays(event).stream()
                .map(ConfirmedHearingEvent.HearingDay::sittingDay)
                .filter(Objects::nonNull)
                .min(ZonedDateTime::compareTo);
    }

    private static List<ConfirmedHearingEvent.HearingDay> safeHearingDays(final ConfirmedHearingEvent event) {
        return event.hearingDays() != null ? event.hearingDays() : List.of();
    }

    private static List<ConfirmedHearingEvent.ConfirmedProsecutionCase> safeCases(final ConfirmedHearingEvent event) {
        return event.prosecutionCases() != null ? event.prosecutionCases() : List.of();
    }
}
