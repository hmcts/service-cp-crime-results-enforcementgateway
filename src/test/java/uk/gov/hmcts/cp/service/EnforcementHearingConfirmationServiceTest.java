package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import uk.gov.hmcts.cp.client.LibraClient;
import uk.gov.hmcts.cp.client.ProsecutionCaseClient;
import uk.gov.hmcts.cp.client.ProsecutionCaseDetails;
import uk.gov.hmcts.cp.dto.ConfirmedHearing;
import uk.gov.hmcts.cp.event.ConfirmedHearingEvent;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class EnforcementHearingConfirmationServiceTest {

    private static final String ENFORCEMENT_AUTHORITY_CODE = "GAPGD00";
    // deliberately a GMT-period date (winter) so UTC and Europe/London coincide - keeps these
    // unrelated tests simple; BST conversion itself is verified separately below.
    private static final ZonedDateTime SITTING_DAY = ZonedDateTime.parse("2026-01-15T10:00:00Z");

    @Mock
    private ProsecutionCaseClient prosecutionCaseClient;
    @Mock
    private LibraClient libraClient;

    private EnforcementHearingConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new EnforcementHearingConfirmationService(prosecutionCaseClient, libraClient, ENFORCEMENT_AUTHORITY_CODE);
    }

    @Test
    void shouldPostConfirmedHearingForEnforcementCaseOnlyNotForNonEnforcementCaseInSameGroup() {
        final UUID enforcementCaseId = UUID.randomUUID();
        final UUID nonEnforcementCaseId = UUID.randomUUID();
        final ConfirmedHearingEvent event = eventWithCases(enforcementCaseId, nonEnforcementCaseId);

        when(prosecutionCaseClient.findByCaseId(enforcementCaseId))
                .thenReturn(Optional.of(new ProsecutionCaseDetails(ENFORCEMENT_AUTHORITY_CODE, "12GD3456789")));
        when(prosecutionCaseClient.findByCaseId(nonEnforcementCaseId))
                .thenReturn(Optional.of(new ProsecutionCaseDetails("CPS-EM", "99AB1234567")));

        service.processConfirmedHearing(event);

        verify(libraClient).confirmHearing(new ConfirmedHearing("12GD3456789", "B01LY", SITTING_DAY.toLocalDate(), "10:00"));
        verify(libraClient, never()).confirmHearing(argThat(hearing -> "99AB1234567".equals(hearing.caseUrn())));
    }

    @Test
    void shouldNotCallLibraWhenCaseLookupReturnsEmpty() {
        final UUID caseId = UUID.randomUUID();
        when(prosecutionCaseClient.findByCaseId(caseId)).thenReturn(Optional.empty());

        service.processConfirmedHearing(eventWithCases(caseId));

        verify(libraClient, never()).confirmHearing(any());
    }

    @Test
    void shouldContinueProcessingOtherCasesWhenOneCaseLookupThrows() {
        final UUID failingCaseId = UUID.randomUUID();
        final UUID okCaseId = UUID.randomUUID();
        when(prosecutionCaseClient.findByCaseId(failingCaseId)).thenThrow(new RuntimeException("boom"));
        when(prosecutionCaseClient.findByCaseId(okCaseId))
                .thenReturn(Optional.of(new ProsecutionCaseDetails(ENFORCEMENT_AUTHORITY_CODE, "12GD3456789")));

        service.processConfirmedHearing(eventWithCases(failingCaseId, okCaseId));

        verify(libraClient).confirmHearing(any());
    }

    @Test
    void shouldNotLookUpAnyCaseWhenEventHasNoSittingDay() {
        final ConfirmedHearingEvent event = new ConfirmedHearingEvent(
                new ConfirmedHearingEvent.CourtCentre("B01LY"), List.of(),
                List.of(new ConfirmedHearingEvent.ConfirmedProsecutionCase(UUID.randomUUID())));

        service.processConfirmedHearing(event);

        verify(prosecutionCaseClient, never()).findByCaseId(any());
    }

    @Test
    void shouldUseEarliestSittingDayNotFirstArrayEntryWhenHearingSpansMultipleDays() {
        final UUID caseId = UUID.randomUUID();
        final ZonedDateTime laterDay = SITTING_DAY.plusDays(5);
        // deliberately out of order: the later day appears first in the array
        final ConfirmedHearingEvent event = new ConfirmedHearingEvent(
                new ConfirmedHearingEvent.CourtCentre("B01LY"),
                List.of(new ConfirmedHearingEvent.HearingDay(laterDay), new ConfirmedHearingEvent.HearingDay(SITTING_DAY)),
                List.of(new ConfirmedHearingEvent.ConfirmedProsecutionCase(caseId)));
        when(prosecutionCaseClient.findByCaseId(caseId))
                .thenReturn(Optional.of(new ProsecutionCaseDetails(ENFORCEMENT_AUTHORITY_CODE, "12GD3456789")));

        service.processConfirmedHearing(event);

        verify(libraClient).confirmHearing(new ConfirmedHearing("12GD3456789", "B01LY", SITTING_DAY.toLocalDate(), "10:00"));
    }

    @Test
    void shouldConvertUtcSittingDayToUkLocalTimeDuringBritishSummerTime() {
        final UUID caseId = UUID.randomUUID();
        // 2026-07-15 is within BST (UTC+1) - the real UK court local time is 10:00, not the raw UTC 09:00.
        final ZonedDateTime bstSittingDayUtc = ZonedDateTime.parse("2026-07-15T09:00:00Z");
        final ConfirmedHearingEvent event = new ConfirmedHearingEvent(
                new ConfirmedHearingEvent.CourtCentre("B01LY"),
                List.of(new ConfirmedHearingEvent.HearingDay(bstSittingDayUtc)),
                List.of(new ConfirmedHearingEvent.ConfirmedProsecutionCase(caseId)));
        when(prosecutionCaseClient.findByCaseId(caseId))
                .thenReturn(Optional.of(new ProsecutionCaseDetails(ENFORCEMENT_AUTHORITY_CODE, "12GD3456789")));

        service.processConfirmedHearing(event);

        verify(libraClient).confirmHearing(new ConfirmedHearing("12GD3456789", "B01LY",
                bstSittingDayUtc.toLocalDate(), "10:00"));
    }

    @Test
    void shouldNotLookUpAnyCaseWhenEventHasNoCourtCentre() {
        final ConfirmedHearingEvent event = new ConfirmedHearingEvent(
                null, List.of(new ConfirmedHearingEvent.HearingDay(SITTING_DAY)),
                List.of(new ConfirmedHearingEvent.ConfirmedProsecutionCase(UUID.randomUUID())));

        service.processConfirmedHearing(event);

        verify(prosecutionCaseClient, never()).findByCaseId(any());
    }

    private static ConfirmedHearingEvent eventWithCases(final UUID... caseIds) {
        return new ConfirmedHearingEvent(
                new ConfirmedHearingEvent.CourtCentre("B01LY"),
                List.of(new ConfirmedHearingEvent.HearingDay(SITTING_DAY)),
                List.of(caseIds).stream().map(ConfirmedHearingEvent.ConfirmedProsecutionCase::new).toList());
    }

    // the contract requires caseUrn; an enforcement case without one is skipped, not sent as null
    @Test
    void shouldNotCallLibraWhenTheEnforcementCaseHasNoCaseUrn() {
        final UUID caseId = UUID.randomUUID();
        when(prosecutionCaseClient.findByCaseId(caseId))
                .thenReturn(Optional.of(new ProsecutionCaseDetails(ENFORCEMENT_AUTHORITY_CODE, " ")));

        service.processConfirmedHearing(eventWithCases(caseId));

        verifyNoInteractions(libraClient);
    }

    // QA can see why no callback was sent: the case id only
    @Test
    void shouldLogWhenACaseIsNotAnEnforcementCase(final CapturedOutput output) {
        final UUID caseId = UUID.randomUUID();
        when(prosecutionCaseClient.findByCaseId(caseId)).thenReturn(Optional.of(new ProsecutionCaseDetails("CPS-EM", "99AB1234567")));

        service.processConfirmedHearing(eventWithCases(caseId));

        assertThat(output.getAll()).contains("Case " + caseId + " is not an enforcement case: no confirmedHearing callback")
                .doesNotContain("99AB1234567");
    }
}
