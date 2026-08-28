package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.conditions.Condition;
import org.cses.flow.core.domains.conditions.ConditionContext;
import org.cses.flow.core.domains.conditions.Operand;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.validations.ModelInvariant;

import java.util.LinkedHashMap;
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
                    condition: '{{ outputs.check.status }} == DONE'
                    maxIterations: 3
                    tasks:
                      - key: check
                        type: org.cses.flow.extensions.tasks.AutomaticTask
                        outputs:
                          - key: status
                            type: STRING
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public class LoopUntil extends Branch implements OrchestrationTask, ModelInvariant {

    @NotNull
    @Schema(
        title = "结束条件",
        description = "当前轮次输出满足时结束；仅 {{ path.to.value }} 表示引用，"
            + "其他操作数为常量",
        implementation = String.class,
        format = "flow-condition",
        example = "{{ outputs.check.status }} == DONE"
    )
    private Condition condition;

    @NotNull
    @Positive
    @Schema(
        title = "最大循环次数",
        description = "条件始终不成立时允许执行的最大轮数",
        example = "10"
    )
    private Integer maxIterations;

    public Condition condition() {
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
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("inputs", Map.of());
        variables.put("outputs", iterationOutputs);
        variables.put("vars", Map.of());
        return decideAfterIteration(
            completedIterations,
            ConditionContext.from(variables)
        );
    }

    public IterationDecision decideAfterIteration(
        int completedIterations,
        ConditionContext context
    ) {
        if (condition != null && condition.matches(context)) {
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
        condition.references().stream()
            .filter(reference -> referencesRoot(reference, "outputs"))
            .forEach(this::verifyOutputReference);
    }

    private void verifyOutputReference(Operand reference) {
        if (reference.path().size() != 3) {
            throw new IllegalArgumentException(
                "Unsupported LOOP UNTIL output reference: " + reference
            );
        }
        String taskKey = reference.path().get(1);
        String outputKey = reference.path().get(2);
        Task source = allDescendants().stream()
            .filter(task -> task.key().equals(taskKey))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "LOOP UNTIL condition references a Task outside its body: "
                    + taskKey
            ));
        Output output = source.outputs().stream()
            .filter(candidate -> candidate.getKey().equals(outputKey))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "LOOP UNTIL condition references an undeclared output: "
                    + taskKey + "." + outputKey
            ));
        if (!condition.supports(reference, output.getType())) {
            throw new IllegalArgumentException(
                "LOOP UNTIL condition is incompatible with output: "
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
    }

    private static boolean referencesRoot(
        Operand reference,
        String root
    ) {
        return !reference.path().isEmpty()
            && reference.path().getFirst().equals(root);
    }
}
