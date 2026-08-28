package org.cses.flow.extensions.flow;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.plugins.annotations.Plugin;

@Plugin(
    title = "顺序",
    description = "按定义顺序执行子任务并等待全部完成"
)
@SuperBuilder
@NoArgsConstructor
public class Sequence extends Branch implements OrchestrationTask {

    @Override
    public boolean holdsTaskRunUntilChildrenSettle() {
        return true;
    }
}
