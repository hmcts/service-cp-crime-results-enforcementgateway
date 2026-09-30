package uk.gov.hmcts.cp.logging;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Scopes {@link HearingResultedTrafficLoggingFilter} to the {@code HearingResultedController} endpoint only. */
@Configuration
public class LoggingConfiguration {

    @Bean
    public FilterRegistrationBean<HearingResultedTrafficLoggingFilter> hearingResultedTrafficLoggingFilter() {
        final FilterRegistrationBean<HearingResultedTrafficLoggingFilter> registration =
                new FilterRegistrationBean<>(new HearingResultedTrafficLoggingFilter());
        registration.addUrlPatterns("/hearingResulted");
        return registration;
    }
}
