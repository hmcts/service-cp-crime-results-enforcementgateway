package uk.gov.hmcts.cp.support;

import uk.gov.hmcts.cp.client.LibraClient;
import uk.gov.hmcts.cp.openapi.model.HearingResultedRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Sample hearing-result request/response JSON in src/test/resources/hearingresulted. */
public final class HearingResultedFixtures {

    private HearingResultedFixtures() {
    }

    public static String requestJson() {
        return resource("hearingresulted/request.json");
    }

    public static String responseJson() {
        return resource("hearingresulted/response.json");
    }

    public static HearingResultedRequest request() {
        return LibraClient.JSON.readValue(requestJson(), HearingResultedRequest.class);
    }

    private static String resource(final String name) {
        try (InputStream in = HearingResultedFixtures.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
