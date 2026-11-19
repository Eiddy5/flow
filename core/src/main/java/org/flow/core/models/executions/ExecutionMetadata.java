package org.flow.core.models.executions;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.With;

@Builder(toBuilder = true)
@Setter
@Getter
public class ExecutionMetadata {
    @Builder.Default
    @With
    Integer attemptNumber = 1;

    @NotNull
    Instant originalCreatedDate;

    /**
     * The number of Subflow/Flow-trigger hops between this execution and the root execution that
     * started the chain, incremented by one at each hop.
     * Null for a root execution which is treated as depth 0.
     */
    @With
    Integer executionDepth;

    public ExecutionMetadata nextAttempt() {
        return this.toBuilder()
            .attemptNumber(this.attemptNumber + 1)
            .build();
    }

    /**
     * Returns {@link #executionDepth}, defaulting to 0 for a root execution or one predating this field.
     */
    public int executionDepthOrZero() {
        return this.executionDepth == null ? 0 : this.executionDepth;
    }
}
