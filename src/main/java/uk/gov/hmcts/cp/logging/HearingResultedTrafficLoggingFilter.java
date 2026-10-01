package uk.gov.hmcts.cp.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

/**
 * Logs every request to, and response from, {@code POST /hearingResulted} at INFO — metadata only
 * ({@code httpMethod}, {@code path}, {@code status}, {@code durationMs}; {@code traceId} comes from
 * the MDC). Bodies are never read or logged: they carry defendant PII, case references, hearing
 * dates and bank details ({@code context/logging-standards.md} "Never log"). No headers are logged.
 *
 * <p>A filter rather than logging in the controller, so that 400 rejections — produced before the
 * controller runs — are logged too.
 */
@Slf4j
public class HearingResultedTrafficLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        final long start = System.nanoTime();
        log.info("Hearing resulted request received",
                keyValue("httpMethod", request.getMethod()),
                keyValue("path", request.getRequestURI()));
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info("Hearing resulted response sent",
                    keyValue("httpMethod", request.getMethod()),
                    keyValue("path", request.getRequestURI()),
                    keyValue("status", response.getStatus()),
                    keyValue("durationMs", (System.nanoTime() - start) / 1_000_000));
        }
    }
}
