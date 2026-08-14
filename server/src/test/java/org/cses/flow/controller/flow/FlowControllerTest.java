package org.cses.flow.controller.flow;

import io.micronaut.http.annotation.Controller;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowControllerTest {

    @Test
    void exposesTheAuthenticatedFlowApiWithoutDemoRuntimeSwitch() {
        Controller controller = FlowController.class.getAnnotation(
            Controller.class
        );

        assertEquals("/api", controller.value());
        assertFalse(
            FlowController.class.getName().toLowerCase().contains("demo")
        );
    }

    @Test
    void servesTheManagementPageFromFlowResourceNamespace()
        throws IOException {
        Path resourceRoot = Path.of("src/main/resources/flow");

        assertTrue(Files.isRegularFile(resourceRoot.resolve("index.html")));
        assertTrue(Files.isRegularFile(resourceRoot.resolve("flow.js")));
        assertTrue(Files.isRegularFile(resourceRoot.resolve("flow.css")));
        assertTrue(
            Files.readString(resourceRoot.resolve("index.html"))
                .contains("Flow Studio")
        );
        assertTrue(
            Files.readString(resourceRoot.resolve("flow.js"))
                .contains("const API = \"/api\";")
        );
        assertFalse(
            Files.readString(resourceRoot.resolve("flow.js"))
                .contains("/api/demo")
        );
    }
}
