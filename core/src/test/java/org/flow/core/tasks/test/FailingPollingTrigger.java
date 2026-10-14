package org.flow.core.tasks.test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.models.triggers.PollingTriggerInterface;
import org.flow.core.models.triggers.TriggerContext;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@NoArgsConstructor
public class FailingPollingTrigger extends AbstractTrigger implements PollingTriggerInterface {

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws IllegalVariableEvaluationException {
        throw new RuntimeException("fail");
    }

    @Override
    public Duration getInterval() {
        return Duration.of(1, ChronoUnit.MINUTES);
    }
}
