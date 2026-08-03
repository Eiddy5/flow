package org.cses.flow.extensions.tasks;

import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.BranchTask;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;

import java.util.List;

/**
 * A Task whose TaskRun remains running until an external trigger resumes it.
 */
public final class PauseTask extends Task implements BranchTask {

    public static final String TYPE = "PAUSE";

    private PauseTask(
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

    public static PauseTask create(
        String id,
        String parentId,
        String key,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        List<? extends Task> tasks
    ) {
        return new PauseTask(
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

    public static PauseTask rehydrate(
        String id,
        String parentId,
        String key,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        List<? extends Task> tasks
    ) {
        return new PauseTask(
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
    public boolean waitsForResume() {
        return true;
    }
}
