package org.cses.flow.controller.execution;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import org.cses.flow.controller.flow.FlowModels.ExecutionView;
import org.cses.flow.controller.flow.FlowModels.RewindView;
import org.cses.flow.core.services.executions.ExecutionService;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionControllerTest {

    @Test
    void ownsOnlyTheExecutionServiceAtTheExecutionHttpSeam() {
        Controller controller = ExecutionController.class.getAnnotation(
            Controller.class
        );
        java.lang.reflect.Field[] fields = ExecutionController.class
            .getDeclaredFields();

        assertEquals("/executions", controller.value());
        assertEquals(1, fields.length);
        assertTrue(Arrays.stream(fields)
            .allMatch(field -> field.getType() == ExecutionService.class));
    }

    @Test
    void preservesTheExecutionRoutesAfterTheControllerSplit()
        throws NoSuchMethodException {
        Method start = ExecutionController.class.getDeclaredMethod(
            "start",
            Session.class,
            String.class,
            Optional.class,
            Map.class
        );
        Method executions = ExecutionController.class.getDeclaredMethod(
            "executions",
            Session.class
        );
        Method rewind = ExecutionController.class.getDeclaredMethod(
            "rewind",
            Session.class,
            String.class,
            String.class,
            Map.class
        );

        assertEquals(
            "/flows/{key}/executions",
            start.getAnnotation(Post.class).value()
        );
        assertEquals(
            "/executions",
            executions.getAnnotation(Get.class).value()
        );
        assertEquals(
            "/executions/{executionId}/task-runs/{taskRunId}/rewind",
            rewind.getAnnotation(Post.class).value()
        );
        assertEquals(RewindView.class, rewind.getReturnType());
    }

    @Test
    void exposesTheExecutionViewFromTheExecutionControllerPackage()
        throws NoSuchMethodException {
        Method endpoint = ExecutionController.class.getDeclaredMethod(
            "execution",
            Session.class,
            String.class
        );

        assertEquals(ExecutionView.class, endpoint.getReturnType());
    }
}
