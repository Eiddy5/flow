package org.flow.plugin.core.flow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.Label;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.executions.TaskRunAttempt;
import org.flow.core.models.flows.State;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.TestRunnerUtils;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
class RuntimeLabelsTest {

    @Inject
    private TestRunnerUtils runnerUtils;

    @Test
    @LoadFlows({ "flows/valids/labels-update-task.yml" })
    void update() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            MAIN_TENANT,
            "org.flow.tests",
            "labels-update-task",
            null,
            (flow, createdExecution) -> Map.of(
                "labelsJson", "{\"keyFromJson\": \"valueFromJson\"}",
                "labelsMapKey", "keyFromMap",
                "labelsMapValue", "valueFromMap",
                "labelsListKey", "keyFromList",
                "labelsListValue", "valueFromList"
            ),
            null,
            List.of(
                new Label("keyFromExecution", "valueFromExecution"),
                new Label("overriddenExecutionLabelKey", "executionValueThatWillGetOverridden")
            )
        );

        assertThat(execution.getTaskRunList().size()).isEqualTo(4);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        String labelsOverriderTaskRunId = execution.findTaskRunsByTaskId("override-labels").getFirst().getId();
        assertThat(execution.getLabels()).containsExactlyInAnyOrder(
            new Label(Label.CORRELATION_ID, execution.getId()),
            new Label("flowLabelKey", "flowLabelValue"),
            new Label("overriddenFlowLabelKey", "org.flow.tests.labels-update-task"),
            new Label("keyFromJson", "valueFromJson"),
            new Label("keyFromMap", "valueFromMap"),
            new Label("keyFromList", "valueFromList"),
            new Label("keyFromExecution", "valueFromExecution"),
            new Label("overriddenExecutionLabelKey", labelsOverriderTaskRunId)
        );

        TaskRun labelTaskRun = execution.findTaskRunsByTaskId("override-labels").getFirst();
        TaskRunAttempt labelRunAttempt = labelTaskRun.lastAttempt();

        assertThat(labelRunAttempt.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(labelRunAttempt.getState().getHistories().size()).isEqualTo(3);
        assertThat(labelRunAttempt.getState().getHistories()).extracting(State.History::getState)
            .containsExactly(State.Type.CREATED, State.Type.RUNNING, State.Type.SUCCESS);
    }

    @Test
    @ExecuteFlow("flows/valids/npe-labels-update-task.yml")
    void noNpeOnNullPreviousExecutionLabels(Execution execution) {
        assertThat(execution.getTaskRunList().size()).isEqualTo(1);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        String labelsTaskRunId = execution.findTaskRunsByTaskId("labels").getFirst().getId();
        assertThat(execution.getLabels()).contains(new Label("someLabel", labelsTaskRunId));

        TaskRun labelTaskRun = execution.findTaskRunsByTaskId("labels").getFirst();
        TaskRunAttempt labelRunAttempt = labelTaskRun.lastAttempt();

        assertThat(labelRunAttempt.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(labelRunAttempt.getState().getHistories().size()).isEqualTo(3);
        assertThat(labelRunAttempt.getState().getHistories()).extracting(State.History::getState)
            .containsExactly(State.Type.CREATED, State.Type.RUNNING, State.Type.SUCCESS);

    }

    @Test
    @LoadFlows({ "flows/valids/primitive-labels-flow.yml" })
    void primitiveTypeLabels() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            MAIN_TENANT,
            "org.flow.tests",
            "primitive-labels-flow",
            null,
            (flow, createdExecution) -> Map.of(
                "intLabel", 42,
                "boolLabel", true,
                "floatLabel", 3.14f
            ),
            null,
            List.of(
                new Label("existingLabel", "someValue")
            )
        );

        assertThat(execution.getTaskRunList().size()).isEqualTo(1);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        String labelsTaskRunId = execution.findTaskRunsByTaskId("update-labels").getFirst().getId();

        assertThat(execution.getLabels()).containsExactlyInAnyOrder(
            new Label(Label.CORRELATION_ID, execution.getId()),
            new Label("intValue", "42"),
            new Label("boolValue", "true"),
            new Label("floatValue", "3.14"),
            new Label("taskRunId", labelsTaskRunId),
            new Label("existingLabel", "someValue")
        );

        TaskRun labelTaskRun = execution.findTaskRunsByTaskId("update-labels").getFirst();
        TaskRunAttempt labelRunAttempt = labelTaskRun.lastAttempt();

        assertThat(labelRunAttempt.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(labelRunAttempt.getState().getHistories().size()).isEqualTo(3);
        assertThat(labelRunAttempt.getState().getHistories()).extracting(State.History::getState)
            .containsExactly(State.Type.CREATED, State.Type.RUNNING, State.Type.SUCCESS);

    }

    @Test
    @LoadFlows(value = { "flows/valids/primitive-labels-flow.yml" }, tenantId = "tenant1")
    void primitiveTypeLabelsOverrideExistingLabels() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            "tenant1",
            "org.flow.tests",
            "primitive-labels-flow",
            null,
            (flow, createdExecution) -> Map.of(
                "intLabel", 42,
                "boolLabel", true,
                "floatLabel", 3.14f
            ),
            null,
            List.of(
                new Label("intValue", "1"),
                new Label("boolValue", "false"),
                new Label("floatValue", "4.2f")
            )
        );

        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        String labelsTaskRunId = execution.findTaskRunsByTaskId("update-labels").getFirst().getId();

        assertThat(execution.getLabels()).containsExactlyInAnyOrder(
            new Label(Label.CORRELATION_ID, execution.getId()),
            new Label("intValue", "42"),
            new Label("boolValue", "true"),
            new Label("floatValue", "3.14"),
            new Label("taskRunId", labelsTaskRunId)
        );

        TaskRun labelTaskRun = execution.findTaskRunsByTaskId("update-labels").getFirst();
        TaskRunAttempt labelRunAttempt = labelTaskRun.lastAttempt();

        assertThat(labelRunAttempt.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(labelRunAttempt.getState().getHistories().size()).isEqualTo(3);
        assertThat(labelRunAttempt.getState().getHistories()).extracting(State.History::getState)
            .containsExactly(State.Type.CREATED, State.Type.RUNNING, State.Type.SUCCESS);
    }

    @Test
    @LoadFlows({ "flows/valids/labels-update-task-deduplicate.yml" })
    void updateGetsDeduplicated() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            MAIN_TENANT,
            "org.flow.tests",
            "labels-update-task-deduplicate",
            null,
            (flow, createdExecution) -> Map.of(),
            null,
            List.of()
        );

        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        assertThat(execution.getLabels()).containsExactlyInAnyOrder(
            new Label(Label.CORRELATION_ID, execution.getId()),
            new Label("fromStringKey", "value2"),
            new Label("fromListKey", "value2")
        );

        TaskRun labelTaskRun = execution.findTaskRunsByTaskId("from-string").getFirst();
        TaskRunAttempt labelRunAttempt = labelTaskRun.lastAttempt();

        assertThat(labelRunAttempt.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(labelRunAttempt.getState().getHistories().size()).isEqualTo(3);
        assertThat(labelRunAttempt.getState().getHistories()).extracting(State.History::getState)
            .containsExactly(State.Type.CREATED, State.Type.RUNNING, State.Type.SUCCESS);
    }

    @Test
    @LoadFlows({ "flows/valids/labels-update-task-empty.yml" })
    void updateIgnoresEmpty() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            MAIN_TENANT,
            "org.flow.tests",
            "labels-update-task-empty",
            null,
            (flow, createdExecution) -> Map.of(),
            null,
            List.of()
        );

        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);

        assertThat(execution.getLabels()).containsExactly(
            new Label(Label.CORRELATION_ID, execution.getId())
        );

        TaskRun labelTaskRun = execution.findTaskRunsByTaskId("from-string").getFirst();
        TaskRunAttempt labelRunAttempt = labelTaskRun.lastAttempt();

        assertThat(labelRunAttempt.getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(labelRunAttempt.getState().getHistories().size()).isEqualTo(1);
    }

    @Test
    @ExecuteFlow("flows/valids/labels-update-nested-value-list.yml")
    void shouldUpdateWithNestedValueInList(Execution execution) {
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        assertThat(execution.getLabels()).containsExactlyInAnyOrder(
            new Label(Label.CORRELATION_ID, execution.getId()),
            new Label("stage", "{\"name\":\"done\"}")
        );
    }

    @Test
    @ExecuteFlow("flows/valids/labels-update-nested-value-map.yml")
    void shouldUpdateWithNestedValueInMap(Execution execution) {
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        assertThat(execution.getLabels()).containsExactlyInAnyOrder(
            new Label(Label.CORRELATION_ID, execution.getId()),
            new Label("stage", "{\"name\":\"done\"}")
        );
    }
}
