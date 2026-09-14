package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import java.util.List;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.conditions.Condition;
import org.cses.flow.core.domains.conditions.ConditionContext;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.OrchestrationContext;
import org.cses.flow.core.runner.ResolvedNextTask;

/**
 * Conditional orchestration scope that preserves its source expression.
 *
 * <p>The string is parsed only when definition validation or runtime matching
 * requests a {@link Condition}.</p>
 */
@Plugin(
    title = "路由",
    description = "条件成立时执行其子任务"
)
@SuperBuilder
@NoArgsConstructor
public class Route extends Branch<Route.Output> {

    @NotBlank
    @Schema(
        title = "路由表达式",
        description = "条件成立时进入当前分支；仅 {{ path.to.value }} 表示引用，"
            + "其他操作数为常量",
        format = "flow-condition",
        example = "{{ vars.level }} == A"
    )
    String route;

    public String route() {
        return route;
    }

    /**
     * Parses the current source expression into an immutable condition.
     */
    public Condition condition() {
        return Condition.parser(route);
    }

    public boolean matches(ConditionContext context) {
        return condition().matches(context);
    }

    /**
     * 条件成立才解析子任务，未命中不产生子任务计划。
     * @param context 当前只读运行上下文
     * @return 可启动子任务列表，未命中时为空
     */
    @Override
    public List<ResolvedNextTask> resolveNexts(OrchestrationContext context) {
        return matches(ConditionContext.from(context.variables())) ? super.resolveNexts(context)
                : List.of();
    }

    /**
     * 未命中时保留跳过事实，命中时等待子任务完成。
     * @param context 当前只读运行上下文
     * @return 跳过、成功或等待决定
     */
    @Override
    public Optional<State.Type> resolveState(OrchestrationContext context) {
        return matches(ConditionContext.from(context.variables())) ? super.resolveState(context)
                : Optional.of(State.Type.SKIPPED);
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return route;
    }

    public static class Output implements org.cses.flow.core.domains.tasks.Output{

    }

}
