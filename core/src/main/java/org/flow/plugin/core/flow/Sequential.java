package org.flow.plugin.core.flow;

import java.util.List;
import java.util.Optional;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.plugin.api.annotations.Example;
import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.NextTaskRun;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.State;
import org.flow.core.models.hierarchies.AbstractGraph;
import org.flow.core.models.hierarchies.GraphCluster;
import org.flow.core.models.hierarchies.RelationType;
import org.flow.core.models.tasks.*;
import org.flow.core.runners.FlowableUtils;
import org.flow.core.runners.RunContext;
import org.flow.core.utils.GraphUtils;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run child tasks sequentially.",
    description = "Executes the listed tasks one after another, with optional `errors` and `finally` hooks. Useful for grouping steps when mixing with parallel constructs."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: sequential
                namespace: company.team

                tasks:
                  - id: sequential
                    type: org.flow.plugin.core.flow.Sequential
                    tasks:
                      - id: first_task
                        type: org.flow.plugin.core.debug.Return
                        format: "{{ task.id }} > {{ taskrun.startDate }}"

                      - id: second_task
                        type: org.flow.plugin.core.debug.Return
                        format: "{{ task.id }} > {{ taskrun.id }}"

                  - id: last
                    type: org.flow.plugin.core.debug.Return
                    format: "{{ task.id }} > {{ taskrun.startDate }}"
                """
        )
    }
)
public class Sequential extends AbstractBranch<VoidOutput> {
    @Override
    public AbstractGraph tasksTree(Execution execution, TaskRun taskRun, List<String> parentValues) throws IllegalVariableEvaluationException {
        GraphCluster subGraph = new GraphCluster(this, taskRun, parentValues, RelationType.SEQUENTIAL);

        GraphUtils.sequential(
            subGraph,
            this.getTasks(),
            this.errors,
            this._finally,
            taskRun,
            execution
        );

        return subGraph;
    }

    @Override
    public Optional<State.Type> resolveState(RunContext runContext, Execution execution, TaskRun parentTaskRun) throws IllegalVariableEvaluationException {
        List<ResolvedTask> childTasks = this.childTasks(runContext, parentTaskRun);

        return FlowableUtils.resolveSequentialState(
            execution,
            childTasks,
            FlowableUtils.resolveTasks(this.getErrors(), parentTaskRun),
            FlowableUtils.resolveTasks(this.getFinally(), parentTaskRun),
            parentTaskRun,
            runContext,
            this.isAllowFailure(),
            this.isAllowWarning()
        );
    }

    @Override
    public List<NextTaskRun> resolveNexts(RunContext runContext, Execution execution, TaskRun parentTaskRun) throws IllegalVariableEvaluationException {
        return FlowableUtils.resolveSequentialNexts(
            execution,
            this.childTasks(runContext, parentTaskRun),
            FlowableUtils.resolveTasks(this.getErrors(), parentTaskRun),
            FlowableUtils.resolveTasks(this.getFinally(), parentTaskRun),
            parentTaskRun
        );
    }
}
