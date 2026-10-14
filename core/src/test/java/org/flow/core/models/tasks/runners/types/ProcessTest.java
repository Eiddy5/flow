package org.flow.core.models.tasks.runners.types;

import org.flow.core.models.tasks.runners.AbstractTaskRunnerTest;
import org.flow.core.models.tasks.runners.TaskRunner;
import org.flow.plugin.core.runner.Process;

class ProcessTest extends AbstractTaskRunnerTest {

    @Override
    protected TaskRunner<?> taskRunner() {
        return new Process();
    }
}