package org.flow.core.runners;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.tasks.Task;

import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;

public interface PausedTaskNotifier {

    void taskPaused(FlowInterface flow, Execution execution, TaskRun taskRun, Task task);

    @Singleton
    @Secondary
    class NoopPausedTaskNotifier implements PausedTaskNotifier {
        @Override
        public void taskPaused(FlowInterface flow, Execution execution, TaskRun taskRun, Task task) {
            // no-op
        }
    }
}
