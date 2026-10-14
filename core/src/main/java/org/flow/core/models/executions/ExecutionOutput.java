package org.flow.core.models.executions;

import org.flow.core.models.HasUID;

public record ExecutionOutput(String executionId, String tenantId, byte[] value, String uri) implements HasUID {
    @Override
    public String uid() {
        return executionId;
    }
}
