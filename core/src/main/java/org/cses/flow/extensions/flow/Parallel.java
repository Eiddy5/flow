package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;

import java.util.OptionalInt;

/**
 * Explicit orchestration scope whose selected direct branches run in
 * parallel.
 */
@Plugin(
    title = "并行",
    description = "并行启动直接子分支并等待所有已选择分支收敛"
)
@SuperBuilder
@NoArgsConstructor
public final class Parallel extends Task implements OrchestrationTask {

    @Positive
    @Schema(
        title = "并发数",
        description = "作用域下 Worker 消费者的最大并发数；省略表示不限制",
        example = "4"
    )
    private Integer concurrent;

    public OptionalInt concurrent() {
        return concurrent == null
            ? OptionalInt.empty()
            : OptionalInt.of(concurrent);
    }

    @Override
    public boolean startsChildrenInParallel() {
        return true;
    }

    @Override
    public boolean holdsTaskRunUntilChildrenSettle() {
        return true;
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return concurrent;
    }
}
