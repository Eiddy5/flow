package org.cses.flow.extensions.flow;

import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.conditions.Condition;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.runner.OrchestrationContext;
import org.paas.session.Session;
import org.paas.session.User;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.extensions.log.Log;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopUntilTest {

    private Context plugins = builtInContext();

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

    /** 当前轮结果分别生成成功、下一轮或次数上限失败的只读计划。 */
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

        assertIterationPlan(loop, 1, "DONE", State.Type.SUCCESS);
        assertIterationPlan(loop, 1, "WAIT", null);
        assertIterationPlan(loop, 3, "WAIT", State.Type.FAILED);
    }

    /**
     * 从真实轮次和子结果解析计划，检查解析不推进聚合。
     * @param loop 待验证循环定义
     * @param iteration 当前已完成轮次
     * @param status 子任务实际输出
     * @param expectedState null 表示继续循环，否则为收敛状态
     */
    private void assertIterationPlan(LoopUntil loop, int iteration, String status, State.Type expectedState) {
        User user = new User();
        user.setId("loop-user");
        user.setCompanyId("loop-company");
        Session<User> session = new Session<>();
        session.setUser(user);
        session.setCompanyId("loop-company");
        Flow flow = Flow.create(session, "loop-flow", "循环协议测试", Map.of(), List.of(), List.of(),
                List.of(loop), "loop fixture");
        flow = Flow.rehydrate(flow.id(), flow.companyId(), flow.key(), false, 1L,
                flow.description(), flow.variables(), flow.inputs(), flow.outputs(), flow.tasks(),
                flow.status(), flow.creator(), flow.updater(), null, flow.createdAt(), flow.updatedAt(), null,
                flow.source());
        Execution execution = Execution.create(null, session, flow.key(), 1, Map.of());
        TaskRun scope = TaskRun.create(loop.id(), null, Map.of());
        execution.startWithTaskRuns(List.of(scope));
        execution.startTaskRun(scope.id());
        execution.startTaskRunGeneration(scope.id(), "INITIAL");
        for (int current = 1; current < iteration; current++) {
            execution.advanceTaskRunGeneration(scope.id(), "NEXT");
        }
        TaskRun check = TaskRun.create("check-id", scope.id(), Map.of(), iteration);
        execution.addTaskRuns(List.of(check));
        execution.startTaskRun(check.id());
        execution.succeedTaskRun(check.id(), Map.of("status", status));
        OrchestrationContext context = OrchestrationContext.from(flow, execution, loop,
                execution.requireTaskRun(scope.id()));
        var nexts = loop.resolveNexts(context);
        assertEquals(iteration, context.iteration());
        assertEquals(2, execution.taskRuns().size());
        assertEquals(State.Type.RUNNING, execution.requireTaskRun(scope.id()).state().current());
        if (expectedState == null) {
            assertTrue(loop.resolveState(context).isEmpty());
            assertEquals(1, nexts.size());
            assertEquals("check-id", nexts.getFirst().task().id());
            assertEquals(scope.id(), nexts.getFirst().taskRun().parentId().orElseThrow());
            assertEquals(iteration + 1, nexts.getFirst().taskRun().iteration().orElseThrow());
            assertEquals(State.Type.CREATED, nexts.getFirst().taskRun().state().current());
        } else {
            assertTrue(nexts.isEmpty());
            if (expectedState == State.Type.FAILED) {
                var error = assertThrows(org.cses.flow.core.exceptions.WorkflowException.class,
                    () -> loop.resolveState(context));
                assertTrue(error.getMessage().contains("3 iterations"));
            } else {
                assertEquals(expectedState, loop.resolveState(context).orElseThrow());
            }
        }
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
