package org.flow.core.runners;

import org.flow.core.models.HasUID;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.Flow;
import org.flow.core.queues.event.DispatchEvent;
import org.flow.core.utils.IdUtils;

public record MultipleConditionEvent(Flow flow, Execution execution) implements HasUID, DispatchEvent {
    @Override
    public String uid() {
        return IdUtils.fromParts(flow.uidWithoutRevision(), execution.getId());
    }

    @Override
    public String key() {
        return uid();
    }
}
