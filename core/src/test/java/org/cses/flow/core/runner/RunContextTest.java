package org.cses.flow.core.runner;

import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunContextTest {

    @Test
    void derivesRuntimeInfoFromTheVariableTree() {
        RunContext context = context(Map.of(
            "parent",
            Map.of("taskRun", Map.of("id", "parent-run-1"))
        ));

        RunContext.TaskRunInfo taskRunInfo = context.taskRunInfo();
        assertEquals("execution-1", taskRunInfo.executionId());
        assertEquals("task-run-1", taskRunInfo.id());
        assertEquals("task-1", taskRunInfo.taskId());
        assertEquals("task", taskRunInfo.taskKey());
        assertEquals(Map.of(), taskRunInfo.outputs());
        assertEquals(
            RunContext.FlowInfo.from(Map.of(
                "id", "flow-1",
                "key", "flow",
                "companyId", "company-1",
                "version", 1L
            )),
            context.flowInfo()
        );
        assertEquals(
            "parent-run-1",
            context.parentTaskRunId().orElseThrow()
        );
        assertTrue(context(Map.of()).parentTaskRunId().isEmpty());
    }

    @Test
    void rejectsMissingOrInvalidRuntimeIdentityPaths() {
        RunContext missing = RunContext.builder()
            .variables(Map.of())
            .build();
        assertThrows(IllegalStateException.class, missing::taskRunInfo);
        assertEquals(RunContext.FlowInfo.empty(), missing.flowInfo());

        RunContext invalid = context(Map.of(
            "taskRun",
            Map.of(
                "id", 1,
                "inputs", Map.of(),
                "outputs", Map.of()
            )
        ));
        assertThrows(IllegalStateException.class, invalid::taskRunInfo);
    }

    @Test
    void exposesImmutableInputsTaskInputsAndFlowVariables() {
        RunContext context = context(Map.of(
            "inputs", Map.of("amount", 1200),
            "vars", Map.of("environment", "prod"),
            "taskRun", Map.of(
                "id", "task-run-1",
                "inputs", Map.of("iteration", 2)
            )
        ));

        assertEquals(Map.of("amount", 1200), context.inputs());
        assertEquals(Map.of("iteration", 2), context.taskInputs());
        assertEquals(
            Map.of("environment", "prod"),
            context.flowVariables()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> context.inputs().put("amount", 1)
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> context.taskInputs().put("iteration", 3)
        );
    }

    @Test
    void rendersExpressionsAgainstTheCompleteVariableTree() {
        RunContext context = context(Map.of(
            "inputs", Map.of("orderId", "order-1"),
            "outputs", Map.of(
                "prepare",
                Map.of("result", "ready")
            ),
            "vars", Map.of("environment", "prod"),
            "task", Map.of("key", "notify", "type", "log"),
            "execution", Map.of(
                "id", "execution-1",
                "outputs", Map.of("summary", "done")
            ),
            "parent", Map.of(
                "task", Map.of("key", "sequence"),
                "taskRun", Map.of("id", "parent-run-1")
            )
        ));

        assertEquals(
            "order-1/ready/prod/notify/task-run-1/execution-1/done/sequence",
            context.render(TemplateExpression.parse(
                "{{ inputs.orderId }}/{{ outputs.prepare.result }}/"
                    + "{{ vars.environment }}/{{ task.key }}/"
                    + "{{ taskRun.id }}/{{ execution.id }}/"
                    + "{{ execution.outputs.summary }}/{{ parent.task.key }}"
            ))
        );
    }

    @Test
    void storesOnlyTheCanonicalVariablesSnapshot() {
        assertEquals(
            List.of("variables"),
            java.util.Arrays.stream(RunContext.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(
                    field.getModifiers()
                ))
                .map(java.lang.reflect.Field::getName)
                .toList()
        );
        assertFalse(java.util.Arrays.stream(RunContext.class.getMethods())
            .anyMatch(method -> method.getName().equals("session")));
    }

    private static RunContext context(Map<String, ?> overrides) {
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        variables.put("inputs", Map.of());
        variables.put("outputs", Map.of());
        variables.put("vars", Map.of());
        variables.put("flow", Map.of(
            "id", "flow-1",
            "key", "flow",
            "companyId", "company-1",
            "version", 1L
        ));
        variables.put("task", Map.of(
            "id", "task-1",
            "key", "task",
            "type", "test"
        ));
        variables.put("taskRun", Map.of(
            "id",
            "task-run-1",
            "inputs",
            Map.of(),
            "outputs",
            Map.of()
        ));
        variables.put("execution", Map.of(
            "id",
            "execution-1",
            "outputs",
            Map.of()
        ));
        variables.put("parent", Map.of());
        variables.put("parents", List.of());
        variables.putAll(overrides);
        return RunContext.builder().variables(variables).build();
    }
}
