package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.OrchestrationContext;
import org.cses.flow.core.runner.ResolvedNextTask;
import org.cses.flow.core.validations.ModelInvariant;
import java.util.Optional;
import org.cses.flow.core.domains.flows.State;


/**
 * Serial orchestration scope repeated a fixed number of times.
 */
@Plugin(
    title = "循环",
    description = "按固定次数重复执行子任务",
    examples = {
        @Example(
            title = "重复执行子任务三次",
            code = """
                key: loop-flow
                tasks:
                  - key: repeat-group
                    type: org.cses.flow.extensions.flow.Loop
                    times: 3
                    tasks:
                      - key: repeated-step
                        type: org.cses.flow.extensions.log.Log
                        message: "执行循环步骤"
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public class Loop extends Branch<Loop.Output> implements ModelInvariant {

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

    /**
     * 当前轮完成且次数未达到时请求下一轮。
     * @param context 当前只读运行上下文
     * @return 当前轮或下一轮的可启动任务列表
     */
    @Override
    public List<ResolvedNextTask> resolveNexts(OrchestrationContext context) {
        int current = context.iteration();
        int next = current == 0 || context.settled(tasks(), current) ? current + 1 : current;
        return next <= times() ? context.serial(tasks(), next) : List.of();
    }

    /**
     * 最后一轮全部完成后返回成功。
     * @param context 当前只读运行上下文
     * @return 成功状态，未完成时为空
     */
    @Override
    public Optional<State.Type> resolveState(OrchestrationContext context) {
        return context.iteration() == times() && context.settled(tasks(), context.iteration())
                ? Optional.of(State.Type.SUCCESS) : Optional.empty();
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
    }

    public static class Output implements org.cses.flow.core.domains.tasks.Output{

    }
}
