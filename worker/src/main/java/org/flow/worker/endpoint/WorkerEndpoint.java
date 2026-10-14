package org.flow.worker.endpoint;

import java.util.List;

import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.runners.Worker;
import org.flow.core.runners.WorkerTask;
import org.flow.core.runners.WorkerTrigger;

import io.micronaut.context.annotation.Requires;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import jakarta.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Endpoint(id = "worker")
@Requires(property = "flow.server-type", pattern = "(WORKER|STANDALONE)")
public class WorkerEndpoint {
    @Inject
    Worker worker;

    @Read
    public WorkerEndpointResult running() throws Exception {
        return WorkerEndpointResult.builder()
            .runningCount(worker.getRunningJobs().stream().filter(o -> o instanceof WorkerTask).count())
            .runnings(
                worker.getRunningJobs()
                    .stream()
                    .map(
                        workerTask -> new WorkerEndpointWorkerTask(
                            workerTask.getType(),
                            workerTask instanceof WorkerTask wt ? wt.getTaskRun() : null,
                            workerTask instanceof WorkerTask wt ? wt.getTask() : null,
                            workerTask instanceof WorkerTrigger wt ? wt.getTrigger() : null
                        )
                    )
                    .toList()
            )
            .build();
    }

    @Getter
    @Builder
    public static class WorkerEndpointResult {
        private final long runningCount;
        private final List<WorkerEndpointWorkerTask> runnings;
    }

    @Getter
    @AllArgsConstructor
    public static class WorkerEndpointWorkerTask {
        private final String type;
        private final TaskRun taskRun;
        private final Task task;
        private final AbstractTrigger trigger;
    }
}
