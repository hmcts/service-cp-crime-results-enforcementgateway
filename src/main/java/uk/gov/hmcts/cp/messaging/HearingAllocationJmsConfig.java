package uk.gov.hmcts.cp.messaging;

import jakarta.jms.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.jms.autoconfigure.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;

/**
 * Listener container factory for {@link HearingAllocationEventListener}, with its own JMS client id.
 * Each listener container opens its own connection, and a broker accepts one connection per client
 * id. With both durable listeners on {@code spring.jms.client-id}, the second to start (this one or
 * {@link PublicEventLoggingListener}) failed with "clientID ... was already set into another
 * connection" and retried forever, so the confirmedHearing flow received no events (reproduced by
 * {@code JmsListenersIntegrationTest}; workflow research.md open item 11, same fix as the workflow's
 * R23). The durable subscription is identified by this client id plus
 * {@code cp.messaging.hearing-allocation-subscription-name}. It uses the plain, not the caching,
 * connection factory, as Spring Boot does for its own listener factory.
 */
@Configuration
@Profile("docker")
public class HearingAllocationJmsConfig {

    public static final String CONTAINER_FACTORY = "hearingAllocationListenerContainerFactory";

    @Bean(name = CONTAINER_FACTORY)
    public DefaultJmsListenerContainerFactory hearingAllocationListenerContainerFactory(
            final DefaultJmsListenerContainerFactoryConfigurer configurer,
            final ConnectionFactory connectionFactory,
            @Value("${cp.messaging.hearing-allocation-client-id}") final String clientId) {
        final DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, ConnectionFactoryUnwrapper.unwrapCaching(connectionFactory));
        factory.setClientId(clientId);
        return factory;
    }
}
