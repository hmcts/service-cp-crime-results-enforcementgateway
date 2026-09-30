package uk.gov.hmcts.cp.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Libra/APIM timeouts (workflow research.md R20): 5s connect + 40s read, below the workflow's 50s read timeout. */
class LibraRestClientTimeoutTest {

    private final WireMockServer apim = new WireMockServer(wireMockConfig().dynamicPort());

    @BeforeEach
    void start() {
        apim.start();
    }

    @AfterEach
    void stop() {
        apim.stop();
    }

    @Test
    void readTimeoutShouldBeApplied() {
        apim.stubFor(post(urlEqualTo("/hearingResulted")).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));
        final RestClient client = new RestClientConfig().libraRestClientBuilder(5000, 300).baseUrl(apim.baseUrl()).build();

        assertThatThrownBy(() -> client.post().uri("/hearingResulted").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void defaultsShouldStayBelowTheWorkflowReadTimeout() throws IOException {
        final PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml")).getFirst();

        assertThat(String.valueOf(yaml.getProperty("cp.libra.connect-timeout-ms"))).endsWith(":5000}");
        // 5s + 40s = 45s, below the workflow's 50s read timeout towards this gateway
        assertThat(String.valueOf(yaml.getProperty("cp.libra.read-timeout-ms"))).endsWith(":40000}");
    }
}
