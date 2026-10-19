package org.flow.core.tasks.test;

import java.util.Map;
import java.util.Optional;

import org.flow.plugin.api.annotations.Example;
import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.SubflowExecutionResult;
import org.flow.plugin.core.flow.Subflow;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Test executable task that generates an exception on createWorkerTaskResult"
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = {
                "no example here"
            }
        )
    }
)
public class BadExecutable extends Subflow {

    @Override
    public Optional<SubflowExecutionResult> createSubflowExecutionResult(RunContext runContext, TaskRun taskRun, FlowInterface flow, Execution execution, Map<String, Object> outputs) {
        throw new RuntimeException("An error!");
    }
}
