package org.cses.flow.core.plugins;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.plugins.annotations.Plugin;

/** 用于定义绑定、状态机和持久化技术测试的具体输出任务。 */
public class TestOutputTasks {
    /** 产生明确的 decision 字段，替代在 Log 上配置虚构输出。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class Decision extends Task implements RunnableTask<Decision.FixtureDecisionOutput> {
        /**
         * 返回此技术样本的确定输出。
         * @param context 本次只读运行上下文；此样本不读取它
         * @return 含 decision 的具体输出
         */
        @Override
        public FixtureDecisionOutput run(RunContext context) {
            return FixtureDecisionOutput.from("approved");
        }
        /** 明确的任务输出字段。 */
        public record FixtureDecisionOutput(String decision) implements org.cses.flow.core.domains.tasks.Output {
            /**
             * 创建本次输出。
             * @param decision 确定业务值；只读
             * @return 新建输出
             */
            public static FixtureDecisionOutput from(String decision) {
                return new FixtureDecisionOutput(decision);
            }
        }
    }
    /** 产生明确的 status 字段，替代在 Log 上配置虚构输出。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class Status extends Task implements RunnableTask<Status.FixtureStatusOutput> {
        /**
         * 返回此技术样本的确定输出。
         * @param context 本次只读运行上下文；此样本不读取它
         * @return 含 status 的具体输出
         */
        @Override
        public FixtureStatusOutput run(RunContext context) {
            return FixtureStatusOutput.from("DONE");
        }
        /** 明确的任务输出字段。 */
        public record FixtureStatusOutput(String status) implements org.cses.flow.core.domains.tasks.Output {
            /**
             * 创建本次输出。
             * @param status 确定业务值；只读
             * @return 新建输出
             */
            public static FixtureStatusOutput from(String status) {
                return new FixtureStatusOutput(status);
            }
        }
    }
    /** 产生明确的 approved 字段，替代在 Log 上配置虚构输出。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class Approved extends Task implements RunnableTask<Approved.FixtureApprovedOutput> {
        /**
         * 返回此技术样本的确定输出。
         * @param context 本次只读运行上下文；此样本不读取它
         * @return 含 approved 的具体输出
         */
        @Override
        public FixtureApprovedOutput run(RunContext context) {
            return FixtureApprovedOutput.from("yes");
        }
        /** 明确的任务输出字段。 */
        public record FixtureApprovedOutput(String approved) implements org.cses.flow.core.domains.tasks.Output {
            /**
             * 创建本次输出。
             * @param approved 确定业务值；只读
             * @return 新建输出
             */
            public static FixtureApprovedOutput from(String approved) {
                return new FixtureApprovedOutput(approved);
            }
        }
    }
    /** 产生明确的 approved 字段，替代在 Log 上配置虚构输出。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class BooleanApproved extends Task implements RunnableTask<BooleanApproved.FixtureBooleanApprovedOutput> {
        /**
         * 返回此技术样本的确定输出。
         * @param context 本次只读运行上下文；此样本不读取它
         * @return 含 approved 的具体输出
         */
        @Override
        public FixtureBooleanApprovedOutput run(RunContext context) {
            return FixtureBooleanApprovedOutput.from(true);
        }
        /** 明确的任务输出字段。 */
        public record FixtureBooleanApprovedOutput(boolean approved) implements org.cses.flow.core.domains.tasks.Output {
            /**
             * 创建本次输出。
             * @param approved 确定业务值；只读
             * @return 新建输出
             */
            public static FixtureBooleanApprovedOutput from(boolean approved) {
                return new FixtureBooleanApprovedOutput(approved);
            }
        }
    }
    /** 产生明确的 count 字段，替代在 Log 上配置虚构输出。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class Count extends Task implements RunnableTask<Count.FixtureCountOutput> {
        /**
         * 返回此技术样本的确定输出。
         * @param context 本次只读运行上下文；此样本不读取它
         * @return 含 count 的具体输出
         */
        @Override
        public FixtureCountOutput run(RunContext context) {
            return FixtureCountOutput.from(1L);
        }
        /** 明确的任务输出字段。 */
        public record FixtureCountOutput(long count) implements org.cses.flow.core.domains.tasks.Output {
            /**
             * 创建本次输出。
             * @param count 确定业务值；只读
             * @return 新建输出
             */
            public static FixtureCountOutput from(long count) {
                return new FixtureCountOutput(count);
            }
        }
    }
    /** 产生明确的 payload 字段，替代在 Log 上配置虚构输出。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class Payload extends Task implements RunnableTask<Payload.FixturePayloadOutput> {
        /**
         * 返回此技术样本的确定输出。
         * @param context 本次只读运行上下文；此样本不读取它
         * @return 含 payload 的具体输出
         */
        @Override
        public FixturePayloadOutput run(RunContext context) {
            return FixturePayloadOutput.from("value");
        }
        /** 明确的任务输出字段。 */
        public record FixturePayloadOutput(String payload) implements org.cses.flow.core.domains.tasks.Output {
            /**
             * 创建本次输出。
             * @param payload 确定业务值；只读
             * @return 新建输出
             */
            public static FixturePayloadOutput from(String payload) {
                return new FixturePayloadOutput(payload);
            }
        }
    }
    /** 产生明确的 result 字段，替代在 Log 上配置虚构输出。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class Result extends Task implements RunnableTask<Result.FixtureResultOutput> {
        /**
         * 返回此技术样本的确定输出。
         * @param context 本次只读运行上下文；此样本不读取它
         * @return 含 result 的具体输出
         */
        @Override
        public FixtureResultOutput run(RunContext context) {
            return FixtureResultOutput.from("ready");
        }
        /** 明确的任务输出字段。 */
        public record FixtureResultOutput(String result) implements org.cses.flow.core.domains.tasks.Output {
            /**
             * 创建本次输出。
             * @param result 确定业务值；只读
             * @return 新建输出
             */
            public static FixtureResultOutput from(String result) {
                return new FixtureResultOutput(result);
            }
        }
    }
    /** 产生明确的 prepared 字段，替代在 Log 上配置虚构输出。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class Prepared extends Task implements RunnableTask<Prepared.FixturePreparedOutput> {
        /**
         * 返回此技术样本的确定输出。
         * @param context 本次只读运行上下文；此样本不读取它
         * @return 含 prepared 的具体输出
         */
        @Override
        public FixturePreparedOutput run(RunContext context) {
            return FixturePreparedOutput.from(true);
        }
        /** 明确的任务输出字段。 */
        public record FixturePreparedOutput(boolean prepared) implements org.cses.flow.core.domains.tasks.Output {
            /**
             * 创建本次输出。
             * @param prepared 确定业务值；只读
             * @return 新建输出
             */
            public static FixturePreparedOutput from(boolean prepared) {
                return new FixturePreparedOutput(prepared);
            }
        }
    }
    /** 产生明确的 status 字段，替代在 Log 上配置虚构输出。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class NumericStatus extends Task implements RunnableTask<NumericStatus.FixtureNumericStatusOutput> {
        /**
         * 返回此技术样本的确定输出。
         * @param context 本次只读运行上下文；此样本不读取它
         * @return 含 status 的具体输出
         */
        @Override
        public FixtureNumericStatusOutput run(RunContext context) {
            return FixtureNumericStatusOutput.from(1);
        }
        /** 明确的任务输出字段。 */
        public record FixtureNumericStatusOutput(int status) implements org.cses.flow.core.domains.tasks.Output {
            /**
             * 创建本次输出。
             * @param status 确定业务值；只读
             * @return 新建输出
             */
            public static FixtureNumericStatusOutput from(int status) {
                return new FixtureNumericStatusOutput(status);
            }
        }
    }
    /** 产生明确的 branchResult 字段，替代在 Log 上配置虚构输出。 */
    @Plugin
    @SuperBuilder
    @NoArgsConstructor
    public static class BranchResult extends Task implements RunnableTask<BranchResult.FixtureBranchResultOutput> {
        /**
         * 返回此技术样本的确定输出。
         * @param context 本次只读运行上下文；此样本不读取它
         * @return 含 branchResult 的具体输出
         */
        @Override
        public FixtureBranchResultOutput run(RunContext context) {
            return FixtureBranchResultOutput.from("ready");
        }
        /** 明确的任务输出字段。 */
        public record FixtureBranchResultOutput(String branchResult) implements org.cses.flow.core.domains.tasks.Output {
            /**
             * 创建本次输出。
             * @param branchResult 确定业务值；只读
             * @return 新建输出
             */
            public static FixtureBranchResultOutput from(String branchResult) {
                return new FixtureBranchResultOutput(branchResult);
            }
        }
    }
}
