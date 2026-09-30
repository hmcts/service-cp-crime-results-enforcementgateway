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
    void validRequestShouldReturnLibraResponseUnchanged() throws Exception {
        when(libraClient.resultHearing(any())).thenReturn(
                LibraClient.JSON.readValue(HearingResultedFixtures.responseJson(), HearingResultedResponse.class));

        mockMvc.perform(post("/hearingResulted").contentType(MediaType.APPLICATION_JSON).content(HearingResultedFixtures.requestJson()))
                .andExpect(status().isOk())
                .andExpect(content().json(HearingResultedFixtures.responseJson()));
    }

    @Test
    void missingCaseUrnShouldReturn400() throws Exception {
        final String body = HearingResultedFixtures.requestJson().replace("\"caseUrn\": \"E012345678\",", "");

        mockMvc.perform(post("/hearingResulted").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAYLOAD"));
        verifyNoInteractions(libraClient);
    }

    @Test
    void unknownResultCodeShouldReturn400() throws Exception {
        final String body = HearingResultedFixtures.requestJson().replace("\"resultCode\": \"SC\"", "\"resultCode\": \"XYZ\"");

        mockMvc.perform(post("/hearingResulted").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAYLOAD"));
        verifyNoInteractions(libraClient);
    }

    @Test
    void libraFailureShouldReturn502WithLibraDetails() throws Exception {
        when(libraClient.resultHearing(any())).thenThrow(new LibraCallException(404, "E1", "not found", null));

        mockMvc.perform(post("/hearingResulted").contentType(MediaType.APPLICATION_JSON).content(HearingResultedFixtures.requestJson()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("LIBRA_CALL_FAILED"))
                .andExpect(jsonPath("$.details.libraStatus").value(404))
                .andExpect(jsonPath("$.details.errorCode").value("E1"))
                .andExpect(jsonPath("$.details.errorDescription").value("not found"));
    }

    @Test
    void wrongContentTypeShouldBe415Not500() throws Exception {
        mockMvc.perform(post("/hearingResulted").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(libraClient);
    }

    @Test
    void confirmedHearingShouldNotBeExposed() throws Exception {
        mockMvc.perform(post("/confirmedHearing").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseUrn\":\"E012345678\",\"courtHearingLocation\":\"B01LY00\",\"dateOfHearing\":\"2026-05-03\",\"timeOfHearing\":\"10:00\"}"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void libraAcceptedButInvalidReplyShouldBe502WithStatus200() throws Exception {
        when(libraClient.resultHearing(any())).thenThrow(LibraCallException.invalidResponse(200, "empty response body"));

        mockMvc.perform(post("/hearingResulted").contentType(MediaType.APPLICATION_JSON).content(HearingResultedFixtures.requestJson()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.details.libraStatus").value(200))
                .andExpect(jsonPath("$.details.errorCode").value("INVALID_RESPONSE"));
    }
}
