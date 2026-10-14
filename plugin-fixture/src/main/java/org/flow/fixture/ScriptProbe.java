package org.flow.fixture;

import java.util.List;

import org.flow.core.models.annotations.Example;
import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.runners.RunContext;
import org.flow.plugin.scripts.exec.AbstractExecScript;
import org.flow.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 验证独立插件能够继承通用脚本基类，并显式选择 TaskRunner。 */
@Getter
@NoArgsConstructor
@SuperBuilder
@Schema(title = "执行本地命令的脚本扩展验证任务")
@Plugin(examples = @Example(title = "显式指定扩展执行器", full = true, code = """
    id: script_probe
    namespace: migration
    tasks:
      - id: command
        type: org.flow.fixture.ScriptProbe
        taskRunner:
          type: org.flow.fixture.LocalProcess
        commands:
          - echo verified
    """))
public class ScriptProbe extends AbstractExecScript implements RunnableTask<ScriptOutput> {
    @NotNull
    @Schema(title = "验证时执行的命令列表")
    private Property<List<String>> commands;

    /**
     * 声明该验证任务不提供容器镜像。
     * @return 空值；本任务使用调用者显式配置的本地执行器
     */
    @Override
    public Property<String> getContainerImage() {
        return null;
    }

    /**
     * 通过原生脚本包装器执行命令并收集日志、结构化输出和输出文件。
     * @param runContext 当前任务的原生运行上下文
     * @return 执行器退出码与收集的输出
     * @throws Exception 命令启动、执行或输出收集失败时抛出
     */
    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {
        return commands(runContext)
            .withInterpreter(getInterpreter())
            .withBeforeCommands(getBeforeCommands())
            .withCommands(commands)
            .run();
    }
}
