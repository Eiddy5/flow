package org.cses.flow.extensions.flow;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.Output;
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
                        type: org.cses.flow.extensions.log.Log
                        message: "准备顺序步骤"
                      - key: finish
                        type: org.cses.flow.extensions.log.Log
                        message: "完成顺序步骤"
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public class Sequence extends Branch<Sequence.Output> {

    public static class Output implements org.cses.flow.core.domains.tasks.Output{

    }
}
