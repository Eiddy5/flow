package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;

import java.util.List;
import java.util.Map;

/**
 * Materializes one registered Task type without owning Flow identity rules.
 */
public interface TaskPlugin {

    String type();

    Task create(
        String id,
        String parentId,
        String key,
        List<Input> inputs,
        List<Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        Map<String, ?> properties,
        List<? extends Task> tasks
    );

    Task rehydrate(
        String id,
        String parentId,
        String key,
        List<Input> inputs,
        List<Output> outputs,
        RouteExpression route,
        List<String> dependOn,
        Map<String, ?> properties,
        List<? extends Task> tasks
    );

    Map<String, Object> properties(Task task);
}
