package uk.gov.hmcts.cp.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Minimal projection of the {@code public.listing.hearing-confirmed}/{@code hearing-updated}
 * public event payload (both share the same {@code confirmedHearing} schema) - only the fields
 * this service needs. Unknown properties are ignored since the real event carries substantially
 * more data than this.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfirmedHearingEvent(CourtCentre courtCentre, List<HearingDay> hearingDays,
                                     List<ConfirmedProsecutionCase> prosecutionCases) {

    /**
     * {@code code} is the court centre's OU code (e.g. {@code B01LY00}); {@code roomId} is the
     * allocated courtroom's reference data id, absent until the hearing is allocated to a room.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourtCentre(String code, UUID roomId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HearingDay(ZonedDateTime sittingDay) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfirmedProsecutionCase(UUID id) {
    }
}
