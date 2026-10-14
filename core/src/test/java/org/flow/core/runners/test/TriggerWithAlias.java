package org.flow.core.runners.test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.models.triggers.PollingTriggerInterface;
import org.flow.core.models.triggers.TriggerContext;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@NoArgsConstructor
@Plugin(
    aliases = "org.flow.core.runners.test.trigger.Alias"
)
public class TriggerWithAlias extends AbstractTrigger implements PollingTriggerInterface {

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws IllegalVariableEvaluationException {
        return Optional.empty();
    }

    @Override
    public Duration getInterval() {
        return Duration.of(1, ChronoUnit.HOURS);
    }
}
