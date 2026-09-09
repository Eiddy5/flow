package org.cses.flow.core.services.flows;

import com.example.flow.inputs.BusinessCodeInput;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.flows.inputs.StringInput;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.executions.WorkflowUcFixture.PausedTaskRunRef;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.extensions.flow.Pause;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UC: docs/uc/flow/UC-12 用户在 Flow 中使用业务输入类型.md */
class Uc12BusinessInputsTest {

    /** Publishes, requeries and completes a Flow using business inputs in three positions. */
    @Test
    void s1UserPublishesAndRunsBusinessInputsAlongsideBuiltInInputs() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow published = fixture.deploy(fullFlowYaml("uc12-s1"));
            String key = published.key();
            long version = published.version();
            fixture.restartServer();
            Flow queried = fixture.flowService().flow(fixture.session(), key, version).orElseThrow();
            assertBusinessDefinitions(queried);

            String executionId = start(fixture, queried, Map.of("order", " order-41 "));
            PausedTaskRunRef pause = fixture.waitingForExecution(executionId);
            Execution waiting = query(fixture, executionId);
            assertEquals(Map.of("order", "ORDER-41", "reference", "builtin-default"), waiting.inputs());
            assertEquals(Map.of("value", "ORDER-41|builtin-default"), run(waiting, queried, "before").outputs());

