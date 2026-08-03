package org.cses.flow.extensions.tasks;

import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;

import java.util.List;
import java.util.Map;

/**
 * Minimal automatic Task extension used by the first lifecycle use case.
 */
public final class AutomaticTask extends Task implements RunnableTask {

    public static final String TYPE = "AUTO";

    private AutomaticTask(
        String id,
        String parentId,
        String key,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        List<? extends Task> tasks
    ) {
        super(
            id,
            parentId,
            key,
            TYPE,
            inputs,
            outputs,
            route,
            dependOn,
            tasks
        );
    }

    public static AutomaticTask create(
        String id,
        String parentId,
        String key,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        List<? extends Task> tasks
    ) {
        return new AutomaticTask(
            id,
            parentId,
            key,
            inputs,
            outputs,
            route,
            dependOn,
            tasks
        );
    }

    public static AutomaticTask rehydrate(
        String id,
        String parentId,
        String key,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        List<? extends Task> tasks
    ) {
        return new AutomaticTask(
            id,
            parentId,
            key,
            inputs,
            outputs,
            route,
            dependOn,
            tasks
        );
    }

    @Override
    public RunResult run(RunContext context) {
        return RunResult.completed(Map.of());
    }
}
