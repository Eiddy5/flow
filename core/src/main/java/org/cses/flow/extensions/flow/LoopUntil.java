package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Optional;
import java.util.List;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.conditions.Condition;
import org.cses.flow.core.domains.conditions.ConditionContext;
import org.cses.flow.core.domains.conditions.Operand;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.OrchestrationContext;
import org.cses.flow.core.runner.ResolvedNextTask;
import org.cses.flow.core.validations.ModelInvariant;


/**
 * Post-condition serial orchestration scope with a bounded iteration count.
 */
@Plugin(
    title = "条件循环",
    description = "重复执行子任务直到条件满足或达到次数上限",
    examples = {
        @Example(
            title = "检查输入后结束循环",
            code = """
                key: loop-until-flow
                inputs:
                  - key: done
                    type: BOOLEAN
                    required: true
                tasks:
                  - key: check-until-done
                    type: org.cses.flow.extensions.flow.LoopUntil
                    condition: '{{ inputs.done }} == true'
                    maxIterations: 3
                    tasks:
                      - key: check
                        type: org.cses.flow.extensions.log.Log
                        message: "检查循环条件"
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public class LoopUntil extends Branch<LoopUntil.Output> implements ModelInvariant {

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

    /** @return 配置的循环上限；未完成绑定时为 0，正式使用前须通过模型校验 */
    public int maxIterations() {
        return maxIterations == null ? 0 : maxIterations;
    }

    /**
     * 完整循环体结束后读取条件，尚未满足且未达到上限时请求下一轮。
     * @param context 当前只读运行上下文
     * @return 当前轮或下一轮的可启动任务列表
     */
    @Override
    public List<ResolvedNextTask> resolveNexts(OrchestrationContext context) {
        int current = context.iteration();
        if (current == 0) return context.serial(tasks(), 1);
        if (!context.settled(tasks(), current)) return context.serial(tasks(), current);
        if (condition.matches(ConditionContext.from(context.variables())) || current >= maxIterations()) return List.of();
        return context.serial(tasks(), current + 1);
    }

    /**
     * 当前循环体收敛后判断条件结果；达到上限仍不成立则失败。
     * @param context 当前只读运行上下文
     * @return 成功状态，仍有工作时为空
     * @throws WorkflowException 达到上限且条件仍未满足时抛出
     */
    @Override
    public Optional<State.Type> resolveState(OrchestrationContext context) {
        if (context.iteration() == 0 || !context.settled(tasks(), context.iteration())) return Optional.empty();
        if (condition.matches(ConditionContext.from(context.variables()))) return Optional.of(State.Type.SUCCESS);
        if (context.iteration() < maxIterations()) return Optional.empty();
        throw new WorkflowException("LOOP UNTIL condition was not satisfied after "
                + context.iteration() + " iterations: " + condition);
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

    /**
     * 检查循环条件引用属于循环体且匹配任务代码定义的标量输出类型。
     * @param reference 非 null 的输出引用，只读
     * @throws IllegalArgumentException 引用路径、字段或条件类型非法时抛出
     */
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
        var output = source.outputs().stream()
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

    public static class Output implements org.cses.flow.core.domains.tasks.Output{}
}