            fixture.resume(pause, Map.of("decision", " dec-52 "));
            Execution completed = assertCompleted(fixture, executionId);
            assertEquals(Map.of("decision", "DEC-52"), completed.requireTaskRun(pause.taskRunId()).outputs());
            assertEquals(Map.of("value", "ORDER-41|DEC-52|builtin-default"), run(completed, queried, "after").outputs());
            assertEquals(1, fixture.executionService().executions(fixture.session()).size());
        }
    }

    /** Checks both invalid rules independently at all three input positions and deletes every draft. */
    @Test
    void s2UserCannotPublishInvalidBusinessDefinitions() {
        for (String position : new String[]{"flow", "task", "resume"}) {
            for (String violation : new String[]{"configuration", "default"}) {
                try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
                    String sample = switch (position) {
                        case "flow" -> "order-10";
                        case "task" -> "task-20";
                        case "resume" -> "dec-30";
                        default -> throw new IllegalArgumentException("Unknown input position");
                    };
                    String prefix = sample.substring(0, sample.indexOf('-')).toUpperCase(Locale.ROOT);
                    String source = fullFlowYaml("uc12-s2-" + position + "-" + violation);
                    source = violation.equals("configuration")
                        ? source.replace("prefix: " + prefix, "prefix: INVALID_PREFIX")
                        : source.replace("defaultValue: ' " + sample + " '", "defaultValue: 'BAD-99'");
                    Flow draft = fixture.flowService().save(fixture.session(), PublishFlowCommand.from(source));
                    Flow saved = fixture.flowService().draft(fixture.session(), draft.key()).orElseThrow();
                    assertEquals(source, saved.source());
                    if (!position.equals("flow")) {
                        assertEquals(2, saved.inputs().size());
                        assertBusinessDefinition(saved.inputs().getFirst(), "order", "ORDER", "ORDER-10", true);
                    }

                    RuntimeException rejected = assertThrows(RuntimeException.class, () -> fixture.flowService()
                        .save(fixture.session(), PublishFlowCommand.from(draft.key(), false)));
                    assertTrue(causes(rejected).contains("Business code"), causes(rejected));
                    Flow unchanged = fixture.flowService().draft(fixture.session(), draft.key()).orElseThrow();
                    assertEquals(source, unchanged.source());
                    assertEquals(saved.version(), unchanged.version());
                    assertTrue(fixture.flowService().latestFlow(fixture.session(), draft.key()).isEmpty());
                    assertTrue(fixture.flowService().flow(fixture.session(), draft.key(), saved.version()).isEmpty());
                    assertTrue(fixture.executionService().executions(fixture.session()).isEmpty());

                    fixture.flowService().delete(fixture.session(), draft.key(), true);
                    assertTrue(fixture.flowService().draft(fixture.session(), draft.key()).isEmpty());
                    assertTrue(fixture.flowService().latestFlow(fixture.session(), draft.key()).isEmpty());
                    assertTrue(fixture.pausedTaskRuns().isEmpty());
                }
            }
        }
    }

    /** Rejects an invalid startup code without a run, then observes one normalized successful run. */
    @Test
    void s3UserCorrectsAnInvalidBusinessStartupValue() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(startFlowYaml("uc12-s3"));
            WorkflowException rejected = assertThrows(WorkflowException.class, () -> fixture.executionService()
                .create(fixture.session(), flow.key(), Optional.of(flow.version()), Map.of("order", "wrong-1")));
            assertTrue(causes(rejected).contains("Business code"));
            assertTrue(fixture.executionService().executions(fixture.session()).isEmpty());
            assertTrue(fixture.pausedTaskRuns().isEmpty());

            String executionId = start(fixture, flow, Map.of("order", " order-63 "));
            Execution completed = assertCompleted(fixture, executionId);
            assertEquals(Map.of("order", "ORDER-63"), completed.inputs());
            assertEquals(Map.of("value", "ORDER-63"), run(completed, flow, "after").outputs());
            assertEquals(1, completed.taskRuns().size());
            assertEquals(1, fixture.executionService().executions(fixture.session()).size());
        }
    }

    /** Rejects an invalid Pause code without advancing, then resumes the same instance with a valid code. */
    @Test
    void s4UserCorrectsAnInvalidBusinessResumeValue() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(resumeFlowYaml("uc12-s4"));
            String executionId = start(fixture, flow, Map.of());
            PausedTaskRunRef pause = fixture.waitingForExecution(executionId);
            assertRejectedResume(fixture, flow, pause);

            fixture.resume(pause, Map.of("decision", " dec-74 "));
            Execution completed = assertCompleted(fixture, executionId);
            assertEquals(Map.of("decision", "DEC-74"), completed.requireTaskRun(pause.taskRunId()).outputs());
            assertEquals(Map.of("value", "DEC-74"), run(completed, flow, "after").outputs());
            assertEquals(1, fixture.executionService().executions(fixture.session()).size());
        }
    }

    /** Restarts the real context and reuses only public identifiers to find definitions and resume a run. */
    @Test
    void s5UserReentersTheSystemAndContinuesWithPersistedBusinessRules() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(fullFlowYaml("uc12-s5"));
            String flowKey = flow.key();
            long flowVersion = flow.version();
            String executionId = start(fixture, flow, Map.of("order", " order-81 "));
            String pauseId = fixture.waitingForExecution(executionId).taskRunId();
            fixture.restartServer();

            Flow restored = fixture.flowService().flow(fixture.session(), flowKey, flowVersion).orElseThrow();
            assertBusinessDefinitions(restored);
            Execution waiting = query(fixture, executionId);
            assertEquals(Map.of("order", "ORDER-81", "reference", "builtin-default"), waiting.inputs());
            PausedTaskRunRef pause = fixture.waitingForExecution(executionId);
            assertEquals(pauseId, pause.taskRunId());
            assertRejectedResume(fixture, restored, pause);

            fixture.resume(pause, Map.of("decision", " dec-92 "));
            Execution completed = assertCompleted(fixture, executionId);
            assertEquals(Map.of("order", "ORDER-81", "reference", "builtin-default"), completed.inputs());
            assertEquals(Map.of("decision", "DEC-92"), completed.requireTaskRun(pauseId).outputs());
            assertEquals(Map.of("value", "ORDER-81|DEC-92|builtin-default"), run(completed, restored, "after").outputs());
            assertEquals(1, fixture.executionService().executions(fixture.session()).size());
        }
    }

    /**
     * Queries the final instance and checks terminal state and absence of waiting or active work.
     * @param fixture live public-service fixture
     * @param executionId publicly returned instance identifier
     * @return the queried completed instance
     */
    private static Execution assertCompleted(WorkflowUcFixture fixture, String executionId) {
        fixture.awaitStable(executionId);
        Execution completed = query(fixture, executionId);
        assertEquals(executionId, completed.id());
        assertEquals(State.Type.SUCCESS, completed.state().current());
        assertTrue(completed.unfinishedTaskRuns().isEmpty());
        assertTrue(completed.activeTaskRuns().isEmpty());
        assertTrue(fixture.pausedTaskRuns().isEmpty());
        return completed;
    }

    /**
     * Checks a business rejection and the unchanged publicly observable waiting snapshot.
     * @param fixture live service fixture
     * @param flow published definition used to locate the downstream step
     * @param pause current Pause obtained from a public query
     */
    private static void assertRejectedResume(WorkflowUcFixture fixture, Flow flow, PausedTaskRunRef pause) {
        Execution before = query(fixture, pause.executionId());
        WorkflowException rejected = assertThrows(WorkflowException.class, () -> fixture.executionService()
            .resume(fixture.session(), pause.executionId(), pause.taskRunId(), Map.of("decision", "wrong-1")));
        assertTrue(causes(rejected).contains("Business code"));
        Execution unchanged = query(fixture, pause.executionId());
        assertEquals(before.state(), unchanged.state());
        assertEquals(State.Type.PAUSED, unchanged.state().current());
        assertEquals(before.taskRuns().stream().map(TaskRun::id).toList(),
            unchanged.taskRuns().stream().map(TaskRun::id).toList());
        assertEquals(before.requireTaskRun(pause.taskRunId()).state(), unchanged.requireTaskRun(pause.taskRunId()).state());
        assertEquals(Map.of(), unchanged.requireTaskRun(pause.taskRunId()).outputs());
        assertTrue(unchanged.taskRunsForTask(task(flow, "after").id()).isEmpty());
    }

    /**
     * Checks type, configuration and normalized defaults at all three business-input positions.
     * @param flow the definition obtained through a fresh public query
     */
    private static void assertBusinessDefinitions(Flow flow) {
        assertBusinessDefinition(flow.inputs().getFirst(), "order", "ORDER", "ORDER-10", true);
        assertBusinessDefinition(task(flow, "before").inputs().getFirst(), "local", "TASK", "TASK-20", false);
        Pause pause = assertInstanceOf(Pause.class, task(flow, "wait"));
        assertBusinessDefinition(pause.onResume().getFirst(), "decision", "DEC", "DEC-30", true);
        Input<?> builtIn = flow.inputs().get(1);
        assertInstanceOf(StringInput.class, builtIn);
        assertEquals("reference", builtIn.getKey());
        assertEquals("builtin-default", builtIn.getDefaultValue());
    }

    /**
     * Checks the public definition of one host-provided input.
     * @param input publicly queried input definition
     * @param key expected field key
     * @param prefix expected independent configuration
     * @param defaultValue expected normalized default
     * @param required expected required flag
     */
    private static void assertBusinessDefinition(Input<?> input, String key, String prefix, String defaultValue,
        boolean required) {
        BusinessCodeInput business = assertInstanceOf(BusinessCodeInput.class, input);
        assertEquals("BUSINESS_CODE", input.getType());
        assertEquals(key, input.getKey());
        assertEquals(key, input.getDisplayName());
        assertEquals(required, input.isRequired());
        assertEquals(prefix, business.getPrefix());
        assertEquals(defaultValue, input.getDefaultValue());
    }

    /**
     * Starts through the public service and awaits either waiting or terminal state.
     * @param fixture live service fixture
     * @param flow exact published definition selected by the user
     * @param values submitted values, read only
     * @return the publicly returned stable instance identifier
     */
    private static String start(WorkflowUcFixture fixture, Flow flow, Map<String, ?> values) {
        String executionId = fixture.executionService().create(fixture.session(), flow.key(),
            Optional.of(flow.version()), values).getExecutionId();
        fixture.awaitStable(executionId);
        return executionId;
    }

    /**
     * Reads one execution using its public identifier.
     * @param fixture live service fixture
     * @param executionId accessible instance identifier
     * @return freshly queried instance
     */
    private static Execution query(WorkflowUcFixture fixture, String executionId) {
        return fixture.executionService().execution(fixture.session(), executionId).orElseThrow();
    }

    /**
     * Finds a step by business key in the publicly queried definition.
     * @param flow queried definition
     * @param key declared business step key
     * @return matching step definition
     */
    private static Task task(Flow flow, String key) {
        return flow.allTasks().stream().filter(candidate -> candidate.key().equals(key)).findFirst().orElseThrow();
    }

    /**
     * Checks that the selected observation step executed exactly once and reads its public result.
     * @param execution queried runtime instance
     * @param flow matching published definition
     * @param key observation step business key
     * @return its single successful run
     */
    private static TaskRun run(Execution execution, Flow flow, String key) {
        var runs = execution.taskRunsForTask(task(flow, key).id());
        assertEquals(1, runs.size());
        assertEquals(State.Type.SUCCESS, runs.getFirst().state().current());
        return runs.getFirst();
    }

    /**
     * Exposes wrapped stable validation messages for rejection assertions without matching a stacktrace.
     * @param failure observed public operation failure
     * @return combined causal messages
     */
    private static String causes(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            result.append(cause.getMessage()).append('\n');
        }
        return result.toString();
    }

    /**
     * Builds the independent three-position business-input flow with a built-in default control.
     * @param key independent scenario flow key
     * @return publishable YAML source
     */
    private static String fullFlowYaml(String key) {
        return """
            key: %s
            inputs:
              - key: order
                type: %s
                required: true
                prefix: ORDER
                defaultValue: ' order-10 '
              - key: reference
                type: STRING
                defaultValue: builtin-default
            tasks:
              - key: before
                type: %s
                expression: '{{ inputs.order }}|{{ inputs.reference }}'
                inputs:
                  - key: local
                    type: %s
                    prefix: TASK
                    defaultValue: ' task-20 '
                outputs:
                  - key: value
                    type: STRING
            %s
              - key: after
                type: %s
                expression: '{{ inputs.order }}|{{ outputs.wait.decision }}|{{ inputs.reference }}'
                outputs:
                  - key: value
                    type: STRING
            """.formatted(key, "BUSINESS_CODE", Snapshot.class.getCanonicalName(),
                "BUSINESS_CODE", pauseYaml().indent(2), Snapshot.class.getCanonicalName());
    }

    /**
     * Builds a minimal independent flow for business startup rejection and correction.
     * @param key scenario flow key
     * @return publishable YAML source
     */
    private static String startFlowYaml(String key) {
        return """
            key: %s
            inputs:
              - key: order
                type: %s
                prefix: ORDER
                required: true
            tasks:
              - key: after
                type: %s
                expression: '{{ inputs.order }}'
                outputs:
                  - key: value
                    type: STRING
            """.formatted(key, "BUSINESS_CODE", Snapshot.class.getCanonicalName());
    }

    /**
     * Builds a minimal independent Pause flow whose downstream step exposes the business result.
     * @param key scenario flow key
     * @return publishable YAML source
     */
    private static String resumeFlowYaml(String key) {
        return """
            key: %s
            tasks:
            %s
              - key: after
                type: %s
                expression: '{{ outputs.wait.decision }}'
                outputs:
                  - key: value
                    type: STRING
            """.formatted(key, pauseYaml().indent(2), Snapshot.class.getCanonicalName());
    }

    /** @return a Pause task declaring the independently configured business resume input */
    private static String pauseYaml() {
        return """
            - key: wait
              type: org.cses.flow.extensions.flow.Pause
              onPause:
                key: prepare
                type: org.cses.flow.extensions.log.Log
                message: ready
              onResume:
                - key: decision
                  type: %s
                  required: true
                  prefix: DEC
                  defaultValue: ' dec-30 '
              outputs:
                - key: decision
                  type: STRING
            """.formatted("BUSINESS_CODE");
    }

    /** A deterministic Worker observation step; it never performs Input conversion or validation itself. */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class Snapshot extends Task implements RunnableTask {
        private String expression;

        /**
         * Renders the configured expression against real accepted runtime values.
         * @param context real Worker context, read only
         * @return the observed string as a declared output
         */
        @Override
        public RunResult run(RunContext context) {
            return RunResult.success(Map.of("value", context.render(TemplateExpression.parse(expression))));
        }

        /** @return the configured expression for Task definition equality */
        @Override
        protected Object typeSpecificEqualityState() {
            return expression;
        }
    }
}
