package org.flow.core.metrics;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ExecutionMode;

import org.flow.core.models.Label;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.models.triggers.TriggerEvaluationResult;
import org.flow.core.models.triggers.TriggerId;
import org.flow.core.utils.IdUtils;

import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@MicronautTest
@org.junit.jupiter.api.parallel.Execution(ExecutionMode.SAME_THREAD)
class MetricRegistryTest {
    @Inject
    private MetricRegistry metricRegistry;

    @Inject
    private MetricConfig mockConfig;

    @MockBean(MetricConfig.class)
    MetricConfig mockMetricConfig() {
        return mock(MetricConfig.class);
    }

    @Test
    void executionTagsNoLabelsConfigured() {
        when(mockConfig.getLabels()).thenReturn(
            List.of()
        );

        var execution = Execution.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unittest")
            .flowId("flow")
            .flowRevision(1)
            .state(State.of(State.Type.SUCCESS, Collections.emptyList()))
            .labels(
                List.of(
                    new Label("execution-label-foo", "bar"),
                    new Label(Label.CORRELATION_ID, "correlationId")
                )
            )
            .build();
        var tags = metricRegistry.tags(execution);

        assertThat(tags).containsExactly(
            "flow_id", "flow",
            "namespace_id", "org.flow.unittest",
            "state", "SUCCESS"
        );
    }

    @Test
    void executionTagsLabelsConfigured() {
        when(mockConfig.getLabels()).thenReturn(
            List.of("execution-label-foo")
        );

        var executionContainingConfiguredLabel = Execution.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unittest")
            .flowId("flow")
            .flowRevision(1)
            .state(State.of(State.Type.SUCCESS, Collections.emptyList()))
            .labels(
                List.of(
                    new Label("execution-label-foo", "test1"),
                    new Label("execution-label-bar", "test2"),
                    new Label(Label.CORRELATION_ID, "correlationId")
                )
            )
            .build();

        assertThat(metricRegistry.tags(executionContainingConfiguredLabel)).containsExactly(
            "flow_id", "flow",
            "namespace_id", "org.flow.unittest",
            "state", "SUCCESS",
            "label_execution-label-foo",
            "test1"
        );

        var executionNotContainingConfiguredLabel = Execution.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unittest")
            .flowId("flow")
            .flowRevision(1)
            .state(State.of(State.Type.SUCCESS, Collections.emptyList()))
            .labels(
                List.of(
                    new Label("execution-label-bar", "test2"),
                    new Label(Label.CORRELATION_ID, "correlationId")
                )
            )
            .build();

        assertThat(metricRegistry.tags(executionNotContainingConfiguredLabel)).containsExactly(
            "flow_id", "flow",
            "namespace_id", "org.flow.unittest",
            "state", "SUCCESS",
            "label_execution-label-foo",
            "__none__"
        );
    }

    @Test
    void shouldTagTriggerEvaluationWithFlowLabelWhenTheEvaluationResultDoesNotCarryIt() {
        // Given a configured label key the flow declares and the trigger does not
        when(mockConfig.getLabels()).thenReturn(List.of("owner_team"));
        TriggerEvaluationResult evaluationResult = evaluationResult(List.of(new Label(Label.FROM, "trigger")));

        // When
        var tags = metricRegistry.tags(evaluationResult, triggerId(), List.of(new Label("owner_team", "platform")));

        // Then the flow label is tagged, as it is on the execution the trigger creates
        assertThat(tags).containsExactly(
            "flow_id", "flow",
            "namespace_id", "org.flow.unittest",
            "state", "CREATED",
            "label_owner_team", "platform"
        );
    }

    @Test
    void shouldPreferTriggerLabelOverFlowLabelWhenTaggingATriggerEvaluation() {
        // Given the flow and the trigger declaring the same configured label key
        when(mockConfig.getLabels()).thenReturn(List.of("owner_team"));
        TriggerEvaluationResult evaluationResult = evaluationResult(List.of(new Label("owner_team", "data")));

        // When
        var tags = metricRegistry.tags(evaluationResult, triggerId(), List.of(new Label("owner_team", "platform")));

        // Then the trigger wins, matching the merge the created execution applies
        assertThat(tags).containsExactly(
            "flow_id", "flow",
            "namespace_id", "org.flow.unittest",
            "state", "CREATED",
            "label_owner_team", "data"
        );
    }

    @Test
    void shouldTagTriggerEvaluationWithPlaceholderWhenNeitherFlowNorTriggerCarriesTheLabel() {
        // Given a configured label key nothing declares, and null label lists on both sides
        when(mockConfig.getLabels()).thenReturn(List.of("owner_team"));
        TriggerEvaluationResult evaluationResult = evaluationResult(null);

        // When
        var tags = metricRegistry.tags(evaluationResult, triggerId(), null);

        // Then
        assertThat(tags).containsExactly(
            "flow_id", "flow",
            "namespace_id", "org.flow.unittest",
            "state", "CREATED",
            "label_owner_team", "__none__"
        );
    }

    @Test
    void triggerTagsWithNullLabelsAndMetricsLabelsConfigured() {
        when(mockConfig.getLabels()).thenReturn(
            List.of("owner_team")
        );

        AbstractTrigger trigger = mock(AbstractTrigger.class);
        when(trigger.getType()).thenReturn("org.flow.plugin.core.trigger.Schedule");
        when(trigger.getLabels()).thenReturn(null);

        var tags = metricRegistry.tags(trigger);

        assertThat(tags).containsExactly(
            "trigger_type", "org.flow.plugin.core.trigger.Schedule",
            "label_owner_team",
            "__none__"
        );
    }

    private static TriggerEvaluationResult evaluationResult(List<Label> labels) {
        return new TriggerEvaluationResult(IdUtils.create(), State.Type.CREATED, null, labels, 1, null, null);
    }

    private static TriggerId triggerId() {
        return TriggerId.of(null, "org.flow.unittest", "flow", "trigger");
    }
}
