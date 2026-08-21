package org.cses.flow.controller.flow;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import org.paas.session.Session;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
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
    void doesNotDefineControllerRequestModels() throws IOException {
        Path controllerRoot = Path.of(
            "src/main/java/org/cses/flow/controller"
        );
        String controller = Files.readString(
            controllerRoot.resolve("flow/FlowController.java")
        );
        String models = Files.readString(
            controllerRoot.resolve("flow/FlowModels.java")
        );

        assertTrue(controller.contains("@Body SaveFlowDraftCommand command"));
        assertTrue(controller.contains("@Body Map<String, Object> body"));
        assertFalse(controller.contains("SaveDraftRequest"));
        assertFalse(controller.contains("StartRequest"));
        assertFalse(controller.contains("ResumeRequest"));
        assertFalse(models.contains("Request"));
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

    @Test
    void exposesTheExactDeployedDefinitionWithoutExpandingTheFlowList()
        throws NoSuchMethodException {
        Method endpoint = FlowController.class.getDeclaredMethod(
            "flowDefinition",
            Session.class,
            String.class,
            long.class
        );

        assertEquals(
            "/flows/{flowKey}/reversions/{reversion}/definition",
            endpoint.getAnnotation(Get.class).value()
        );
    }
}
