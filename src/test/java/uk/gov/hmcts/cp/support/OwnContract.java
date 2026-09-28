package uk.gov.hmcts.cp.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates JSON against this service's own contract ({@code openapi/openapi-spec.yml} in the API jar). */
public final class OwnContract {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode DOCUMENT = load();

    private OwnContract() {
    }

    public static Set<String> violations(final String schemaName, final String json) {
        try {
            final ObjectNode root = DOCUMENT.deepCopy();
            root.put("$ref", "#/components/schemas/" + schemaName);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(root).validate(JSON.readTree(json))
                    .stream().map(ValidationMessage::getMessage).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode load() {
        try (InputStream in = OwnContract.class.getClassLoader().getResourceAsStream("openapi/openapi-spec.yml")) {
            if (in == null) {
                throw new IllegalStateException("openapi/openapi-spec.yml not on the test classpath");
            }
            return new ObjectMapper(new YAMLFactory()).readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
