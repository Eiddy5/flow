package org.cses.flow.scheduler;

import io.micronaut.context.annotation.Requires;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** 测试范围外的确定 HTTP 业务操作，状态和编排仍由真实引擎处理。 */
@Plugin
@Requires(property = "flow.test.native-task", value = "true")
@SuperBuilder
@NoArgsConstructor
public class NativeBusinessTask extends Task implements RunnableTask<NativeBusinessTask.Result> {
    private String endpoint;
    private String message;

    /**
     * 使用当前真实调用身份访问本机外部业务样本。
     *
     * @param context 真实 Worker 提供的只读运行变量
     * @return 外部业务返回的确定文本
     * @throws IllegalStateException HTTP 调用或外部业务失败时抛出
     */
    @Override
    public Result run(RunContext context) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("X-Execution-Id", context.taskRunInfo().executionId());
            connection.setRequestProperty("X-TaskRun-Id", context.taskRunInfo().id());
            try (var out = connection.getOutputStream()) {
                out.write(
                        context.render(TemplateExpression.parse(message))
                                .getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200)
                throw new IllegalStateException("native-business-failure");
            try (var in = connection.getInputStream()) {
                return Result.from(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 外部操作的确定结果。 */
    public record Result(String value) implements Output {
        /**
         * 根据外部响应创建结果。
         *
         * @param value 外部系统响应文本，不得为空引用
         * @return 不可变业务结果
         */
        public static Result from(String value) {
            return new Result(value);
        }
    }
}
