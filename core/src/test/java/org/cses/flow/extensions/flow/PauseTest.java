package org.cses.flow.extensions.flow;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.flows.inputs.StringInput;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.extensions.log.Log;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PauseTest {

    private final Context plugins = builtInContext();

    @Test
    void ownsIndependentResumeInputsAndOutputs() {
        Task action = log("action-id", "create-approval");
        Pause pause = plugins.modelValidator().validate(Pause.builder()
            .id("pause-id")
            .key("wait-approval")
            .onPause(action)
            .onResume(List.of(
                StringInput.builder()
                    .key("decision")
                    .displayName("Decision")
                    .required(true)
                    .build(),
                StringInput.builder()
                    .key("comment")
                    .displayName("Comment")
                    .required(false)
                    .build()
            ))

            .duration(" pt5m ")
            .behavior(Pause.Behavior.FAIL)
            .build());

        assertEquals(Pause.class.getCanonicalName(), pause.getType());
        assertEquals(action, pause.onPause());
        assertEquals(List.of(action), pause.definitionChildren());
        assertEquals(action, pause.findDescendant(action.id()).orElseThrow());
        assertEquals(
            java.util.Set.of(
                Output.create("decision", DataType.STRING),
                Output.create("comment", DataType.STRING)
            ),
            java.util.Set.copyOf(pause.outputs())
        );
        assertEquals("PT5M", pause.duration().orElseThrow());
        assertEquals(Pause.Behavior.FAIL, pause.behavior().orElseThrow());
    }

    @Test
    void doesNotOwnTheOrdinaryBranchTasksField() {
        Task action = log("action-id", "create-approval");

        Pause pause = plugins.modelValidator().validate(Pause.builder()
            .id("pause-id")
            .key("wait-approval")
            .onPause(action)
            .build());

        assertEquals(List.of(action), pause.definitionChildren());
        assertTrue(java.util.Arrays.stream(Pause.class.getMethods())
            .noneMatch(method -> method.getName().equals("tasks")));
    }

    @Test
    void mapsEveryTimeoutBehaviorDirectlyToStateType() {
        assertEquals(State.Type.RUNNING, Pause.Behavior.RESUME.state());
        assertEquals(State.Type.WARNING, Pause.Behavior.WARN.state());
        assertEquals(State.Type.KILLED, Pause.Behavior.CANCEL.state());
        assertEquals(State.Type.FAILED, Pause.Behavior.FAIL.state());
    }

    @Test
    void materializesNestedTaskAndNormalizesIsoDurationFromRealFields() {
        Flow flow = plugins.deploy(
            "company-1",
            "flow-1",
            Map.of(
                "key", "approval-flow",
                "tasks", List.of(Map.of(
                    "key", "wait-approval",
                    "type", Pause.class.getCanonicalName(),
                    "onPause", Map.of(
                        "key", "create-approval",
                        "type", Log.class.getCanonicalName(), "message", "test step"
                    ),
                    "onResume", List.of(Map.of(
                        "key", "decision",
                        "type", "STRING",
                        "required", true
                    )),

                    "duration", " pt5m ",
                    "behavior", "FAIL"
                ))
            ),
            null,
            ActorRef.create("user-1", "User"),
            1L
        );

        Pause pause = assertInstanceOf(Pause.class, flow.tasks().getFirst());
        assertInstanceOf(Log.class, pause.onPause());
        assertEquals("decision",
            pause.onResume().getFirst().getDisplayName());
        assertEquals(
            List.of(Output.create("decision", DataType.STRING)),
            pause.outputs()
        );
        assertEquals("PT5M", pause.duration().orElseThrow());
        assertEquals(2, flow.allTasks().size());
        assertTrue(flow.findTask(pause.onPause().id()).isPresent());
    }

    /** 验证 Pause 收集各字段绑定结果，并保留默认值与输入拒绝规则。 */
    @Test
    void validatesResumeInputsWithDefaultsAndConcreteInputRules() {
        Pause pause = plugins.modelValidator().validate(Pause.builder()
            .id("pause-id")
            .key("wait")
            .onPause(log("action-id", "create"))
            .onResume(List.of(
                StringInput.builder()
                    .key("decision")
                    .displayName("Decision")
                    .required(true)
                    .build(),
                IntegerInput.builder()
                    .key("score")
                    .displayName("Score")
                    .required(false)
                    .defaultValue(3)
                    .min(1)
                    .max(5)
                    .build()
            ))
            .build());

        assertEquals(
            Map.of("decision", "APPROVED", "score", 3),
            pause.bindResume(Map.of("decision", "APPROVED"))
        );
        assertThrows(
            WorkflowException.class,
            () -> pause.bindResume(Map.of())
        );
        assertThrows(
            WorkflowException.class,
            () -> pause.bindResume(Map.of(
                "decision", "APPROVED",
                "score", 6
            ))
        );
        assertThrows(
            WorkflowException.class,
            () -> pause.bindResume(Map.of(
                "decision", "APPROVED",
                "extra", true
            ))
        );
    }

    @Test
    void rejectsIncompletePauseAndTimeoutDefinitions() {
        assertInvalid(Pause.builder()
            .id("pause-id")
            .key("wait")
            .build());
        assertInvalid(Pause.builder()
            .id("pause-id")
            .key("wait")
            .onPause(log("action-id", "create"))
            .duration("PT5M")
            .build());
        assertInvalid(Pause.builder()
            .id("pause-id")
            .key("wait")
            .onPause(log("action-id", "create"))
            .duration("PT0S")
            .behavior(Pause.Behavior.FAIL)
            .build());
    }

    private void assertInvalid(Pause pause) {
        assertThrows(
            RuntimeException.class,
            () -> plugins.modelValidator().validate(pause)
        );
    }

    private static Task log(String id, String key) {
        return Log.builder()
            .id(id)
            .key(key)
            .message(TemplateExpression.parse("test step"))
            .build();
    }
}
