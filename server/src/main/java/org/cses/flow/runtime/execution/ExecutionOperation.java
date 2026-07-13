package org.cses.flow.runtime.execution;

import org.cses.flow.runtime.context.FlowContext;

public interface ExecutionOperation {

    void execute(FlowContext flowContext);
}
