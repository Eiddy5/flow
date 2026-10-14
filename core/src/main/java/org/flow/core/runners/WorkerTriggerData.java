package org.flow.core.runners;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.flow.core.models.Label;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.triggers.TriggerContext;

import io.micronaut.core.annotation.Nullable;

/**
 * Wire-format carrying all data the worker needs to process a trigger.
 * <p>
 * This is a single flat data carrier — the worker reconstructs {@link TriggerContext},
 * {@link org.flow.core.models.flows.GenericFlow}, {@link RunContext}, and
 * {@link ConditionContext} from these fields plus locally available beans.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkerTriggerData(
    // --- Trigger + flow identity ---
    String tenantId,
    String namespace,
    String flowId,
    ZonedDateTime date,
    @Nullable Integer flowRevision,
    @Nullable Map<String, Object> flowVariables,
    @Nullable List<Label> flowLabels,

    // --- RunContext wire data ---
    @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS) Map<String, Object> variables,
    @Nullable String traceParent,

    // --- Condition data ---
    Map<String, Object> conditionVariables) implements WorkerRunContextData {

    /**
     * Keys excluded from the variables map — rebuilt on the worker from
     * explicit fields (identity, flow metadata) and local beans.
     * <p>
     * Includes {@code flow} and {@code labels} (rebuilt from explicit fields)
     * plus the {@linkplain WorkerRunContextData#COMMON_RECONSTRUCTED_KEYS common keys}.
     */
    static final Set<String> WORKER_RECONSTRUCTED_KEYS = Stream.concat(
        Stream.of("flow", "labels"),
        COMMON_RECONSTRUCTED_KEYS.stream()
    ).collect(Collectors.toUnmodifiableSet());

    /**
     * Creates a {@link WorkerTriggerData} from a {@link ConditionContext} and
     * {@link TriggerContext}, stripping keys the worker can reconstructed locally.
     *
     * @param conditionContext the ConditionContext with RunContext and flow
     * @param triggerContext the TriggerContext with identity and date
     * @return a new WorkerTriggerData suitable for wire transport
     */
    public static WorkerTriggerData from(ConditionContext conditionContext, TriggerContext triggerContext) {
        RunContext runContext = conditionContext.getRunContext();
        FlowInterface flow = conditionContext.getFlow();

        return new WorkerTriggerData(
            triggerContext.getTenantId(),
            triggerContext.getNamespace(),
            triggerContext.getFlowId(),
            triggerContext.getDate(),
            flow.getRevision(),
            flow.getVariables(),
            flow.getLabels(),
            WorkerRunContextData.filterVariables(runContext, WORKER_RECONSTRUCTED_KEYS),
            runContext.getTraceParent(),
            conditionContext.getVariables()
        );
    }
}
