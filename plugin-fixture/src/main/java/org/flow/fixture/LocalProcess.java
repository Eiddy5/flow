package org.flow.fixture;

import org.flow.core.models.annotations.Plugin;
import org.flow.plugin.core.runner.Process;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 以独立插件类型复用原生 Process，验证 TaskRunner 的发现和多态解析。 */
@Plugin
@NoArgsConstructor
@SuperBuilder(toBuilder = true)
@Schema(title = "独立插件中的本地进程执行器")
public class LocalProcess extends Process {
}
