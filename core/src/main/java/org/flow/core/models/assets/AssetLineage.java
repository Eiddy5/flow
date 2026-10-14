package org.flow.core.models.assets;

import java.time.Instant;
import java.util.List;

import org.flow.core.models.flows.State;

public record AssetLineage(
    String tenantId,
    String namespace,
    String flowId,
    Integer flowRevision,
    String executionId,
    String taskId,
    String taskRunId,
    State.Type state,
    Instant startDate,
    Instant endDate,
    List<Asset> inputs,
    List<Asset> outputs,
    Instant timestamp) {
}
