package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.runner.RunContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 创建一个独立子 Execution 并等待结果的任务协议，由 Executor 协调执行。 */
public interface ExecutableTask<T extends Output> {
    /**
     * 绑定当前调用参数，生成独立子执行请求。
     * @param inputs 父运行输入，只读且非 null
     * @return 已校验的调用请求；不查询仓储或创建 Execution
     */
    Request createExecution(Map<String, Object> inputs);

    /**
     * 将已完成子运行的可见结果转换为具体任务输出。
     * @param context 子运行的只读完成上下文，非 null
     * @return 具体调用结果，非 null
     */
    T completeExecution(RunContext context);

    /** 跨调度边界的调用请求，不是 Task 定义中的配置包装。 */
    class Request {
        private String flowKey;
        private long flowVersion;
        private Map<String, Object> inputs;

        /** @return 本次调用的精确流程键 */
        public String flowKey() {
            return flowKey;
        }

        /** @return 本次调用的正整数精确版本 */
        public long flowVersion() {
            return flowVersion;
        }

        /** @return 已复制的只读输入，保留显式空值 */
        public Map<String, Object> inputs() {
            return inputs;
        }

        /**
         * 创建独立执行请求，保留输入空值。
         * @param flowKey 非空流程键
         * @param flowVersion 正整数精确版本
         * @param inputs 已绑定输入，只读
         * @return 已校验的调用请求
         */
        public static Request from(String flowKey, long flowVersion, Map<String, Object> inputs) {
            return new Request(flowKey, flowVersion, inputs);
        }

        /**
         * 校验目标并复制输入容器，保留合法的显式空值。
         * @param flowKey 非空子流程键
         * @param flowVersion 正整数精确版本
         * @param inputs 已绑定输入，只读且非 null
         * @throws IllegalArgumentException 目标不合法时抛出
         */
        private Request(String flowKey, long flowVersion, Map<String, Object> inputs) {
            if (flowKey == null || flowKey.isBlank() || flowVersion < 1) {
                throw new IllegalArgumentException("Child execution requires a key and positive version");
            }
            this.flowKey = flowKey;
            this.flowVersion = flowVersion;
            this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        }
    }
}
