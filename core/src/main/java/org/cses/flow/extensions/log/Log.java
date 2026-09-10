package org.cses.flow.extensions.log;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.tasks.*;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Plugin(
    title = "日志",
    description = "将模板消息写入应用日志",
    examples = {
        @Example(
            title = "记录流程消息",
            code = """
                key: log-flow
                tasks:
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    message: "流程已进入自动处理阶段"
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public class Log extends Task implements RunnableTask<VoidOutput> {

    private static final org.slf4j.Logger LOGGER =
        LoggerFactory.getLogger(Log.class);

    @NotNull
    @Schema(
        title = "消息",
        description = "支持 {{ path.to.value }} 取值的日志消息",
        example = "处理结果：{{ outputs.prepare.result }}",
        implementation = String.class
    )
    private TemplateExpression message;

    public String message() {
        return message.source();
    }

    /**
     * 渲染消息并写入日志，模板错误返回无业务字段的失败结果。
     * @param context 非 null 的本次运行上下文，只读
     * @return 非 null 的空成功结果或携带模板错误的失败结果
     */
    @Override
    public VoidOutput run(RunContext context) {
        try {
            LOGGER.info("{}", context.render(message));
            return VoidOutput.from();
        } catch (WorkflowException exception) {
            return VoidOutput.failed("Log message could not be rendered: " + exception.getMessage());
        }
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return message;
    }
}
