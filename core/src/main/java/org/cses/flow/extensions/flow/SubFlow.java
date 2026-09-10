package org.cses.flow.extensions.flow;

import jakarta.validation.constraints.NotNull;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;

import java.util.Map;
import java.util.Optional;

/** 调用独立子流程并等待其完成的编排任务。 */
@Plugin(title = "子流程", description = "通过统一 inputs 调用指定版本的流程，等待并返回结果")
@SuperBuilder
@NoArgsConstructor
public class SubFlow extends Task implements OrchestrationTask<SubFlow.Output> {

    @NotNull
    private FlowReference flow;

    /** @return 当前调用的精确流程引用 */
    @Override
    public Optional<FlowReference> subFlow() {
        return Optional.ofNullable(flow);
    }

    /** @return 参与任务定义相等判断的流程引用 */
    @Override
    protected Object typeSpecificEqualityState() {
        return flow;
    }

    /**
     * 从已完成的子 Execution 上下文收集可见节点结果。
     * @param context 子流程完成时的只读上下文
     * @return 子运行身份与按任务 key 分组的有效结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public Output outputs(RunContext context) {
        Map<String, Object> execution = (Map<String, Object>) context.variables().get("execution");
        return Output.from((String) execution.get("id"),
                (Map<String, Object>) context.variables().get("outputs"));
    }

    /** 子调用结果；outputs 复用运行上下文的有效任务结果视图。 */
    @lombok.Getter
    public static class Output implements org.cses.flow.core.domains.tasks.Output {
        private String executionId;
        private Map<String, Object> outputs;

        /**
         * 校验身份并复制结果容器；嵌套值来自不可变运行上下文。
         * @param executionId 非空子运行编号
         * @param outputs 非 null 的只读任务结果
         * @throws IllegalArgumentException 子运行编号为空时抛出
         */
        private Output(String executionId, Map<String, Object> outputs) {
            if (executionId == null || executionId.isBlank()) {
                throw new IllegalArgumentException("Child execution id must not be blank");
            }
            this.executionId = executionId;
            this.outputs = Map.copyOf(outputs);
        }
        /**
         * 创建子流程结果快照。
         * @param executionId 已完成的子运行编号
         * @param outputs 只读的有效任务结果
         * @return 子调用输出
         */
        public static Output from(String executionId, Map<String, Object> outputs) {
            return new Output(executionId, outputs);
        }
    }
}
