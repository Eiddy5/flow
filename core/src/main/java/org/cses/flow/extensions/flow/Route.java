package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.conditions.Condition;
import org.cses.flow.core.domains.conditions.ConditionContext;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.plugins.annotations.Plugin;

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
public class Route extends Branch implements OrchestrationTask {

    @NotBlank
    @Schema(
        title = "路由表达式",
        description = "条件成立时进入当前分支；仅 {{ scope.path }} 表示引用，"
            + "其他操作数为常量",
        format = "flow-condition",
        example = "{{ variables.level }} == A"
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

    @Override
    public boolean holdsTaskRunUntilChildrenSettle() {
        return true;
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return route;
    }

}
