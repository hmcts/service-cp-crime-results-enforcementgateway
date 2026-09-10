package uk.gov.hmcts.cp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * Prototype-scoped: {@link RestClient.Builder} is mutable ({@code baseUrl(...)} mutates in
     * place rather than returning a copy), so each client needs its own instance rather than
     * sharing (and clobbering) a singleton's base URL.
     *
     * <p>Explicit connect/read timeouts: called from inside a JMS listener thread - an unbounded
     * HTTP call to Progression hanging would otherwise stall message processing indefinitely.
     * Placeholder default values pending real guidance on Progression's SLA.
     */
    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder(
            @Value("${cp.http-client.connect-timeout-ms:10000}") final long connectTimeoutMs,
            @Value("${cp.http-client.read-timeout-ms:10000}") final long readTimeoutMs) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * The Libra/APIM call is deliberately simple - matching {@code cpp-context-staging-dvla}'s
     * equivalent outbound-to-APIM client, which sets no custom timeout at all. No connect/read
     * timeout, no TLS customisation: APIM is the trust boundary and does the real work
     * authenticating onward to Libra (OAuth2, per the architect) - this leg is just a plain POST.
     */
    @Bean(name = "libraRestClientBuilder")
    @Scope("prototype")
    public RestClient.Builder libraRestClientBuilder() {
        return RestClient.builder();
    }
}
