package org.cses.flow.definition.session;

import org.cses.flow.definition.model.Flow;

public interface DefinitionSession {

    Flow resolveStartFlow(String requestedFlowId);

    Flow loadBoundFlow(String flowId);
}
