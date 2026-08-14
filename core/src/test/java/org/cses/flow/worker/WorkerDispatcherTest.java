package org.cses.flow.worker;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.flow.Pause;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            workerTask
        );

        assertEquals(State.Type.SUCCESS, result.targetState());
        assertEquals(Map.of(), result.outputs());
    }

    @Test
    void propagatesUnexpectedTaskException() {
        FailingTask task = FailingTask.builder()
            .id("task-1")
            .key("failing")
            .build();

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> dispatcher.dispatch(
                new Session<User>(),
                new WorkerTask(
                    "execution-1",
                    "task-run-1",
                    task,
                    Map.of()
                )
            )
        );

        assertEquals("unexpected-task-failure", exception.getMessage());
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
        Execution execution = execution(
            "execution-1",
            Map.of("amount", 1200)
        );
        WorkerTask firstWorkerTask = new WorkerTask(
            "execution-1",
            "task-run-1",
            "parent-run-1",
            task,
            sourceInputs,
            Map.of(RunContext.EXECUTION_VARIABLE, execution)
        );
        WorkerTask secondWorkerTask = new WorkerTask(
            "execution-1",
            "task-run-2",
            task,
            sourceInputs,
            Map.of(RunContext.EXECUTION_VARIABLE, execution)
        );
        sourceInputs.put("payload", "changed");
        Session<User> session = new Session<>();

        WorkerTaskResult first = dispatcher.dispatch(
            session,
            firstWorkerTask
        );
        dispatcher.dispatch(session, secondWorkerTask);

        assertEquals(
            Map.of("payload", "original"),
            first.outputs()
        );
        assertEquals(2, task.contexts.size());
        assertNotSame(task.contexts.get(0), task.contexts.get(1));
        assertSame(session, task.contexts.getFirst().session());
        assertEquals("execution-1", task.contexts.getFirst().executionId());
        assertEquals("task-run-1", task.contexts.getFirst().taskRunId());
        assertEquals("task-run-2", task.contexts.get(1).taskRunId());
        assertEquals(
            "parent-run-1",
            task.contexts.getFirst().parentTaskRunId().orElseThrow()
        );
        assertTrue(task.contexts.get(1).parentTaskRunId().isEmpty());
        assertSame(
            execution,
            task.contexts.getFirst().variables().get(
                RunContext.EXECUTION_VARIABLE
            )
        );
        assertEquals(
            "task-run-1",
            task.contexts.getFirst().variables().get(
                RunContext.TASK_RUN_ID_VARIABLE
            )
        );
        assertEquals(
            Map.of("payload", "original"),
            task.contexts.getFirst().variables().get(
                RunContext.TASK_INPUTS_VARIABLE
            )
        );
        assertEquals(
            Map.of("amount", 1200),
            task.contexts.getFirst().inputs()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> task.contexts.getFirst().variables().put(
                "custom",
                "value"
            )
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> task.contexts.getFirst().taskInputs().put(
                "payload",
                "mutated"
            )
        );
    }

    @Test
    void rejectsCallerProvidedTaskRunIdentityVariable() {
        AutomaticTask task = AutomaticTask.builder()
            .id("task-1")
            .key("automatic")
            .build();

        assertThrows(
            IllegalArgumentException.class,
            () -> new WorkerTask(
                "execution-1",
                "task-run-1",
                task,
                Map.of(),
                Map.of(
                    RunContext.TASK_RUN_ID_VARIABLE,
                    "spoofed-task-run"
                )
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new WorkerTask(
                "execution-1",
                "task-run-1",
                task,
                Map.of(),
                Map.of(
                    RunContext.PARENT_TASK_RUN_ID_VARIABLE,
                    "spoofed-parent-run"
                )
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
            return RunResult.success(Map.of(
                "payload",
                context.taskInputs().get("payload")
            ));
        }
    }

    @SuperBuilder
    @NoArgsConstructor
    private static final class FailingTask
        extends Task implements RunnableTask {

        @Override
        public RunResult run(RunContext context) {
            throw new IllegalStateException("unexpected-task-failure");
        }
    }

    private static Execution execution(
        String id,
        Map<String, ?> inputs
    ) {
        return Execution.rehydrate(
            id,
            "company-1",
            "flow-1",
            1,
            inputs,
            State.created(),
            0,
            List.of()
        );
    }
}
