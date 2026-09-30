package uk.gov.hmcts.cp.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientConfigTest {

    private final RestClientConfig config = new RestClientConfig();

    @Test
    void bothBuildersApplyTheirReadTimeout() {
        final WireMockServer slow = new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));
        slow.start();
        try {
            slow.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));

            for (final RestClient.Builder builder : List.of(config.restClientBuilder(1000, 300), config.libraRestClientBuilder(1000, 300))) {
                final long start = System.nanoTime();
                assertThatThrownBy(() -> builder.baseUrl(slow.baseUrl()).build().get().uri("/slow").retrieve().toBodilessEntity())
                        .isInstanceOf(ResourceAccessException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
            }
        } finally {
            slow.stop();
        }
    }
}
