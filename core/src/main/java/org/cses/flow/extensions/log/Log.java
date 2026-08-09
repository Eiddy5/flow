package org.cses.flow.extensions.log;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Plugin(
    title = "日志",
    description = "解析消息表达式并将结果写入应用日志"
)
@SuperBuilder
@NoArgsConstructor
public final class Log extends Task implements RunnableTask {

    private static final org.slf4j.Logger LOGGER =
        LoggerFactory.getLogger(Log.class);

    @NotNull
    @Schema(
        title = "消息",
        description = "支持 {{ path.to.value }} 取值的日志消息",
        example = "处理结果：{{ dependOnOutputs.prepare.result }}",
        implementation = String.class
    )
    private TemplateExpression message;

    public String message() {
        return message.source();
    }

    @Override
    public RunResult run(RunContext context) {
        try {
            LOGGER.info("{}", context.render(message));
            return RunResult.success(Map.of());
        } catch (WorkflowException exception) {
            return RunResult.failed(
                "Log message could not be rendered: "
                    + exception.getMessage()
            );
        }
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return message;
    }
}
