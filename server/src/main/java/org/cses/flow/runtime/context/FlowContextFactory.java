package org.cses.flow.runtime.context;

import org.cses.flow.definition.model.Flow;
import org.cses.flow.runtime.model.Process;

public class FlowContextFactory {

    public FlowContext create(Flow flow) {
        return new FlowContext(flow, null);
    }

    public FlowContext create(Flow flow, Process process) {
        return new FlowContext(flow, process);
    }
}
