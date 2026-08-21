package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.expressions.Express;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.validations.ModelInvariant;

import java.util.Map;

/**
 * Post-condition serial orchestration scope with a bounded iteration count.
 */
@Plugin(
    title = "条件循环",
    description = "重复执行子任务直到条件满足或达到次数上限",
    examples = {
        @Example(
            title = "检查结果后结束循环",
            code = """
                key: loop-until-flow
                tasks:
                  - key: check-until-done
                    type: org.cses.flow.extensions.flow.LoopUntil
                    condition: 'outputs.check.status == "DONE"'
                    maxIterations: 3
                    tasks:
                      - key: check
                        type: org.cses.flow.extensions.flow.Pause
                        pause:
                          key: request-status
                          type: org.cses.flow.extensions.tasks.AutomaticTask
                        resume:
                          - key: status
                            type: STRING
                            required: true
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public final class LoopUntil extends Task implements OrchestrationTask, ModelInvariant {

    @NotNull
    @Schema(
        title = "结束条件",
        description = "当前轮次输出满足此表达式时结束循环",
        implementation = String.class,
        format = "flow-expression",
        example = "outputs.check.status == \"DONE\""
    )
    private Express condition;

    @NotNull
    @Positive
    @Schema(
        title = "最大循环次数",
        description = "条件始终不成立时允许执行的最大轮数",
        example = "10"
    )
    private Integer maxIterations;

    public Express condition() {
        return condition;
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
        return maxIterations == null ? 0 : maxIterations;
    }

    @Override
    public IterationDecision decideAfterIteration(
        int completedIterations,
        Map<String, Map<String, Object>> iterationOutputs
    ) {
        if (condition != null && condition.matches(iterationOutputs)) {
            return IterationDecision.SUCCESS;
        }
        return completedIterations < maxIterations()
            ? IterationDecision.CONTINUE
            : IterationDecision.FAILURE;
    }

    @Override
    public String iterationFailureMessage(int completedIterations) {
        return "LOOP UNTIL condition was not satisfied after "
            + completedIterations + " iterations: " + condition;
    }

    @Override
    public void verifyModelInvariant() {
        verifyBody();
        if (condition == null) {
            throw new IllegalArgumentException(
                "LOOP UNTIL condition must be provided"
            );
        }
        if (condition.outputPath().size() != 2) {
            throw new IllegalArgumentException(
                "Unsupported LOOP UNTIL condition expression: "
                    + condition.source()
            );
        }
        String taskKey = condition.outputPath().get(0);
        String outputKey = condition.outputPath().get(1);
        Task source = allDescendants().stream()
            .filter(task -> task.key().equals(taskKey))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "LOOP UNTIL condition references a Task outside its body: "
                    + taskKey
            ));
        Output output = source.outputs().stream()
            .filter(candidate -> candidate.key().equals(outputKey))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "LOOP UNTIL condition references an undeclared output: "
                    + taskKey + "." + outputKey
            ));
        if (output.type() != DataType.STRING) {
            throw new IllegalArgumentException(
                "LOOP UNTIL condition output must be STRING: "
                    + taskKey + "." + outputKey
            );
        }
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return java.util.Arrays.asList(condition, maxIterations);
    }

    private void verifyBody() {
        if (tasks().isEmpty()) {
            throw new IllegalArgumentException(
                "LOOP UNTIL requires at least one child Task"
            );
        }
        if (!"DIRECT".equals(tasks().getFirst().route().source())) {
            throw new IllegalArgumentException(
                "LOOP UNTIL first child Task route must be DIRECT"
            );
        }
    }
}
