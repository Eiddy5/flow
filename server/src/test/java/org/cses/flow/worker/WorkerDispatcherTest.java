package org.cses.flow.worker;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.tasks.PauseTask;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WorkerDispatcherTest {

    private final WorkerDispatcher dispatcher = new WorkerDispatcher();

    @Test
    void directlyInvokesTheRunnableTask() {
        AutomaticTask task = AutomaticTask.create(
            "task-1",
            null,
            "automatic",
            List.of(),
            List.of(),
            RouteExpression.direct(),
            List.of(),
            List.of()
        );
        WorkerTask workerTask = new WorkerTask(
            "execution-1",
            "task-run-1",
            task,
            Map.of("payload", "value")
        );

        WorkerTaskResult result = dispatcher.dispatch(
            new Session<User>(),
            DSL.using(SQLDialect.POSTGRES),
            workerTask
        );

        assertEquals(State.Type.COMPLETED, result.targetState());
        assertEquals(Map.of(), result.outputs());
    }

    @Test
    void rejectsBranchTasksBeforeWorkerDispatch() {
        PauseTask task = PauseTask.create(
            "task-1",
            null,
            "pause",
            List.of(),
            List.of(),
            RouteExpression.direct(),
            List.of(),
            List.of()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new WorkerTask(
                "execution-1",
                "task-run-1",
                task,
                Map.of()
            )
        );
    }

    @Test
    void createsOneFreshRunContextPerRunnableInvocation() {
        ContextRecordingTask task = new ContextRecordingTask();
        Map<String, Object> sourceInputs = new LinkedHashMap<>();
        sourceInputs.put("payload", "original");
        WorkerTask workerTask = new WorkerTask(
            "execution-1",
            "task-run-1",
            task,
            sourceInputs
        );
        sourceInputs.put("payload", "changed");
        Session<User> session = new Session<>();
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);

        WorkerTaskResult first = dispatcher.dispatch(
            session,
            dsl,
            workerTask
        );
        dispatcher.dispatch(session, dsl, workerTask);

        assertEquals(
            Map.of("payload", "original"),
            first.outputs()
        );
        assertEquals(2, task.contexts.size());
        assertNotSame(task.contexts.get(0), task.contexts.get(1));
        assertSame(session, task.contexts.getFirst().session());
        assertSame(dsl, task.contexts.getFirst().dsl());
        assertThrows(
            UnsupportedOperationException.class,
            () -> task.contexts.getFirst().inputs().put(
                "payload",
                "mutated"
            )
        );
    }

    private static final class ContextRecordingTask
        extends Task implements RunnableTask {

        private final List<RunContext> contexts = new ArrayList<>();

        private ContextRecordingTask() {
            super(
                "task-1",
                null,
                "record-context",
                "TEST_RUN_CONTEXT",
                List.of(),
                List.of(),
                RouteExpression.direct(),
                List.of(),
                List.of()
            );
        }

        @Override
        public RunResult run(RunContext context) {
            contexts.add(context);
            return RunResult.completed(Map.of(
                "payload",
                context.inputs().get("payload")
            ));
        }
    }
}
