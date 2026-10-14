package org.flow.plugin.core.trigger;

import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.models.Label;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionTrigger;
import org.flow.core.models.flows.State;
import org.flow.core.models.triggers.*;
import org.flow.core.runners.RunContext;
import org.flow.core.services.LabelService;
import org.flow.core.utils.ListUtils;

/**
 * Factory class for constructing a new {@link Execution} from a {@link Schedulable} trigger.
 *
 * @see org.flow.plugin.core.trigger.Schedule
 * @see org.flow.plugin.core.trigger.ScheduleOnDates
 */
final class SchedulableExecutionFactory {

    // TODO rename and maybe move ?
    static TriggerEvaluationResult createExecution(Schedulable trigger, ConditionContext conditionContext, TriggerContext triggerContext, Map<String, Object> variables,
        ZonedDateTime scheduleDate)
        throws IllegalVariableEvaluationException {
        RunContext runContext = conditionContext.getRunContext();
        ExecutionTrigger executionTrigger = ExecutionTrigger.of((AbstractTrigger) trigger, variables);

        List<Label> labels = getLabels(trigger, runContext, triggerContext.getBackfill(), variables);

        List<Label> executionLabels = new ArrayList<>(ListUtils.emptyOnNull(labels));
        executionLabels.add(new Label(Label.FROM, Label.FromLabel.TRIGGER.value));
        executionLabels = LabelService.withCorrelationId(executionLabels, runContext.getTriggerExecutionId());

        Execution execution = Execution.builder()
            .id(runContext.getTriggerExecutionId())
            .tenantId(triggerContext.getTenantId())
            .namespace(triggerContext.getNamespace())
            .flowId(triggerContext.getFlowId())
            .flowRevision(conditionContext.getFlow().getRevision())
            .variables(conditionContext.getFlow().getVariables()) // TODO needed ??
            .labels(executionLabels)
            .state(new State())
            .trigger(executionTrigger)

            .scheduleDate(Optional.ofNullable(scheduleDate).map(ChronoZonedDateTime::toInstant).orElse(null))
            .build();

        Map<String, Object> allInputs = getInputs(trigger, runContext, triggerContext.getBackfill());

        Execution executionForRendering = execution.withLabels(
            LabelService.forExecution(conditionContext.getFlow(), executionLabels, execution.getId())
        );

        // add inputs and inject defaults (FlowInputOutput handles defaults internally)
        Map<String, Object> inputs = runContext.inputAndOutput().readInputs(conditionContext.getFlow(), executionForRendering, allInputs);

        return new TriggerEvaluationResult(
            runContext.getTriggerExecutionId(),
            new State().getCurrent(),
            executionTrigger,
            executionLabels,
            conditionContext.getFlow().getRevision(),
            Optional.ofNullable(scheduleDate).map(ChronoZonedDateTime::toInstant).orElse(null),
            inputs
        );
    }

    private static Map<String, Object> getInputs(Schedulable trigger, RunContext runContext, Backfill backfill) throws IllegalVariableEvaluationException {
        Map<String, Object> inputs = new HashMap<>();

        if (trigger.getInputs() != null) {
            inputs.putAll(runContext.render(trigger.getInputs()));
        }

        if (backfill != null && backfill.getInputs() != null) {
            inputs.putAll(runContext.render(backfill.getInputs()));
        }

        return inputs;
    }

    /**
     * Builds the labels the trigger and its backfill contribute, without the flow's own labels: the execution
     * takes those from the flow processed for runtime when it is created.
     */
    private static List<Label> getLabels(Schedulable trigger, RunContext runContext, Backfill backfill, Map<String, Object> variables)
        throws IllegalVariableEvaluationException {
        List<Label> labels = LabelService.fromTrigger(runContext, (AbstractTrigger) trigger, Map.of("trigger", variables));

        if (backfill != null) {
            // It is better to remove system labels before rendering
            List<Label> backfillLabels = LabelService.labelsExcludingSystem(backfill.getLabels());
            for (Label label : backfillLabels) {
                final var value = runContext.render(label.value());
                if (value != null) {
                    labels.add(new Label(label.key(), value));
                }
            }
        }
        return labels;
    }
}
