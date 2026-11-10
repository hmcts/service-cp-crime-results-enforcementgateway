package uk.gov.hmcts.cp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deployed (docker profile), startup refuses missing or placeholder system user ids; it states where the pod is pointed. */
@ExtendWith(OutputCaptureExtension.class)
class StartupPropertiesValidatorTest {

    // generated per run, so no id is held in source
    private static final String PROGRESSION_USER = UUID.randomUUID().toString();
    private static final String REFDATA_USER = UUID.randomUUID().toString();
    private static final String PLACEHOLDER = new UUID(0, 0).toString();

    private static StartupPropertiesValidator validator(final boolean deployed, final String progressionUser,
                                                        final String refdataUser) {
        return validator(deployed, true, progressionUser, refdataUser);
    }

    private static StartupPropertiesValidator validator(final boolean deployed, final boolean listenerEnabled,
                                                        final String progressionUser, final String refdataUser) {
        final MockEnvironment environment = new MockEnvironment();
        if (deployed) {
            environment.setActiveProfiles("docker");
        }
        return new StartupPropertiesValidator(environment, listenerEnabled,
                "http://ste01-internal.ingress01.ste.nl.example/path?token=secret", progressionUser,
                "http://ste01-internal.ingress01.ste.nl.example", refdataUser,
                "https://apim.example/enforcement", "tcp://artemis-artemis-activemq:61616", "public.event");
    }

    @Test
    void real_system_user_ids_should_start() {
        assertThatCode(() -> validator(true, PROGRESSION_USER, REFDATA_USER).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void missing_progression_id_should_not_start() {
        assertThatThrownBy(() -> validator(true, "", REFDATA_USER).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROGRESSION_CJSCPPUID");
    }

    @Test
    void placeholder_reference_data_id_should_not_start() {
        assertThatThrownBy(() -> validator(true, PROGRESSION_USER, PLACEHOLDER).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("REFERENCEDATA_CJSCPPUID")
                .hasMessageContaining("placeholder");
    }

    @Test
    void malformed_id_should_not_start_and_should_not_be_echoed() {
        assertThatThrownBy(() -> validator(true, "not-a-uuid", REFDATA_USER).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROGRESSION_CJSCPPUID")
                .hasMessageNotContaining("not-a-uuid");
    }

    // without the docker profile the listener doesn't run, so nothing calls Progression or Reference Data
    @Test
    void placeholders_should_start_when_the_listener_is_off(final CapturedOutput output) {
        assertThatCode(() -> validator(false, PLACEHOLDER, PLACEHOLDER).afterPropertiesSet()).doesNotThrowAnyException();
        assertThat(output).contains("hearingAllocationListener=off");
    }

    // deployed passive (ARTEMIS_LISTENER_ENABLED=false): no events are consumed, so no lookups need a system user
    @Test
    void placeholders_should_start_when_deployed_with_the_listener_disabled(final CapturedOutput output) {
        assertThatCode(() -> validator(true, false, PLACEHOLDER, PLACEHOLDER).afterPropertiesSet()).doesNotThrowAnyException();
        assertThat(output).contains("hearingAllocationListener=off");
    }

    @Test
    void startup_line_should_carry_hosts_only(final CapturedOutput output) {
        validator(true, PROGRESSION_USER, REFDATA_USER).afterPropertiesSet();

        assertThat(output).contains("Effective configuration.")
                .contains("hearingAllocationListener=on")
                .contains("progressionHost=ste01-internal.ingress01.ste.nl.example")
                .contains("referenceDataHost=ste01-internal.ingress01.ste.nl.example")
                .contains("libraApimHost=apim.example")
                .contains("brokerHost=artemis-artemis-activemq")
                .contains("publicEventTopic=public.event")
                .doesNotContain("token=secret").doesNotContain("/enforcement")
                .doesNotContain(PROGRESSION_USER).doesNotContain(REFDATA_USER);
    }

    @Test
    void unreadable_url_should_show_as_unknown_host() {
        assertThat(StartupPropertiesValidator.hostOf("")).isEqualTo("?");
        assertThat(StartupPropertiesValidator.hostOf("not a url")).isEqualTo("?");
    }
}
