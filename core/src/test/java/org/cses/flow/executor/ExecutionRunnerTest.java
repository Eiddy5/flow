package org.cses.flow.executor;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
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

final class ExecutionRunnerTest {

    private static final AtomicReference<RunIdentity> CAPTURED_RUN =
        new AtomicReference<>();
    private static final Context PLUGINS = builtInContext(
        new ParentTaskRunRecordingTask()
    );

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
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    ),
                    Map.of(
                        "key", "finish",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    )
                )
            ),
            null,
            ActorRef.create("executor-user", "Executor User"),
            1_785_312_000_000L
        );
        Execution execution = Execution.create(
            flow.companyId(),
            flow.id(),
            flow.reversion()
        );
        ExecutorContext context = new ExecutorContext(flow, execution);
        TestExecutionRepository repository = new TestExecutionRepository();
        ExecutionRunner executionRunner = new ExecutionRunner(
            repository,
            new ExecutorService(),
            new WorkerDispatcher()
        );
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

        Execution completed = executionRunner.execute(
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
                    "pause", Map.of(
                        "key", "create-confirmation",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    )
                ))
            ),
            null,
            ActorRef.create("executor-user", "Executor User"),
            1_785_312_000_000L
        );
        Execution execution = Execution.create(
            flow.companyId(),
            flow.id(),
            flow.reversion()
        );
        ExecutorContext context = new ExecutorContext(flow, execution);
        TestExecutionRepository executionRepository =
            new TestExecutionRepository();
        ExecutionRunner executionRunner = new ExecutionRunner(
            executionRepository,
            new ExecutorService(),
            new WorkerDispatcher()
        );
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

        Execution waiting = executionRunner.execute(
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
                    "pause", Map.of(
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
            flow.companyId(),
            flow.id(),
            flow.reversion()
        );
        ExecutionRunner executor = new ExecutionRunner(
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
            new RunIdentity(actionRun.id(), pauseRun.id()),
            CAPTURED_RUN.get()
        );
    }

    private static final class TestExecutionRepository
        implements ExecutionRepository {

        private final Map<ExecutionKey, Execution> executions =
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
        public Optional<Execution> lockById(
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
                new ExecutionKey(execution.companyId(), execution.id()),
                execution.copy()
            );
        }

        private Optional<Execution> stored(
            String companyId,
            String executionId
        ) {
            Execution execution = executions.get(
                new ExecutionKey(companyId, executionId)
            );
            return execution == null
                ? Optional.empty()
                : Optional.of(execution.copy());
        }
    }

    private record ExecutionKey(String companyId, String executionId) {
        private ExecutionKey {
            Objects.requireNonNull(companyId);
            Objects.requireNonNull(executionId);
        }
    }

    private record RunIdentity(String taskRunId, String parentTaskRunId) {
    }

    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static final class ParentTaskRunRecordingTask
        extends Task implements RunnableTask {

        @Override
        public RunResult run(RunContext context) {
            CAPTURED_RUN.set(new RunIdentity(
                context.taskRunId(),
                context.parentTaskRunId().orElseThrow()
            ));
            return RunResult.success(Map.of());
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
}
