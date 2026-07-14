package org.cses.flow.runtime.context;

import java.util.Map;
import java.util.Objects;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.model.Node;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;

public record ActivityContext(
        Flow flow,
        Process process,
        Executor executor,
        Node node,
        Activity activity) {

    public ActivityContext {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(activity, "activity");
    }

    public Map<String, Object> processVariables() {
        return process.variables();
    }
}
