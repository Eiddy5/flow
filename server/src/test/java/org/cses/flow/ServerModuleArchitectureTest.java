package org.cses.flow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerModuleArchitectureTest {

    private static final Path FLOW = Path.of(
        "src/main/java/org/cses/flow"
    );

    @Test
    void serverContainsOnlyHttpAndApplicationSources() throws IOException {
        List<String> invalid;
        try (var paths = Files.walk(FLOW)) {
            invalid = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !isServerSource(FLOW.relativize(path)))
                .map(Path::toString)
                .toList();
        }

        assertTrue(
            invalid.isEmpty(),
            () -> "Non-HTTP sources found in the Server module: " + invalid
        );
    }

    private static boolean isServerSource(Path relativePath) {
        String path = relativePath.toString().replace('\\', '/');
        return path.equals("Application.java")
            || path.startsWith("controller/");
    }
}
