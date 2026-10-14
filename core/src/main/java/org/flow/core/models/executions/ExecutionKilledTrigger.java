package org.flow.core.models.executions;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.flow.core.models.TenantInterface;
import org.flow.core.models.triggers.TriggerId;
import org.flow.core.utils.IdUtils;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * The Flow event for killing a trigger.
 */
@Getter
@SuperBuilder
@EqualsAndHashCode
@ToString
@NoArgsConstructor
public class ExecutionKilledTrigger extends ExecutionKilled implements TenantInterface {
    @NotNull
    @JsonInclude
    @Builder.Default
    protected String type = "trigger";

    String namespace;

    String flowId;

    String triggerId;

    public boolean isEqual(TriggerId triggerId) {
        return (triggerId.getTenantId() == null || Objects.equals(triggerId.getTenantId(), this.tenantId)) &&
            triggerId.getNamespace().equals(this.namespace) &&
            triggerId.getFlowId().equals(this.flowId) &&
            triggerId.getTriggerId().equals(this.triggerId);
    }

    @Override
    public String uid() {
        return IdUtils.fromParts(
            this.getTenantId(),
            this.getNamespace(),
            this.getFlowId(),
            this.getTriggerId()
        );
    }
}
