package org.flow.core.models.triggers;

import java.util.*;

import org.flow.core.models.Label;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionTrigger;
import org.flow.core.models.flows.State;
import org.flow.core.models.tasks.Output;
import org.flow.core.runners.RunContext;
import org.flow.core.services.LabelService;
import org.flow.core.utils.IdUtils;

public abstract class TriggerService {

    /**
     * Generate a {@link TriggerEvaluationResult} from raw trigger output variables.
     */
    public static TriggerEvaluationResult generateEvaluationResult(
        AbstractTrigger trigger,
        ConditionContext conditionContext,
        Map<String, Object> variables) {
        RunContext runContext = conditionContext.getRunContext();
        ExecutionTrigger executionTrigger = ExecutionTrigger.of(trigger, variables, runContext.logFileURI());

        return buildEvaluationResult(runContext.getTriggerExecutionId(), trigger, executionTrigger, conditionContext);
    }

    /**
     * Generate a {@link TriggerEvaluationResult} from a typed trigger output.
     */
    public static TriggerEvaluationResult generateEvaluationResult(
        AbstractTrigger trigger,
        ConditionContext conditionContext,
        Output output) {
        RunContext runContext = conditionContext.getRunContext();
        ExecutionTrigger executionTrigger = ExecutionTrigger.of(trigger, output, runContext.logFileURI());

        return buildEvaluationResult(runContext.getTriggerExecutionId(), trigger, executionTrigger, conditionContext);
    }

    /**
     * Generate a {@link TriggerEvaluationResult} for a realtime trigger.
     */
    public static TriggerEvaluationResult generateRealtimeEvaluationResult(
        AbstractTrigger trigger,
        ConditionContext conditionContext,
        Output output) {
        RunContext runContext = conditionContext.getRunContext();
        ExecutionTrigger executionTrigger = ExecutionTrigger.of(trigger, output, runContext.logFileURI());

        return buildEvaluationResult(IdUtils.create(), trigger, executionTrigger, conditionContext);
    }

    private static TriggerEvaluationResult buildEvaluationResult(
        String id,
        AbstractTrigger trigger,
        ExecutionTrigger executionTrigger,
        ConditionContext conditionContext) {
        List<Label> labels = buildLabels(id, trigger, conditionContext, executionTrigger.getVariables());

        return new TriggerEvaluationResult(
            id,
            State.Type.CREATED,
            executionTrigger,
            labels,
            conditionContext.getFlow().getRevision(),
            null,
            null
        );
    }

    /**
     * @deprecated Use {@link #generateEvaluationResult(AbstractTrigger, ConditionContext, Map)} instead.
     */
    @Deprecated(forRemoval = true, since = "2.0.0")
    public static Execution generateExecution(
        AbstractTrigger trigger,
        ConditionContext conditionContext,
        TriggerContext context,
        Map<String, Object> variables) {
        RunContext runContext = conditionContext.getRunContext();
        ExecutionTrigger executionTrigger = ExecutionTrigger.of(trigger, variables, runContext.logFileURI());

        return generateExecution(runContext.getTriggerExecutionId(), trigger, context, executionTrigger, conditionContext);
    }

    /**
     * @deprecated Use {@link #generateEvaluationResult(AbstractTrigger, ConditionContext, Output)} instead.
     */
    @Deprecated(forRemoval = true, since = "2.0.0")
    public static Execution generateExecution(
        AbstractTrigger trigger,
        ConditionContext conditionContext,
        TriggerContext context,
        Output output) {
        RunContext runContext = conditionContext.getRunContext();
        ExecutionTrigger executionTrigger = ExecutionTrigger.of(trigger, output, runContext.logFileURI());

        return generateExecution(runContext.getTriggerExecutionId(), trigger, context, executionTrigger, conditionContext);
    }

    /**
     * @deprecated Use {@link #generateRealtimeEvaluationResult(AbstractTrigger, ConditionContext, Output)} instead.
     */
    @Deprecated(forRemoval = true, since = "2.0.0")
    public static Execution generateRealtimeExecution(
        AbstractTrigger trigger,
        ConditionContext conditionContext,
        TriggerContext context,
        Output output) {
        RunContext runContext = conditionContext.getRunContext();
        ExecutionTrigger executionTrigger = ExecutionTrigger.of(trigger, output, runContext.logFileURI());

        return generateExecution(IdUtils.create(), trigger, context, executionTrigger, conditionContext);
    }

    private static Execution generateExecution(
        String id,
        AbstractTrigger trigger,
        TriggerContext context,
        ExecutionTrigger executionTrigger,
        ConditionContext conditionContext) {
        List<Label> executionLabels = buildLabels(id, trigger, conditionContext, executionTrigger.getVariables());
        return Execution.builder()
            .id(id)
            .namespace(context.getNamespace())
            .flowId(context.getFlowId())
            .tenantId(context.getTenantId())
            .flowRevision(conditionContext.getFlow().getRevision())
            .variables(conditionContext.getFlow().getVariables())
            .state(new State())
            .trigger(executionTrigger)
            .labels(executionLabels)
            .build();
    }

    /**
     * Builds the labels the trigger itself contributes, deliberately without the flow's own labels: the
     * execution is created from the flow processed for runtime and picks those up there, so carrying the
     * raw flow's here would let them win the creation-time merge and pin back a value governance overrides.
     */
    private static List<Label> buildLabels(String id, AbstractTrigger trigger, ConditionContext conditionContext, Map<String, Object> variables) {
        List<Label> labels = LabelService.fromTrigger(conditionContext.getRunContext(), trigger, Map.of("trigger", variables));
        labels.add(new Label(Label.FROM, Label.FromLabel.TRIGGER.value));

        return LabelService.withCorrelationId(labels, id);
    }
}
