package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import java.util.OptionalInt;
import java.util.List;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.OrchestrationContext;
import org.cses.flow.core.runner.ResolvedNextTask;
import org.cses.flow.extensions.flow.Parallel;

/**
 * Explicit orchestration scope whose selected direct branches run in
 * parallel.
 */
@Plugin(
    title = "并行",
    description = "同时执行多个子任务并等待分支完成",
    capabilities = "PARALLEL_CHILDREN",
    examples = {
        @Example(
            title = "同时执行两个检查",
            code = """
                key: parallel-flow
                tasks:
                  - key: parallel-checks
                    type: org.cses.flow.extensions.flow.Parallel
                    concurrent: 2
                    tasks:
                      - key: backend-check
                        type: org.cses.flow.extensions.log.Log
                        message: "执行后端检查"
                      - key: frontend-check
                        type: org.cses.flow.extensions.log.Log
                        message: "执行前端检查"
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public class Parallel extends Branch<Parallel.Output> {

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

    /**
     * 并行解析各分支，分支之间保持输入隔离。
     * @param context 当前只读运行上下文
     * @return 所有可启动分支的任务列表
     */
    @Override
    public List<ResolvedNextTask> resolveNexts(OrchestrationContext context) {
        return context.parallel(tasks());
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return concurrent;
    }

    public static class Output implements org.cses.flow.core.domains.tasks.Output{

    }
}
