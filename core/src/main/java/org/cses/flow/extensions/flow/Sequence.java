package org.cses.flow.extensions.flow;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;

/**
 * Explicit orchestration scope whose direct children run serially.
 */
@Plugin(
    title = "顺序",
    description = "按定义顺序执行子任务并等待全部完成",
    examples = {
        @Example(
            title = "依次执行两个步骤",
            code = """
                key: sequence-flow
                tasks:
                  - key: ordered-steps
                    type: org.cses.flow.extensions.flow.Sequence
                    tasks:
                      - key: prepare
                        type: org.cses.flow.extensions.tasks.AutomaticTask
                      - key: finish
                        type: org.cses.flow.extensions.tasks.AutomaticTask
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public class Sequence extends Branch implements OrchestrationTask {

    @Override
    public boolean holdsTaskRunUntilChildrenSettle() {
        return true;
    }
}
