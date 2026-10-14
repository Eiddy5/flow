package org.flow.core.models.assets;

import java.time.Instant;

import org.flow.core.models.HasUID;
import org.flow.core.models.flows.FlowId;
import org.flow.core.models.flows.State;
import org.flow.core.utils.IdUtils;

/**
 * Represents an entity that used an asset
 */
public record AssetUser(String tenantId, String namespace, String flowId, Integer flowRevision, String executionId, String taskId, String taskRunId,
    State.Type state, Instant startDate, Instant endDate) implements HasUID {
    public String uid() {
        return IdUtils.fromParts(tenantId, namespace, flowId, String.valueOf(flowRevision), executionId, taskRunId);
    }

    public FlowId toFlowId() {
        return FlowId.of(tenantId, namespace, flowId, flowRevision);
    }
}
