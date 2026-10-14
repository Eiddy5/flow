package org.flow.core.tasks.test;

import java.time.Duration;
import java.util.Optional;

import org.flow.core.models.annotations.PluginProperty;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.models.triggers.PollingTriggerInterface;
import org.flow.core.models.triggers.TriggerContext;

import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * This trigger is used in unit tests where we need a task that wait a little to be able to check the resubmit of triggers.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public class SleepTrigger extends AbstractTrigger implements PollingTriggerInterface {

    @PluginProperty
    @NotNull
    private Long duration;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) {
        // Try catch to avoid flakky test
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return Optional.empty();
    }

    @Override
    public Duration getInterval() {
        return Duration.ofMillis(1);
    }
}
