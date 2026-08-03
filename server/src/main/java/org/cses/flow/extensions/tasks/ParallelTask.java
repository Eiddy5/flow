package org.cses.flow.extensions.tasks;

import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.BranchTask;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;

import java.util.List;

/**
 * Explicit structural Task whose matching direct children run in parallel.
 */
public final class ParallelTask extends Task implements BranchTask {

    public static final String TYPE = "PARALLEL";

    private ParallelTask(
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

    public static ParallelTask create(
        String id,
        String parentId,
        String key,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        List<? extends Task> tasks
    ) {
        return new ParallelTask(
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

    public static ParallelTask rehydrate(
        String id,
        String parentId,
        String key,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        List<? extends Task> tasks
    ) {
        return new ParallelTask(
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
    public boolean startsChildrenInParallel() {
        return true;
    }
}
