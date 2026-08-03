package org.cses.flow.core.plugins;

import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;

import java.util.List;
import java.util.Map;

/**
 * Internal plugin runtime facade used by Flow materialization and persistence.
 */
public interface TaskTypeDispatcher {

    Task dispatch(
        String id,
        String parentId,
        String key,
        String type,
        List<Input<?>> inputs,
        List<Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        Map<String, ?> properties,
        List<? extends Task> tasks
    );

    Task restore(
        String id,
        String parentId,
        String key,
        String type,
        List<Input<?>> inputs,
        List<Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        Map<String, ?> properties,
        List<? extends Task> tasks
    );

    Map<String, Object> properties(Task task);
}
