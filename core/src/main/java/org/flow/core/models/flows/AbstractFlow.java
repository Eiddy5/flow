package org.flow.core.models.flows;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.flow.core.models.Label;
import org.flow.core.models.tasks.WorkerSelector;
import org.flow.core.serializers.ListOrMapOfLabelDeserializer;
import org.flow.core.serializers.ListOrMapOfLabelSerializer;
import org.flow.core.validations.TenantId;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@JsonDeserialize
public abstract class AbstractFlow implements FlowInterface {
    @NotNull
    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]*")
    @Size(min = 1, max = 100)
    String id;

    @NotNull
    @Pattern(regexp = "^[a-z0-9][a-z0-9._-]*")
    @Size(min = 1, max = 150)
    String namespace;

    @Min(value = 1)
    Integer revision;

    @Schema(description = "The timestamp when this revision was created or last updated.")
    Instant updated;

    String description;

    @Valid
    List<Input<?>> inputs;

    @Valid
    List<Output> outputs;

    @NotNull
    @Builder.Default
    @Schema(
        title = "Whether the flow is disabled.",
        description = "A disabled flow does not run: its triggers are paused and new executions are rejected."
    )
    boolean disabled = false;

    @Getter
    @NotNull
    @Builder.Default
    boolean deleted = false;

    @NotNull
    @Builder.Default
    @Schema(
        description = "Whether this flow revision is a draft. Draft revisions are skipped when an execution starts without an explicit revision (webhooks, schedules, subflows, manual triggers). Executions can still target a draft by passing the revision explicitly."
    )
    boolean draft = false;

    @Hidden
    @TenantId
    String tenantId;

    @JsonSerialize(using = ListOrMapOfLabelSerializer.class)
    @JsonDeserialize(using = ListOrMapOfLabelDeserializer.class)
    @Schema(
        description = "Labels as a list of Label (key/value pairs) or as a map of string to string.",
        implementation = Object.class,
        oneOf = {
            Label[].class,
            Map.class
        }
    )
    @Valid
    List<Label> labels;

    @Schema(
        type = "object",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE
    )
    Map<String, Object> variables;

    @Valid
    @Schema(description = "Routing requirements (tags + fallback) for this flow.")
    private WorkerSelector workerSelector;
}
