package org.cses.flow.core.domains.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.cses.flow.core.runner.RunContext;

import java.util.Map;
import java.util.Optional;

/**
 * Flow-control capability implemented by a concrete Task definition.
 *
 * <p>An OrchestrationTask has no executable work and is never sent to a
 * Worker. Its methods only declare stable orchestration characteristics; the
 * Executor interprets them and owns every resulting Execution and TaskRun
 * transition.</p>
 */
public interface OrchestrationTask<T extends Output> {

    /** @return 本任务调用的独立流程；普通编排任务为空 */
    default Optional<FlowReference> subFlow() {
        return Optional.empty();
    }

    /** 子流程定义引用，租户始终由当前 Execution 确定。 */
    @EqualsAndHashCode
    @ToString
    class FlowReference {
        @jakarta.validation.constraints.NotBlank
        private String key;
        @jakarta.validation.constraints.NotNull
        @jakarta.validation.constraints.Positive
        private Long version;

        /**
         * 校验引用必需的业务键与精确版本。
         * @param key 非空流程键
         * @param version 正整数版本
         * @throws IllegalArgumentException 引用不完整时抛出
         */
        @JsonCreator
        private FlowReference(@JsonProperty("key") String key, @JsonProperty("version") Long version) {
            if (key == null || key.isBlank() || version == null || version < 1) {
                throw new IllegalArgumentException("SubFlow requires a key and positive version");
            }
            this.key = key;
            this.version = version;
        }

        /** @return 引用的非空流程键 */
        public String key() {
            return key;
        }

        /** @return 引用的正整数流程版本 */
        public Long version() {
            return version;
        }

        /**
         * 创建精确流程引用。
         * @param key 非空流程键
         * @param version 正整数版本
         * @return 已校验的引用
         */
        public static FlowReference from(String key, long version) {
            return new FlowReference(key, version);
        }
    }

    /**
     * Whether entering this Task pauses its TaskRun for an explicit resume.
     */
    default boolean pausesTaskRun() {
        return false;
    }

    /**
     * Whether matching direct children may start as parallel branches.
     */
    default boolean startsChildrenInParallel() {
        return false;
    }

    /**
     * Whether this Task repeats its direct child scope in serial iterations.
     */
    default boolean iteratesChildren() {
        return false;
    }

    /**
     * Hard upper bound for an iterative child scope.
     */
    default int maxIterations() {
        return 1;
    }

    /**
     * Decides what happens after one complete child iteration has settled.
     */
    default IterationDecision decideAfterIteration(
            int completedIterations,
            Map<String, Map<String, Object>> iterationOutputs
    ) {
        return IterationDecision.SUCCESS;
    }

    /**
     * Stable failure message for an iterative scope that cannot complete.
     */
    default String iterationFailureMessage(int completedIterations) {
        return "Orchestration did not complete after " + completedIterations
                + " iterations";
    }

    /**
     * Whether this TaskRun remains RUNNING until its child scope settles.
     */
    default boolean holdsTaskRunUntilChildrenSettle() {
        return false;
    }

    /**
     * 在编排完成后计算本任务的具体输出，不通过 Worker 执行。
     * @param context 非 null 的完成时只读上下文；独立子流程调用使用子运行上下文，其他任务使用自身上下文
     * @return 本次具体输出；null 表示沿用已有结果，默认编排任务没有新增业务输出
     */
    default T outputs(RunContext context) {
        return null;
    }

    enum IterationDecision {
        CONTINUE,
        SUCCESS,
        FAILURE
    }


}
