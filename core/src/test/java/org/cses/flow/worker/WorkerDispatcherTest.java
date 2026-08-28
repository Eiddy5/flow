package org.cses.flow.worker;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerDispatcherTest {

    private static final Context PLUGINS = builtInContext(
        new TestNotificationTask()
    );
    private WorkerDispatcher dispatcher = new WorkerDispatcher();

    @Test
    void directlyInvokesTheRunnableTask() {
        AutomaticTask task = AutomaticTask.builder()
            .id("task-1")
            .key("automatic")
            .build();
        WorkerTask workerTask = workerTask(
            "execution-1",
            "task-run-1",
            null,
            task,
            Map.of("payload", "value")
        );

        WorkerTaskResult result = dispatcher.dispatch(workerTask);

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
            () -> dispatcher.dispatch(workerTask(
                "execution-1",
                "task-run-1",
                null,
                task,
                Map.of()
            ))
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
            () -> WorkerTask.from(
                "execution-1",
                "task-run-1",
                task,
                variables(
                    "execution-1",
                    "task-run-1",
                    null,
                    task,
                    Map.of()
                )
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

        WorkerTaskResult result = dispatcher.dispatch(workerTask(
            "execution-1",
            "task-run-1",
            null,
            task,
            Map.of()
        ));

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
        WorkerTask firstWorkerTask = workerTask(
            "execution-1",
            "task-run-1",
            "parent-run-1",
            task,
            sourceInputs
        );
        WorkerTask secondWorkerTask = workerTask(
            "execution-1",
            "task-run-2",
            null,
            task,
            sourceInputs
        );
        sourceInputs.put("payload", "changed");

        WorkerTaskResult first = dispatcher.dispatch(firstWorkerTask);
        dispatcher.dispatch(secondWorkerTask);

        assertEquals(Map.of("payload", "original"), first.outputs());
        assertEquals(2, task.contexts.size());
        assertNotSame(task.contexts.get(0), task.contexts.get(1));
        assertEquals(
            "execution-1",
            task.contexts.getFirst().taskRunInfo().executionId()
        );
        assertEquals(
            "task-run-1",
            task.contexts.getFirst().taskRunInfo().id()
        );
        assertEquals("task-run-2", task.contexts.get(1).taskRunInfo().id());
        assertEquals(
            "task-1",
            task.contexts.getFirst().taskRunInfo().taskId()
        );
        assertEquals(
            "record-context",
            task.contexts.getFirst().taskRunInfo().taskKey()
        );
        assertEquals(
            "parent-run-1",
            task.contexts.getFirst().parentTaskRunId().orElseThrow()
        );
        assertTrue(task.contexts.get(1).parentTaskRunId().isEmpty());
        assertEquals(
            "execution-1",
            map(task.contexts.getFirst().variables(), "execution").get("id")
        );
        assertEquals(
            "task-run-1",
            map(task.contexts.getFirst().variables(), "taskRun").get("id")
        );
        assertEquals(
            Map.of("payload", "original"),
            task.contexts.getFirst().taskInputs()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> task.contexts.getFirst().variables().put("custom", "value")
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
    void rejectsVariableIdentitiesThatDoNotMatchTheEnvelope() {
        AutomaticTask task = AutomaticTask.builder()
            .id("task-1")
            .key("automatic")
            .build();

        IllegalArgumentException taskRunFailure = assertThrows(
            IllegalArgumentException.class,
            () -> WorkerTask.from(
                "execution-1",
                "task-run-1",
                task,
                variables(
                    "execution-1",
                    "spoofed-task-run",
                    null,
                    task,
                    Map.of()
                )
            )
        );
        assertTrue(taskRunFailure.getMessage().contains(
            "spoofed-task-run != task-run-1"
        ));

        assertThrows(
            IllegalArgumentException.class,
            () -> WorkerTask.from(
                "execution-1",
                "task-run-1",
                task,
                variables(
                    "execution-1",
                    "task-run-1",
                    "spoofed-parent-run",
                    task,
                    Map.of()
                )
            )
        );
    }

    @Test
    void rejectsAnExecutionIdentityThatDoesNotMatchTheEnvelope() {
        AutomaticTask task = AutomaticTask.builder()
            .id("task-1")
            .key("automatic")
            .build();

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> WorkerTask.from(
                "execution-1",
                "task-run-1",
                task,
                variables(
                    "execution-2",
                    "task-run-1",
                    null,
                    task,
                    Map.of()
                )
            )
        );

        assertEquals(
            "Worker identity does not match variables: "
                + "execution-2 != execution-1",
            failure.getMessage()
        );
    }

    private static WorkerTask workerTask(
        String executionId,
        String taskRunId,
        String parentTaskRunId,
        Task task,
        Map<String, ?> taskInputs
    ) {
        Map<String, Object> variables = variables(
            executionId,
            taskRunId,
            parentTaskRunId,
            task,
            taskInputs
        );
        return parentTaskRunId == null
            ? WorkerTask.from(executionId, taskRunId, task, variables)
            : WorkerTask.from(
                executionId,
                taskRunId,
                parentTaskRunId,
                task,
                variables
            );
    }

    private static Map<String, Object> variables(
        String executionId,
        String taskRunId,
        String parentTaskRunId,
        Task task,
        Map<String, ?> taskInputs
    ) {
        Map<String, Object> parent = parentTaskRunId == null
            ? Map.of()
            : Map.of(
                "task",
                Map.of("key", "parent", "type", "test"),
                "taskRun",
                Map.of(
                    "id",
                    parentTaskRunId,
                    "inputs",
                    Map.of(),
                    "outputs",
                    Map.of()
                )
            );
        return Map.of(
            "inputs", Map.of("amount", 1200),
            "outputs", Map.of(),
            "vars", Map.of(),
            "task", Map.of(
                "id", task.id(),
                "key", task.key(),
                "type", task.getType()
            ),
            "taskRun", Map.of(
                "id", taskRunId,
                "inputs", taskInputs,
                "outputs", Map.of()
            ),
            "execution", Map.of("id", executionId, "outputs", Map.of()),
            "parent", parent,
            "parents", parent.isEmpty() ? List.of() : List.of(parent)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(
        Map<String, ?> source,
        String key
    ) {
        return (Map<String, Object>) source.get(key);
    }

    @SuperBuilder
    @NoArgsConstructor
    private static class ContextRecordingTask
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
    private static class FailingTask
        extends Task implements RunnableTask {

        @Override
        public RunResult run(RunContext context) {
            throw new IllegalStateException("unexpected-task-failure");
        }
    }
}
