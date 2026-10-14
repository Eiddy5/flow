package org.flow.core.models.flows.sla;

import java.time.Instant;

import org.flow.core.models.HasUID;
import org.flow.core.utils.IdUtils;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class SLAMonitor implements HasUID {
    String executionId;
    String slaId;
    Instant deadline;

    @Override
    public String uid() {
        return IdUtils.fromParts(executionId, slaId);
    }
}
