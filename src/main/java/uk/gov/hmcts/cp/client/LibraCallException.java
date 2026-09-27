package uk.gov.hmcts.cp.client;

import lombok.Getter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Libra (via APIM) rejected or failed a hearing-result call. {@code libraStatus} is null when no
 * HTTP response was received (timeout or transport failure). {@code errorCode}/{@code errorDescription}
 * come from Libra's {@code {errorCode, errorDescription}} error body when one is present.
 */
@Getter
public class LibraCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Integer libraStatus;
    private final String errorCode;
    private final String errorDescription;

    public LibraCallException(final Integer libraStatus, final String errorCode, final String errorDescription,
                              final Throwable cause) {
        super("Libra hearing-result call failed" + (libraStatus == null ? " (no response)" : " with HTTP " + libraStatus), cause);
        this.libraStatus = libraStatus;
        this.errorCode = errorCode;
        this.errorDescription = errorDescription;
    }

    /** Best-effort parse of Libra's error body. A body that isn't Libra JSON keeps only the status. */
    public static LibraCallException fromResponse(final int status, final String body) {
        String errorCode = null;
        String errorDescription = null;
        if (body != null && !body.isBlank()) {
            try {
                final JsonNode json = LibraClient.JSON.readTree(body);
                errorCode = textOrNull(json, "errorCode");
                errorDescription = textOrNull(json, "errorDescription");
            } catch (JacksonException ignored) {
                // not Libra's JSON error shape: keep only the status
            }
        }
        return new LibraCallException(status, errorCode, errorDescription, null);
    }

    public static LibraCallException noResponse(final Throwable cause) {
        return new LibraCallException(null, null, null, cause);
    }

    private static String textOrNull(final JsonNode json, final String field) {
        final JsonNode node = json.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }
}
