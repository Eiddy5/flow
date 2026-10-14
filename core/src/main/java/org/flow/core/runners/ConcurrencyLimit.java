package org.flow.core.runners;

import org.flow.core.models.HasUID;
import org.flow.core.utils.IdUtils;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@AllArgsConstructor
@Builder
public class ConcurrencyLimit implements HasUID {
    @NotNull
    String tenantId;

    @NotNull
    String namespace;

    @NotNull
    String flowId;

    @With
    @PositiveOrZero
    Integer running;

    @Override
    public String uid() {
        return IdUtils.fromPartsAndSeparator('|', this.tenantId, this.namespace, this.flowId);
    }
}
