package org.flow.plugin.core.flow;

import java.util.List;
import java.util.Optional;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.models.annotations.Example;
import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.State;
import org.flow.core.models.tasks.FlowableTask;
import org.flow.core.models.tasks.ResolvedTask;
import org.flow.core.models.tasks.VoidOutput;
import org.flow.core.runners.FlowableUtils;
import org.flow.core.runners.RunContext;

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
    title = "Let a block fail without stopping the rest of the flow.",
    description = """
        Runs the nested tasks sequentially; if one fails, remaining siblings in the block are skipped but downstream tasks after `AllowFailure` continue.

        Useful to mark best-effort sections. Combine with `allowWarning` to downgrade failures inside the block to warnings."""
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: allow_failure
                namespace: company.team

                tasks:
                  - id: sequential
                    type: org.flow.plugin.core.flow.AllowFailure
                    tasks:
                     - id: ko
                       type: org.flow.plugin.scripts.shell.Commands
                       commands:
                        - 'exit 1'

                  - id: last
                    type: org.flow.plugin.core.debug.Return
                    format: "{{ task.id }} > {{ taskrun.startDate }}"
                """
        ),
        @Example(
            full = true,
            title = "Allow failure of a group of tasks",
            code = """
                id: allow-failure-demo
                namespace: company.team

                tasks:
                  - id: allow_failure
                    type: org.flow.plugin.core.flow.AllowFailure
                    tasks:
                      - id: fail_silently
                        type: org.flow.plugin.scripts.shell.Commands
                        taskRunner:
                        type: org.flow.plugin.core.runner.Process
                        commands:
                          - exit 1

                  - id: print_to_console
                    type: org.flow.plugin.scripts.shell.Commands
                    taskRunner:
                    type: org.flow.plugin.core.runner.Process
                    commands:
                      - echo "this will run since previous failure was allowed ✅"
                """
        )
    }
)
public class AllowFailure extends Sequential implements FlowableTask<VoidOutput> {
    @Override
    public Optional<State.Type> resolveState(RunContext runContext, Execution execution, TaskRun parentTaskRun) throws IllegalVariableEvaluationException {
        List<ResolvedTask> resolvedTasks = this.childTasks(runContext, parentTaskRun);
        List<ResolvedTask> resolvedErrors = FlowableUtils.resolveTasks(this.getErrors(), parentTaskRun);
        List<ResolvedTask> resolvedFinally = FlowableUtils.resolveTasks(this.getFinally(), parentTaskRun);

        Optional<State.Type> type = FlowableUtils.resolveState(
            execution,
            resolvedTasks,
            resolvedErrors,
            resolvedFinally,
            parentTaskRun,
            runContext,
            this.isAllowFailure(),
            this.isAllowWarning()
        );

        if (type.isEmpty()) {
            return type;
        } else {
            Optional<State.Type> normalState = FlowableUtils.resolveState(
                execution,
                resolvedTasks,
                null,
                resolvedFinally,
                parentTaskRun,
                runContext,
                this.isAllowFailure(),
                this.isAllowWarning()
            );

            if (normalState.isPresent() && normalState.get().isFailed()) {
                return Optional.of(State.Type.WARNING);
            } else {
                return type;
            }
        }
    }
}
