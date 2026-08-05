package org.cses.flow.worker;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.flow.Pause;
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

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WorkerDispatcherTest {

    private static final Context PLUGINS = builtInContext(
        new TestNotificationTask()
    );
    private final WorkerDispatcher dispatcher = new WorkerDispatcher();

    @Test
    void directlyInvokesTheRunnableTask() {
        AutomaticTask task = AutomaticTask.builder()
            .id("task-1")
            .key("automatic")
            .build();
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
    void rejectsOrchestrationTasksBeforeWorkerDispatch() {
        Pause task = Pause.builder()
            .id("task-1")
            .key("pause")
            .pause(AutomaticTask.builder()
                .id("action-1")
                .key("create-pause")
                .build())
            .build();

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
    void dispatchesAMaterializedPluginUsingItsCustomField() {
        Flow flow = PLUGINS.deploy(
            "worker-company",
            "worker-flow",
            Map.of(
                "key", "notification-flow",
                "tasks", List.of(Map.of(
                    "key", "notify",
                    "type",
                    TestNotificationTask.class.getCanonicalName(),
                    "channel", "operations",
                    "outputs", List.of(Map.of(
                        "key", "channel",
                        "type", "STRING"
                    ))
                ))
            ),
            null,
            ActorRef.create("worker-user", "Worker User"),
            1_785_312_000_000L
        );
        TestNotificationTask task = assertInstanceOf(
            TestNotificationTask.class,
            flow.tasks().getFirst()
        );

        WorkerTaskResult result = dispatcher.dispatch(
            new Session<User>(),
            DSL.using(SQLDialect.POSTGRES),
            new WorkerTask(
                "execution-1",
                "task-run-1",
                task,
                Map.of()
            )
        );

        assertEquals(
            Map.of("channel", "operations"),
            result.outputs()
        );
    }

    @Test
    void createsOneFreshRunContextPerRunnableInvocation() {
        ContextRecordingTask task = ContextRecordingTask.builder()
            .id("task-1")
            .key("record-context")
            .build();
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

    @SuperBuilder
    @NoArgsConstructor
    private static final class ContextRecordingTask
        extends Task implements RunnableTask {

        @Builder.Default
        private List<RunContext> contexts = new ArrayList<>();

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
