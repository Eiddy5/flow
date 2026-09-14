package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.runner.OrchestrationContext;
import org.cses.flow.core.runner.ResolvedNextTask;

@SuperBuilder
@NoArgsConstructor
public abstract class Branch<T extends Output> extends Task implements OrchestrationTask<T> {

    /**
     * 顺序解析本作用域的子任务。
     * @param context 当前只读运行上下文
     * @return 可启动子任务列表
     */
    @Override
    public List<ResolvedNextTask> resolveNexts(OrchestrationContext context) {
        return context.serial(tasks(), null);
    }

    /**
     * 子作用域收敛后完成当前节点。
     * @param context 当前只读运行上下文
     * @return 成功决定，未收敛时为空
     */
    @Override
    public Optional<State.Type> resolveState(OrchestrationContext context) {
        return context.settled(tasks(), null) ? Optional.of(State.Type.SUCCESS) : Optional.empty();
    }

    @NotNull
    @Builder.Default
    @Schema(
        title = "子任务",
        description = "由当前结构型任务直接拥有的有序子任务定义"
    )
    List<Task> tasks = List.of();

    public List<Task> tasks() {
        return tasks == null ? List.of() : List.copyOf(tasks);
    }

    @Override
    public List<Task> definitionChildren() {
        return tasks();
    }
}
