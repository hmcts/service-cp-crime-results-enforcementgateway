package uk.gov.hmcts.cp.integration;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class ActuatorIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;


    @Resource
    private MockMvc mockMvc;

    @Test
    void actuatorInfoShouldHaveBuildFields() throws Exception {
        final String name = "service-cp-crime-results-enforcementgateway";
        mockMvc.perform(get("/actuator/info"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.artifact").value(name))
                .andExpect(jsonPath("$.build.name").value(name))
                .andExpect(jsonPath("$.build.time").exists())
                .andExpect(jsonPath("$.build.version").exists());
    }

    @Test
    void actuatorInfoShouldHaveGorylenkoGitFields() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.branch").exists())
                .andExpect(jsonPath("$.git.commit.id").exists())
                .andExpect(jsonPath("$.git.commit.time").exists());
    }

    @Test
    void actuatorHealthShouldHaveCorrectFields() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.groups[0]").value("liveness"))
                .andExpect(jsonPath("$.groups[1]").value("readiness"));
    }

    // No OTLP collector runs in our environments: pushing every minute only logged "Failed to publish metrics to
    // OTLP receiver". Metrics are still scraped from /actuator/prometheus. OTLP_METRICS_EXPORT_ENABLED turns it on.
    @Test
    void otlpMetricsPushShouldBeOffByDefault() {
        assertThat(applicationContext.getBeansOfType(MeterRegistry.class).values())
                .extracting(registry -> registry.getClass().getSimpleName())
                .noneMatch(name -> name.startsWith("Otlp"));
    }
}
