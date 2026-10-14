package org.flow.core.models.flows.sla.types;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.flow.core.exceptions.InternalException;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.sla.ExecutionChangedSLA;
import org.flow.core.models.flows.sla.SLA;
import org.flow.core.models.flows.sla.Violation;
import org.flow.core.runners.RunContext;
import org.flow.core.utils.TruthUtils;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
public class ExecutionAssertionSLA extends SLA implements ExecutionChangedSLA {
    @NotNull
    @NotEmpty
    @JsonProperty("assert")
    private String _assert;

    @Override
    public Optional<Violation> evaluate(RunContext runContext, Execution execution) throws InternalException {
        String result = runContext.render(this.get_assert());
        if (!TruthUtils.isTruthy(result)) {
            String reason = "assertion is false: " + this.get_assert() + ".";
            return Optional.of(new Violation(this.getId(), this.getBehavior(), this.getLabels(), reason));
        }

        return Optional.empty();
    }
}
