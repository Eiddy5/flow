package org.cses.flow.executor;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.VoidOutput;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.executor.handlers.ExecutorEventMessageHandler;
import org.cses.flow.worker.WorkerDispatcher;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorEventMessageHandlerTest {

    private static AtomicReference<RunIdentity> CAPTURED_RUN =
        new AtomicReference<>();
    private static Context PLUGINS = builtInContext(
        new ParentTaskRunRecordingTask(), new EchoTask(), new SummarySequence()
    );

    @Test
    void acceptsExecutionWithDifferentCompanyWhenFlowReferenceMatches() {
        Flow flow = PLUGINS.deploy(
            "flow-company",
            "company-independent-flow",
            Map.of(
                "key", "company-independent-flow",
                "tasks", List.of(Map.of(
                    "key", "task",
                    "type", org.cses.flow.extensions.log.Log.class.getName(), "message", "test step"
                ))
            ),
            null,
            ActorRef.create("executor-user", "Executor User"),
            1_785_312_000_000L
        );
        Execution execution = Execution.create(
            null,
            session("execution-company"),
            flow.key(),
            flow.reversion(),
            Map.of()
        );

        ExecutorContext context = new ExecutorContext(flow, execution);

        assertEquals(flow, context.flow());
        assertEquals(execution, context.execution());
    }

    @Test
    void persistsAndDispatchesUntilTheExecutionIsStable() {
        Flow flow = PLUGINS.deploy(
            "default-executor-company",
            "default-executor-flow",
            Map.of(
                "key", "default-executor",
                "tasks", List.of(
                    Map.of(
                        "key", "prepare",
                        "type", org.cses.flow.extensions.log.Log.class.getName(), "message", "test step"
                    ),
                    Map.of(
                        "key", "finish",
                        "type", org.cses.flow.extensions.log.Log.class.getName(), "message", "test step"
                    )
                )
            ),
            null,
            ActorRef.create("executor-user", "Executor User"),
            1_785_312_000_000L
        );
        Execution execution = Execution.create(
            null,
            session(flow.companyId()),
            flow.key(),
            flow.reversion(),
            Map.of()
        );
        ExecutorContext context = new ExecutorContext(flow, execution);
        TestExecutionRepository repository = new TestExecutionRepository();
        ExecutorEventMessageHandler eventHandler =
            new ExecutorEventMessageHandler(
            repository,
            new ExecutorService(),
            new WorkerDispatcher()
        );
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

        Execution completed = eventHandler.execute(
            session(flow.companyId()),
            dsl,
            context
        );

        assertTrue(completed.state().is(State.Type.SUCCESS));
        assertEquals(2, completed.taskRuns().size());
        assertTrue(completed.taskRuns().stream()
            .allMatch(taskRun ->
                taskRun.state().is(State.Type.SUCCESS)
            ));
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.SUCCESS
            ),
            context.states()
        );
        assertTrue(context.nexts().isEmpty());
        assertTrue(context.workerTasks().isEmpty());

        Execution persisted = repository.findById(
            dsl,
            flow.companyId(),
            execution.id()
        ).orElseThrow();
        assertEquals(completed.state(), persisted.state());
        assertEquals(
            completed.taskRuns().stream().map(taskRun -> taskRun.id()).toList(),
            persisted.taskRuns().stream().map(taskRun -> taskRun.id()).toList()
        );
    }

    /** 通过真实 Executor 和 Worker 将 Runnable 与编排输出送入后续 RunContext。 */
    @Test
    void typedOutputsReachFollowingRunContextsWithoutOutputConfiguration() {
        Flow flow = PLUGINS.deploy(
            "typed-company", "typed-flow",
            Map.of("key", "typed-flow", "tasks", List.of(
                Map.of("key", "stage", "type", SummarySequence.class.getCanonicalName(),
                    "tasks", List.of(Map.of("key", "prepare", "type",
                        org.cses.flow.core.plugins.TestOutputTasks.Decision.class.getCanonicalName()))),
                Map.of("key", "consume", "type", EchoTask.class.getCanonicalName())
            )), null, ActorRef.create("user", "User"), 1L);
        Execution execution = Execution.create(null, session(flow.companyId()),
            flow.key(), flow.reversion(), Map.of());
        ExecutorEventMessageHandler handler = new ExecutorEventMessageHandler(
            new TestExecutionRepository(), new ExecutorService(), new WorkerDispatcher());
        Execution completed = handler.execute(session(flow.companyId()), DSL.using(SQLDialect.POSTGRES),
            new ExecutorContext(flow, execution));
        assertEquals(State.Type.SUCCESS, completed.state().current());
        assertEquals(3, completed.taskRuns().size());
        for (TaskRun run : completed.taskRuns()) {
            assertEquals(Map.of("decision", "approved"), run.outputs());
        }
        assertTrue(completed.activeTaskRuns().isEmpty());
    }

    @Test
    void handlesPauseBranchWithoutCreatingAWorkerTask() {
        Flow flow = PLUGINS.deploy(
            "default-executor-company",
            "pause-branch-flow",
            Map.of(
                "key", "pause-branch",
                "tasks", List.of(Map.of(
                    "key", "wait-confirmation",
                    "type", org.cses.flow.extensions.flow.Pause.class.getName(),
                    "onPause", Map.of(
                        "key", "create-confirmation",
                        "type", org.cses.flow.extensions.log.Log.class.getName(), "message", "test step"
                    )
                ))
            ),
            null,
            ActorRef.create("executor-user", "Executor User"),
            1_785_312_000_000L
        );
        Execution execution = Execution.create(
            null,
            session(flow.companyId()),
            flow.key(),
            flow.reversion(),
            Map.of()
        );
        ExecutorContext context = new ExecutorContext(flow, execution);
        TestExecutionRepository executionRepository =
            new TestExecutionRepository();
        ExecutorEventMessageHandler eventHandler =
            new ExecutorEventMessageHandler(
            executionRepository,
            new ExecutorService(),
            new WorkerDispatcher()
        );
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

        Execution waiting = eventHandler.execute(
            session(flow.companyId()),
            dsl,
            context
        );

        assertTrue(waiting.state().is(State.Type.PAUSED));
        assertEquals(2, waiting.taskRuns().size());
        assertTrue(waiting.taskRuns().getFirst().state().is(
            State.Type.PAUSED
        ));
        assertTrue(waiting.taskRuns().getLast().state().is(
            State.Type.SUCCESS
        ));
        assertTrue(context.workerTasks().isEmpty());
        assertEquals(
            waiting.taskRuns().getFirst().id(),
            waiting.pausedTaskRuns().getFirst().id()
        );
    }

    @Test
    void exposesTheOuterPauseRunToItsNestedRunnableAction() {
        CAPTURED_RUN.set(null);
        Flow flow = PLUGINS.deploy(
            "default-executor-company",
            "pause-parent-run-flow",
            Map.of(
                "key", "pause-parent-run",
                "tasks", List.of(Map.of(
                    "key", "wait-confirmation",
                    "type", org.cses.flow.extensions.flow.Pause.class.getName(),
                    "onPause", Map.of(
                        "key", "record-parent-run",
                        "type",
                        ParentTaskRunRecordingTask.class.getCanonicalName()
                    )
                ))
            ),
            null,
            ActorRef.create("executor-user", "Executor User"),
            1_785_312_000_000L
        );
        Execution execution = Execution.create(
            null,
            session(flow.companyId()),
            flow.key(),
            flow.reversion(),
            Map.of()
        );
        ExecutorEventMessageHandler executor =
            new ExecutorEventMessageHandler(
            new TestExecutionRepository(),
            new ExecutorService(),
            new WorkerDispatcher()
        );

        Execution waiting = executor.execute(
            session(flow.companyId()),
            DSL.using(SQLDialect.POSTGRES),
            new ExecutorContext(flow, execution)
        );

        TaskRun pauseRun = waiting.taskRuns().stream()
            .filter(taskRun -> taskRun.parentId().isEmpty())
            .findFirst()
            .orElseThrow();
        TaskRun actionRun = waiting.taskRuns().stream()
            .filter(taskRun -> taskRun.parentId().isPresent())
            .findFirst()
            .orElseThrow();
        assertEquals(pauseRun.id(), actionRun.parentId().orElseThrow());
        assertEquals(
            RunIdentity.from(actionRun.id(), pauseRun.id()),
            CAPTURED_RUN.get()
        );
    }

    private static class TestExecutionRepository
        implements ExecutionRepository {

        private Map<ExecutionKey, Execution> executions =
            new HashMap<>();

        @Override
        public Optional<Execution> findById(
            DSLContext dsl,
            String companyId,
            String executionId
        ) {
            return stored(companyId, executionId);
        }

        @Override
        public List<Execution> findAll(
            DSLContext dsl,
            String companyId
        ) {
            return executions.entrySet().stream()
                .filter(entry -> entry.getKey().companyId().equals(companyId))
                .map(entry -> entry.getValue().copy())
                .sorted(java.util.Comparator.comparing(Execution::id))
                .toList();
        }

        @Override
        public long count(DSLContext dsl, String companyId) {
            return executions.keySet().stream()
                .filter(key -> key.companyId().equals(companyId))
                .count();
        }

        @Override
        public void save(DSLContext dsl, Execution execution) {
            executions.put(
                ExecutionKey.from(
                    execution.companyId(),
                    execution.id()
                ),
                execution.copy()
            );
        }

        /**
         * 适配双快照签名的替身只用于状态机测试；两次普通保存不验证原子提交。
         * @param dsl 测试上下文
         * @param source 原实例快照
         * @param derived 新实例快照
         */
        @Override
        public void save(DSLContext dsl, Execution source, Execution derived) {
            save(dsl, source);
            save(dsl, derived);
        }

        /**
         * 返回当前租户内同源的独立副本。
         * @param dsl 测试上下文
         * @param companyId 目标租户
         * @param originId 最初实例编号
         * @return 同源快照，无记录时为空
         */
        @Override
        public List<Execution> findByOriginId(DSLContext dsl, String companyId, String originId) {
            return findAll(dsl, companyId).stream()
                .filter(execution -> execution.origin().originId().equals(originId)).toList();
        }

        private Optional<Execution> stored(
            String companyId,
            String executionId
        ) {
            Execution execution = executions.get(
                ExecutionKey.from(companyId, executionId)
            );
            return execution == null
                ? Optional.empty()
                : Optional.of(execution.copy());
        }
    }

    private record ExecutionKey(String companyId, String executionId) {

        private static ExecutionKey from(
            String companyId,
            String executionId
        ) {
            return new ExecutionKey(companyId, executionId);
        }

        private ExecutionKey {
            Objects.requireNonNull(companyId);
            Objects.requireNonNull(executionId);
        }
    }

    private record RunIdentity(String taskRunId, String parentTaskRunId) {

        private static RunIdentity from(
            String taskRunId,
            String parentTaskRunId
        ) {
            return new RunIdentity(taskRunId, parentTaskRunId);
        }
    }

    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class ParentTaskRunRecordingTask
        extends Task implements RunnableTask<VoidOutput> {

        @Override
        public VoidOutput run(RunContext context) {
            CAPTURED_RUN.set(RunIdentity.from(
                context.taskRunInfo().id(),
                context.parentTaskRunId().orElseThrow()
            ));
            return VoidOutput.from();
        }
    }

    private static Session<User> session(String companyId) {
        User user = new User();
        user.setId("default-executor-user");
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setUser(user);
        return session;
    }

    /** 从具体编排输出中读取结果的后续任务。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class EchoTask extends Task implements RunnableTask<DecisionOutput> {
        /**
         * 读取上一个编排的具体业务结果。
         * @param context 当前任务只读运行上下文
         * @return 前一步输出在本次上下文中的实际值
         */
        @Override
        public DecisionOutput run(RunContext context) {
            return DecisionOutput.from(context.render(
                org.cses.flow.core.domains.expressions.TemplateExpression.parse("{{ outputs.stage.decision }}")));
        }
    }

    /** 子任务完成后生成具体汇总结果的编排样本。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class SummarySequence extends org.cses.flow.extensions.flow.Branch<DecisionOutput> {
        /** @return 等待子任务全部完成后再生成自身输出 */
        @Override
        public boolean holdsTaskRunUntilChildrenSettle() {
            return true;
        }
        /**
         * 将子任务结果转为本编排的具体输出。
         * @param context 子任务完成后的只读运行上下文
         * @return 实际读取到的前置决定
         */
        @Override
        public DecisionOutput outputs(RunContext context) {
            return DecisionOutput.from(context.render(
                org.cses.flow.core.domains.expressions.TemplateExpression.parse("{{ outputs.prepare.decision }}")));
        }
    }

    /** 下游能够读取的明确决定字段。 */
    public record DecisionOutput(String decision) implements org.cses.flow.core.domains.tasks.Output {
        /**
         * 创建观察到的决定结果。
         * @param decision 只读的实际决定值
         * @return 包含该值的新输出
         */
        public static DecisionOutput from(String decision) {
            return new DecisionOutput(decision);
        }
    }
}
