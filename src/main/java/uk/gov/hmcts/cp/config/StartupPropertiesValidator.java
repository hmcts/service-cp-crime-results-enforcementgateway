package uk.gov.hmcts.cp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks the connection settings when the context starts and states, once, where this pod is pointed. Same idea as
 * the enforcement workflow's {@code StartupPropertiesValidator}.
 *
 * <p>When the hearing-allocation listener runs ({@code docker} profile and {@code spring.jms.listener.auto-startup},
 * i.e. {@code ARTEMIS_LISTENER_ENABLED=true}; it is off when unset) it refuses to start without real system user ids. Progression and
 * Reference Data reject a call whose {@code CJSCPPUID} is not a System Users member; with a missing or all-zero id the
 * gateway would start normally, every lookup would fail and no confirmedHearing would reach Libra, so it fails at
 * startup instead, naming the setting. When the listener doesn't run (no profile, or deployed passive with
 * {@code ARTEMIS_LISTENER_ENABLED} unset or false) nothing calls Progression or Reference Data, so the ids aren't checked; the
 * startup line then says {@code hearingAllocationListener=off}.
 *
 * <p>The startup line carries hosts only, never whole URLs (a path or query can carry a token), never a system user id,
 * and never a credential.
 */
@Slf4j
@Component
public class StartupPropertiesValidator implements InitializingBean {

    private static final UUID NIL = new UUID(0, 0);
    private static final String LISTENER_PROFILE = "docker";

    private final Environment environment;
    private final boolean listenerAutoStartup;
    private final String progressionUrl;
    private final String progressionCjscppuid;
    private final String referenceDataUrl;
    private final String referenceDataCjscppuid;
    private final String libraApimBaseUrl;
    private final String brokerUrl;
    private final String publicEventTopic;

    public StartupPropertiesValidator(final Environment environment,
                                      @Value("${spring.jms.listener.auto-startup:false}") final boolean listenerAutoStartup,
                                      @Value("${cp.progression.query-api.base-url:}") final String progressionUrl,
                                      @Value("${cp.progression.query-api.cjscppuid:}") final String progressionCjscppuid,
                                      @Value("${cp.referencedata.query-api.base-url:}") final String referenceDataUrl,
                                      @Value("${cp.referencedata.query-api.cjscppuid:}") final String referenceDataCjscppuid,
                                      @Value("${cp.libra.apim-base-url:}") final String libraApimBaseUrl,
                                      @Value("${spring.artemis.broker-url:}") final String brokerUrl,
                                      @Value("${cp.messaging.public-event-topic:}") final String publicEventTopic) {
        this.environment = environment;
        this.listenerAutoStartup = listenerAutoStartup;
        this.progressionUrl = progressionUrl;
        this.progressionCjscppuid = progressionCjscppuid;
        this.referenceDataUrl = referenceDataUrl;
        this.referenceDataCjscppuid = referenceDataCjscppuid;
        this.libraApimBaseUrl = libraApimBaseUrl;
        this.brokerUrl = brokerUrl;
        this.publicEventTopic = publicEventTopic;
    }

    @Override
    public void afterPropertiesSet() {
        final boolean listenerOn = listenerAutoStartup && environment.acceptsProfiles(Profiles.of(LISTENER_PROFILE));
        if (listenerOn) {
            requireSystemUser("cp.progression.query-api.cjscppuid (PROGRESSION_CJSCPPUID)", progressionCjscppuid);
            requireSystemUser("cp.referencedata.query-api.cjscppuid (REFERENCEDATA_CJSCPPUID)", referenceDataCjscppuid);
        }
        log.info("Effective configuration. hearingAllocationListener={} progressionHost={} referenceDataHost={}"
                        + " libraApimHost={} brokerHost={} publicEventTopic={}",
                listenerOn ? "on" : "off",
                hostOf(progressionUrl),
                hostOf(referenceDataUrl),
                hostOf(libraApimBaseUrl),
                hostOf(brokerUrl),
                publicEventTopic);
    }

    /** A system user id must be a UUID and not the all-zero placeholder. The value is never echoed. */
    /* default */ static void requireSystemUser(final String setting, final String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(setting + " must be set to a System Users member id");
        }
        final UUID id;
        try {
            id = UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(setting + " must be a UUID", e);
        }
        if (NIL.equals(id)) {
            throw new IllegalStateException(setting + " is the all-zero placeholder; set it to a System Users member id");
        }
    }

    /** The host of a URL, and nothing else about it; {@code ?} when it can't be read. */
    /* default */ static String hostOf(final String url) {
        Optional<String> host;
        try {
            host = Optional.ofNullable(url).filter(StringUtils::hasText).map(u -> URI.create(u.trim()).getHost());
        } catch (IllegalArgumentException e) {
            host = Optional.empty();
        }
        return host.orElse("?");
    }
}
