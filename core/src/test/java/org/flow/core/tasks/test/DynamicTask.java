package org.flow.core.tasks.test;

import java.util.List;

import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.executions.TaskRunAttempt;
import org.flow.core.models.flows.State;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.tasks.VoidOutput;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.core.utils.IdUtils;

import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder(toBuilder = true)
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public class DynamicTask extends Task implements RunnableTask<VoidOutput> {

    @Builder.Default
    private Boolean fail = false;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        State state = fail ? new State(State.Type.FAILED) : new State(State.Type.SUCCESS);

        WorkerTaskResult workerTaskResult = WorkerTaskResult.builder()
            .taskRun(
                TaskRun.builder()
                    .id(IdUtils.create())
                    .namespace(runContext.render("{{ flow.namespace }}"))
                    .flowId(runContext.render("{{ flow.id }}"))
                    .taskId(IdUtils.create())
                    .value(runContext.render("{{ taskrun.id }}"))
                    .executionId(runContext.render("{{ execution.id }}"))
                    .parentTaskRunId(runContext.render("{{ taskrun.id }}"))
                    .state(state)
                    .attempts(
                        List.of(
                            TaskRunAttempt.builder()
                                .state(state)
                                .build()
                        )
                    )
                    .build()
            )
            .build();

        runContext.dynamicWorkerResult(List.of(workerTaskResult));

        if (workerTaskResult.getTaskRun().getState().isFailed()) {
            throw new Exception("Task failed");
        }

        return null;
    }
}
