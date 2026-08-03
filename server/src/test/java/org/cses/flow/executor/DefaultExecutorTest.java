package org.cses.flow.executor;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.repositories.executions.memory.InMemoryExecutionRepository;
import org.cses.flow.core.repositories.externaltasks.memory.InMemoryExternalTaskRepository;
import org.cses.flow.worker.WorkerDispatcher;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskExtensionTestSupport.builtInDispatcher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DefaultExecutorTest {

    @Test
    void persistsAndDispatchesUntilTheExecutionIsStable() {
        Flow flow = Flow.deploy(
            "default-executor-company",
            "default-executor-flow",
            Map.of(
                "key", "default-executor",
                "tasks", List.of(
                    Map.of(
                        "key", "prepare",
                        "type", "AUTO"
                    ),
                    Map.of(
                        "key", "finish",
                        "type", "AUTO"
                    )
                )
            ),
            null,
            builtInDispatcher(),
            ActorRef.create("executor-user", "Executor User"),
            1_785_312_000_000L
        );
        Execution execution = Execution.create(
            flow.companyId(),
            flow.id(),
            flow.reversion()
        );
        ExecutorContext context = new ExecutorContext(flow, execution);
        InMemoryExecutionRepository repository =
            new InMemoryExecutionRepository();
        DefaultExecutor defaultExecutor = new DefaultExecutor(
            repository,
            new ExecutorService(),
            new WorkerDispatcher(),
            new InMemoryExternalTaskRepository()
        );
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

        Execution completed = defaultExecutor.execute(
            session(flow.companyId()),
            dsl,
            context
        );

        assertTrue(completed.state().is(State.Type.COMPLETED));
        assertEquals(2, completed.taskRuns().size());
        assertTrue(completed.taskRuns().stream()
            .allMatch(taskRun ->
                taskRun.state().is(State.Type.COMPLETED)
            ));
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.COMPLETED
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
        Flow flow = Flow.deploy(
            "default-executor-company",
            "pause-branch-flow",
            Map.of(
                "key", "pause-branch",
                "tasks", List.of(Map.of(
                    "key", "wait-confirmation",
                    "type", "PAUSE"
                ))
            ),
            null,
            builtInDispatcher(),
            ActorRef.create("executor-user", "Executor User"),
            1_785_312_000_000L
        );
        Execution execution = Execution.create(
            flow.companyId(),
            flow.id(),
            flow.reversion()
        );
        ExecutorContext context = new ExecutorContext(flow, execution);
        InMemoryExecutionRepository executionRepository =
            new InMemoryExecutionRepository();
        InMemoryExternalTaskRepository externalTaskRepository =
            new InMemoryExternalTaskRepository();
        DefaultExecutor defaultExecutor = new DefaultExecutor(
            executionRepository,
            new ExecutorService(),
            new WorkerDispatcher(),
            externalTaskRepository
        );
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

        Execution waiting = defaultExecutor.execute(
            session(flow.companyId()),
            dsl,
            context
        );

        assertTrue(waiting.state().is(State.Type.WAITING));
        assertEquals(1, waiting.taskRuns().size());
        assertTrue(waiting.taskRuns().getFirst().state().is(
            State.Type.WAITING
        ));
        assertTrue(context.workerTasks().isEmpty());
        assertTrue(context.branchTaskRuns().isEmpty());
        assertEquals(
            waiting.taskRuns().getFirst().id(),
            externalTaskRepository.findWaiting(
                dsl,
                flow.companyId()
            ).getFirst().taskRunId()
        );
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
