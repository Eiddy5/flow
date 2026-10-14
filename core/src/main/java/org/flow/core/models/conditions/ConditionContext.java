package org.flow.core.models.conditions;

import java.util.HashMap;
import java.util.Map;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.runners.RunContext;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ConditionContext {
    @NotNull
    private FlowInterface flow;

    private Execution execution;

    @With
    @NotNull
    private RunContext runContext;

    @With
    @Builder.Default
    private final Map<String, Object> variables = new HashMap<>();
}
