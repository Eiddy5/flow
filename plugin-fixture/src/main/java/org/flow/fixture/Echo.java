package org.flow.fixture;

import org.flow.core.models.annotations.Example;
import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 仅用于扩展验收的无业务任务，返回配置的字符串。 */
@Getter
@NoArgsConstructor
@SuperBuilder
@Schema(title = "返回输入文本的迁移验证任务")
@Plugin(examples = @Example(title = "返回文本", full = true, code = """
    id: plugin_verification
    namespace: migration
    tasks:
      - id: echo
        type: org.flow.fixture.Echo
        text: migrated
    """))
public class Echo extends Task implements RunnableTask<Echo.Output> {
    @NotBlank
    @Schema(title = "原样返回的文本")
    private String text;

    /**
     * 返回配置文本，不读写外部系统。
     *
     * @param runContext 引擎运行上下文；本验证任务不使用其中的服务
     * @return 含输入文本的结构化输出
     */
    @Override
    public Output run(RunContext runContext) {
        return new Output(text);
    }

    /** 供下游任务读取的结构化输出。 */
    public record Output(@Schema(title = "任务返回的文本") String text)
        implements org.flow.core.models.tasks.Output {
    }
}
