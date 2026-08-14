package org.cses.flow.extensions.tasks;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;

import java.util.Map;

/**
 * Minimal automatic Task used by the first lifecycle use case.
 */
@Plugin(
    title = "自动任务",
    description = "自动执行单个步骤并推进流程",
    examples = {
        @Example(
            title = "执行自动步骤",
            code = """
                key: automatic-task-flow
                tasks:
                  - key: automatic-step
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public final class AutomaticTask extends Task implements RunnableTask {

    @Override
    public RunResult run(RunContext context) {
        return RunResult.success(Map.of());
    }
}
