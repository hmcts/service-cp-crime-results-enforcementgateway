package uk.gov.hmcts.cp.controller;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import uk.gov.hmcts.cp.client.LibraCallException;
import uk.gov.hmcts.cp.openapi.model.ErrorResponse;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error mapping for the gateway contract. A payload that fails validation returns 400. Libra/APIM
 * rejecting the call, failing or timing out returns 502, with Libra's status and error fields in
 * {@code details}. Messages never echo the request payload (PII, FR-017).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleInvalidPayload(final Exception e) {
        log.warn("Rejected hearing-result payload: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error("INVALID_PAYLOAD", "Request does not match the HearingResultedRequest contract", Map.of()));
    }

    @ExceptionHandler(LibraCallException.class)
    public ResponseEntity<ErrorResponse> handleLibraCallFailure(final LibraCallException e) {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("libraStatus", e.getLibraStatus());
        details.put("errorCode", e.getErrorCode());
        details.put("errorDescription", e.getErrorDescription());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error("LIBRA_CALL_FAILED", e.getMessage(), details));
    }

    private static ErrorResponse error(final String code, final String message, final Map<String, Object> details) {
        final ErrorResponse response = new ErrorResponse();
        response.setError(code);
        response.setMessage(message);
        response.setDetails(details);
        response.setTimestamp(Instant.now());
        response.setTraceId(MDC.get("traceId"));
        return response;
    }
}
