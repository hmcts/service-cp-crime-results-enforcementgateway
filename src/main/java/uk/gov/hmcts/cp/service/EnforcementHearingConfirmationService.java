package uk.gov.hmcts.cp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.client.LibraClient;
import uk.gov.hmcts.cp.client.ProsecutionCaseClient;
import uk.gov.hmcts.cp.client.ProsecutionCaseDetails;
import uk.gov.hmcts.cp.client.ReferenceDataClient;
import uk.gov.hmcts.cp.dto.ConfirmedHearing;
import uk.gov.hmcts.cp.event.ConfirmedHearingEvent;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
    private final ReferenceDataClient referenceDataClient;
    private final String enforcementAuthorityCode;

    public EnforcementHearingConfirmationService(final ProsecutionCaseClient prosecutionCaseClient,
                                                  final LibraClient libraClient,
                                                  final ReferenceDataClient referenceDataClient,
                                                  @Value("${cp.enforcement.authority-code}") final String enforcementAuthorityCode) {
        this.prosecutionCaseClient = prosecutionCaseClient;
        this.libraClient = libraClient;
        this.referenceDataClient = referenceDataClient;
        this.enforcementAuthorityCode = enforcementAuthorityCode;
    }

    /**
     * A lookup/POST failure for one case is logged and does not affect the others in the same
     * event - retry/error-handling strategy is a separate, not-yet-designed piece of work.
     */
    public void processConfirmedHearing(final ConfirmedHearingEvent event) {
        final Optional<ZonedDateTime> sittingDay = earliestSittingDay(event);
        final String courtCentreCode = event.courtCentre() != null ? event.courtCentre().code() : null;

        if (sittingDay.isEmpty() || courtCentreCode == null) {
            log.warn("Ignoring confirmedHearing event with no sitting day / court centre code - nothing to confirm");
            return;
        }
        // looked up once per event, and only if an enforcement case needs it - most hearings have none
        final Supplier<String> courtHearingLocation = once(() -> courtHearingLocation(event.courtCentre()));

        for (final ConfirmedHearingEvent.ConfirmedProsecutionCase prosecutionCase : safeCases(event)) {
            try {
                prosecutionCaseClient.findByCaseId(prosecutionCase.id())
                        .filter(details -> isEnforcement(details, prosecutionCase.id()))
                        .filter(details -> hasCaseUrn(details, prosecutionCase.id()))
                        .ifPresent(details -> libraClient.confirmHearing(
                                toConfirmedHearing(details, courtHearingLocation.get(), sittingDay.get())));
                // deliberately broad: a lookup/POST failure for one case must not stop the others in the same event
            } catch (@SuppressWarnings("PMD.AvoidCatchingGenericException") final RuntimeException e) {
                // class name only: an exception message can quote payload values (constitution IV)
                log.error("Failed to process confirmedHearing callback for case {}: {}", prosecutionCase.id(), e.getClass().getSimpleName());
            }
        }
    }

    /**
     * The allocated courtroom's OU code (e.g. {@code B01LY01}), falling back to the court centre's own
     * OU code (e.g. {@code B01LY00}) when the hearing has no room yet or Reference Data has no mapping
     * for it - the same rule as Progression's {@code transformCourtCentre} for this event.
     */
    private String courtHearingLocation(final ConfirmedHearingEvent.CourtCentre courtCentre) {
        final String location;
        if (courtCentre.roomId() == null) {
            location = courtCentre.code();
        } else {
            final Optional<String> courtroomOuCode = referenceDataClient.findCourtroomOuCode(courtCentre.roomId());
            location = courtroomOuCode.orElse(courtCentre.code());
            if (courtroomOuCode.isPresent()) {
                log.info("Courtroom {} resolved to courtHearingLocation {}", courtCentre.roomId(), location);
            } else {
                log.warn("No OU code for courtroom {}: courtHearingLocation falls back to the court centre code {}",
                        courtCentre.roomId(), location);
            }
        }
        return location;
    }

    private static <T> Supplier<T> once(final Supplier<T> supplier) {
        final AtomicReference<T> value = new AtomicReference<>();
        return () -> value.updateAndGet(current -> current != null ? current : supplier.get());
    }

    /** caseUrn is required by the contract: an enforcement case without one is skipped, never sent as null. */
    private static boolean hasCaseUrn(final ProsecutionCaseDetails details, final UUID caseId) {
        final boolean present = details.caseUrn() != null && !details.caseUrn().isBlank();
        if (!present) {
            log.warn("Enforcement case {} has no caseURN in Progression: no confirmedHearing callback", caseId);
        }
        return present;
    }

    private boolean isEnforcement(final ProsecutionCaseDetails details, final UUID caseId) {
        final boolean enforcement = enforcementAuthorityCode.equals(details.prosecutionAuthorityOUCode());
        if (!enforcement) {
            log.info("Case {} is not an enforcement case: no confirmedHearing callback", caseId);
        }
        return enforcement;
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
