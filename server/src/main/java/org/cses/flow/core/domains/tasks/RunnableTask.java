package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.runner.RunContext;

/**
 * Executable capability implemented by a concrete Task definition.
 */
public interface RunnableTask {

    /**
     * Executes this Task once using only its invocation-scoped context.
     */
    RunResult run(RunContext context);
}
