package org.flow.core.tasks.test;

import java.util.Optional;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.models.annotations.Example;
import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.State;
import org.flow.core.runners.RunContext;
import org.flow.plugin.core.flow.Sequential;

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
    title = "Test flowable task that generates a NPE on resolveState"
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = {
                "id: sequential",
                "namespace: org.flow.tests",
                "",
                "tasks:",
                "  - id: sequential",
                "    type: org.flow.core.tasks.test.BadSequential",
                "    tasks:",
                "      - id: 1st",
                "        type: org.flow.plugin.core.debug.Return",
                "        format: \"{{task.id}} > {{taskrun.startDate}}\"",
                "      - id: 2nd",
                "        type: org.flow.plugin.core.debug.Return",
                "        format: \"{{task.id}} > {{taskrun.id}}\"",
                "  - id: last",
                "    type: org.flow.plugin.core.debug.Return",
                "    format: \"{{task.id}} > {{taskrun.startDate}}\""
            }
        )
    }
)
public class BadSequential extends Sequential {

    @Override
    public Optional<State.Type> resolveState(RunContext runContext, Execution execution, TaskRun parentTaskRun) throws IllegalVariableEvaluationException {
        throw new RuntimeException("BAM");
    }
}
