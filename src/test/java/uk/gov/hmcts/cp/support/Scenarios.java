package uk.gov.hmcts.cp.support;

import org.junit.jupiter.api.Named;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Finds and reads the scenario folders the scenario integration tests run (workflow research.md R25). */
public final class Scenarios {

    // strict, so a mistyped field in a scenario fails the test instead of being ignored
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Scenarios() {
    }

    /** The folders under a test-classpath directory, in name order, each named after its folder. */
    public static Stream<Named<Path>> folders(final String resource) {
        final URL root = Scenarios.class.getClassLoader().getResource(resource);
        if (root == null) {
            throw new IllegalStateException("src/test/resources/" + resource + " not found");
        }
        try (Stream<Path> children = Files.list(Path.of(root.toURI()))) {
            final List<Path> folders = children.filter(Files::isDirectory).sorted().toList();
            if (folders.isEmpty()) {
                throw new IllegalStateException("No scenario folders in src/test/resources/" + resource);
            }
            return folders.stream().map(folder -> Named.of(folder.getFileName().toString(), folder));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> T read(final Path file, final Class<T> type) {
        return MAPPER.readValue(readString(file), type);
    }

    public static String readString(final Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
