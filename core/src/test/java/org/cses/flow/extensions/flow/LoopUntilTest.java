package org.cses.flow.extensions.flow;

import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.conditions.Condition;
import org.cses.flow.core.domains.tasks.OrchestrationTask.IterationDecision;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.extensions.log.Log;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoopUntilTest {

    private final Context plugins = builtInContext();

    @Test
    void validatesAStringOutputInsideItsOwnBody() {
        LoopUntil loop = plugins.modelValidator().validate(
            LoopUntil.builder()
                .id("loop-id")
                .key("poll")
                .condition(Condition.parser(
                    "{{ outputs.check.status }} == DONE"
                ))
                .maxIterations(5)
                .tasks(List.of(checkTask(DataType.STRING)))
                .build()
        );

        assertEquals(5, loop.maxIterations());
        assertEquals(
            "{{ outputs.check.status }} == DONE",
            loop.condition().source()
        );
    }

    @Test
    void rejectsMissingExternalAndNonStringConditionOutputs() {
        assertThrows(RuntimeException.class, () ->
            plugins.modelValidator().validate(LoopUntil.builder()
                .id("loop-id")
                .key("poll")
                .maxIterations(3)
                .tasks(List.of(checkTask(DataType.STRING)))
                .build())
        );
        assertThrows(RuntimeException.class, () ->
            plugins.modelValidator().validate(LoopUntil.builder()
                .id("loop-id")
                .key("poll")
                .condition(Condition.parser(
                    "{{ outputs.outside.status }} == DONE"
                ))
                .maxIterations(3)
                .tasks(List.of(checkTask(DataType.STRING)))
                .build())
        );
        assertThrows(RuntimeException.class, () ->
            plugins.modelValidator().validate(LoopUntil.builder()
                .id("loop-id")
                .key("poll")
                .condition(Condition.parser(
                    "{{ outputs.check.status }} == DONE"
                ))
                .maxIterations(3)
                .tasks(List.of(checkTask(DataType.INTEGER)))
                .build())
        );
    }

    @Test
    void requiresTheLoopUntilTaskAndOutputPathShape() {
        assertThrows(RuntimeException.class, () ->
            plugins.modelValidator().validate(LoopUntil.builder()
                .id("loop-id")
                .key("poll")
                .condition(Condition.parser(
                    "{{ outputs.status }} == DONE"
                ))
                .maxIterations(3)
                .tasks(List.of(checkTask(DataType.STRING)))
                .build())
        );
        assertThrows(RuntimeException.class, () ->
            plugins.modelValidator().validate(LoopUntil.builder()
                .id("loop-id")
                .key("poll")
                .condition(Condition.parser(
                    "{{ outputs.group.check.status }} == DONE"
                ))
                .maxIterations(3)
                .tasks(List.of(checkTask(DataType.STRING)))
                .build())
        );
    }

    @Test
    void derivesEveryIterationDecisionFromTheCurrentOutputs() {
        LoopUntil loop = plugins.modelValidator().validate(
            LoopUntil.builder()
                .id("loop-id")
                .key("poll")
                .condition(Condition.parser(
                    "{{ outputs.check.status }} == DONE"
                ))
                .maxIterations(3)
                .tasks(List.of(checkTask(DataType.STRING)))
                .build()
        );

        assertEquals(
            IterationDecision.SUCCESS,
            loop.decideAfterIteration(1, Map.of(
                "check",
                Map.of("status", "DONE")
            ))
        );
        assertEquals(
            IterationDecision.CONTINUE,
            loop.decideAfterIteration(1, Map.of(
                "check",
                Map.of("status", "WAIT")
            ))
        );
        assertEquals(
            IterationDecision.FAILURE,
            loop.decideAfterIteration(3, Map.of(
                "check",
                Map.of("status", "WAIT")
            ))
        );
    }

    /**
     * 创建带具体 status 返回类型的检查任务。
     * @param type STRING 选择字符串输出，其他值选择整数输出
     * @return 用于本测试定义校验的具体任务
     */
    private static org.cses.flow.core.domains.tasks.Task checkTask(DataType type) {
        return type == DataType.STRING
            ? org.cses.flow.core.plugins.TestOutputTasks.Status.builder().id("check-id").key("check").build()
            : org.cses.flow.core.plugins.TestOutputTasks.NumericStatus.builder().id("check-id").key("check").build();
    }
}
