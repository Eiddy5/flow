package org.cses.flow.runtime.context;

import java.util.Objects;
import org.cses.flow.runtime.engine.EngineConfiguration;
import org.cses.flow.runtime.execution.OperationScheduler;
import org.cses.flow.runtime.session.RuntimeSession;

public record OperationContext(
        FlowContext flowContext,
        RuntimeSession runtimeSession,
        OperationScheduler scheduler,
        EngineConfiguration configuration) {

    public OperationContext {
        Objects.requireNonNull(flowContext, "flowContext");
        Objects.requireNonNull(runtimeSession, "runtimeSession");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(configuration, "configuration");
    }
}
