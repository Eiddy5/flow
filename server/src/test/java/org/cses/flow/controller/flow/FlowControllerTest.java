package org.cses.flow.controller.flow;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import org.cses.flow.controller.flow.FlowModels.DraftView;
import org.cses.flow.controller.flow.FlowModels.FlowView;
import org.cses.flow.controller.flow.FlowModels.TaskView;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
    void ownsOnlyFlowRoutesAndDoesNotDefineRequestModels()
        throws IOException {
        Path controllerRoot = Path.of(
            "src/main/java/org/cses/flow/controller"
        );
        String flowController = Files.readString(
            controllerRoot.resolve("flow/FlowController.java")
        );
        String executionController = Files.readString(
            controllerRoot.resolve("execution/ExecutionController.java")
        );
        String models = Files.readString(
            controllerRoot.resolve("flow/FlowModels.java")
        );

        assertTrue(flowController.contains(
            "@Body PublishFlowCommand command"
        ));
        assertTrue(flowController.contains(
            "@QueryValue(defaultValue = \"true\") Boolean draft"
        ));
        assertFalse(flowController.contains("ExecutionService"));
        assertFalse(flowController.contains("/executions"));
        assertFalse(flowController.contains("/session"));
        assertFalse(flowController.contains("/flows/{flowKey}/draft"));
        assertFalse(flowController.contains("SaveDraftRequest"));
        assertTrue(executionController.contains(
            "@Body Map<String, Object> body"
        ));
        assertFalse(executionController.contains("FlowService"));
        assertFalse(executionController.contains("StartRequest"));
        assertFalse(executionController.contains("ResumeRequest"));
        assertFalse(models.contains("Request"));
        assertTrue(Arrays.stream(FlowController.class.getDeclaredFields())
            .noneMatch(field -> field.getType().getSimpleName()
                .equals("ExecutionService")));
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
        assertTrue(
            Files.readString(resourceRoot.resolve("flow.js"))
                .contains("state.selectedExecutionId = receipt.executionId;")
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

    @Test
    void exposesTheFlowStringEntityId() throws NoSuchMethodException {
        assertEquals(
            String.class,
            FlowView.class.getMethod("getId").getReturnType()
        );
    }

    /**
     * Verifies that draft API responses expose the system-assigned version.
     *
     * @throws NoSuchMethodException when the draft version accessor is absent
     */
    @Test
    void exposesTheDraftVersion() throws NoSuchMethodException {
        assertEquals(
            long.class,
            DraftView.class.getMethod("getVersion").getReturnType()
        );
    }

    @Test
    void exposesTheRouteDefinitionUsingTheRouteProperty()
        throws NoSuchMethodException {
        assertEquals(
            String.class,
            TaskView.class.getMethod("getRoute").getReturnType()
        );
    }
}
