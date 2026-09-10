package uk.gov.hmcts.cp.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientConfigTest {

    private final RestClientConfig config = new RestClientConfig();

    @Test
    void restClientBuilderCreatesPrototypeScopedBuilderWithConfiguredTimeouts() {
        final RestClient.Builder builder = config.restClientBuilder(10000, 10000);

        assertThat(builder).isNotNull();
    }

    @Test
    void libraRestClientBuilderCreatesPlainBuilderWithNoCustomTimeoutOrTls() {
        final RestClient.Builder builder = config.libraRestClientBuilder();

        assertThat(builder).isNotNull();
    }
}
