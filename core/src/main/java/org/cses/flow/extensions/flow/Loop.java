package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.validations.ModelInvariant;

import java.util.Map;

/**
 * Serial orchestration scope repeated a fixed number of times.
 */
@Plugin(
    title = "LOOP",
    description = "将直接子步骤按固定次数依次重复执行"
)
@SuperBuilder
@NoArgsConstructor
public final class Loop extends Task implements OrchestrationTask, ModelInvariant {

    @NotNull
    @Positive
    @Schema(
        title = "循环次数",
        description = "直接子步骤完整执行的次数",
        example = "3"
    )
    private Integer times;

    public int times() {
        return times == null ? 0 : times;
    }

    @Override
    public boolean iteratesChildren() {
        return true;
    }

    @Override
    public boolean holdsTaskRunUntilChildrenSettle() {
        return true;
    }

    @Override
    public int maxIterations() {
        return times();
    }

    @Override
    public IterationDecision decideAfterIteration(
        int completedIterations,
        Map<String, Map<String, Object>> iterationOutputs
    ) {
        return completedIterations < times()
            ? IterationDecision.CONTINUE
            : IterationDecision.SUCCESS;
    }

    @Override
    public void verifyModelInvariant() {
        verifyBody("LOOP");
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return times;
    }

    private void verifyBody(String type) {
        if (tasks().isEmpty()) {
            throw new IllegalArgumentException(
                type + " requires at least one child Task"
            );
        }
        if (!"DIRECT".equals(tasks().getFirst().route().source())) {
            throw new IllegalArgumentException(
                type + " first child Task route must be DIRECT"
            );
        }
    }
}
