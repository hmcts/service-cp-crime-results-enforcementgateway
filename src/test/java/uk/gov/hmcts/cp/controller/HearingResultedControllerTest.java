package uk.gov.hmcts.cp.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.client.LibraCallException;
import uk.gov.hmcts.cp.client.LibraClient;
import uk.gov.hmcts.cp.openapi.model.HearingResultedResponse;
import uk.gov.hmcts.cp.support.HearingResultedFixtures;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HearingResultedController.class)
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class HearingResultedControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LibraClient libraClient;

    @Test
    void valid_request_should_return_libra_response_unchanged() throws Exception {
        when(libraClient.resultHearing(any())).thenReturn(
                LibraClient.JSON.readValue(HearingResultedFixtures.responseJson(), HearingResultedResponse.class));

        mockMvc.perform(post("/hearingResulted").contentType(MediaType.APPLICATION_JSON).content(HearingResultedFixtures.requestJson()))
                .andExpect(status().isOk())
                .andExpect(content().json(HearingResultedFixtures.responseJson()));
    }

    @Test
    void missing_case_urn_should_return_400() throws Exception {
        final String body = HearingResultedFixtures.requestJson().replace("\"caseUrn\": \"E012345678\",", "");

        mockMvc.perform(post("/hearingResulted").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAYLOAD"));
        verifyNoInteractions(libraClient);
    }

    @Test
    void unknown_result_code_should_return_400() throws Exception {
        final String body = HearingResultedFixtures.requestJson().replace("\"resultCode\": \"SC\"", "\"resultCode\": \"XYZ\"");

        mockMvc.perform(post("/hearingResulted").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAYLOAD"));
        verifyNoInteractions(libraClient);
    }

    @Test
    void libra_failure_should_return_502_with_libra_details() throws Exception {
        when(libraClient.resultHearing(any())).thenThrow(new LibraCallException(404, "E1", "not found", null));

        mockMvc.perform(post("/hearingResulted").contentType(MediaType.APPLICATION_JSON).content(HearingResultedFixtures.requestJson()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("LIBRA_CALL_FAILED"))
                .andExpect(jsonPath("$.details.libraStatus").value(404))
                .andExpect(jsonPath("$.details.errorCode").value("E1"))
                .andExpect(jsonPath("$.details.errorDescription").value("not found"));
    }
}
