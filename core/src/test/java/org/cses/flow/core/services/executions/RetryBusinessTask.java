package org.cses.flow.core.services.executions;

import io.micronaut.context.annotation.Requires;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;

import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** 通过真实 HTTP 调用外部业务样本，返回可由后置步骤消费的输出。 */
@Plugin
@Requires(property = "flow.test.retry-task", value = "true")
@SuperBuilder
@NoArgsConstructor
public class RetryBusinessTask extends Task implements RunnableTask<RetryBusinessTask.BusinessOutput> {
    private String endpoint;
    private String message;

    /**
     * 每次调用都发送一次业务请求，外部失败直接交给生产 Worker 处理。
     * @param context 本次真实调用的身份和变量，只读
     * @return 业务服务返回的成功值
     * @throws IllegalStateException 外部请求失败时抛出
     */
    @Override
    public BusinessOutput run(RunContext context) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setDoOutput(true);
            connection.setRequestProperty("X-Execution-Id", context.taskRunInfo().executionId());
            connection.setRequestProperty("X-TaskRun-Id", context.taskRunInfo().id());
            try (var body = connection.getOutputStream()) {
                body.write(context.render(TemplateExpression.parse(message)).getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200) {
                try (var body = connection.getErrorStream()) {
                    throw new IllegalStateException(new String(body.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            try (var body = connection.getInputStream()) {
                return BusinessOutput.from(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Business HTTP failed", failure);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 返回业务结果的简单边界协议。 */
    public record BusinessOutput(String value) implements Output {
        /**
         * 创建只读业务输出。
         * @param value 非空业务服务响应
         * @return 新输出
         */
        public static BusinessOutput from(String value) { return new BusinessOutput(value); }
    }
}
