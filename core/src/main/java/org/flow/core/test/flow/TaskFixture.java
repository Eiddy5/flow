package org.flow.core.test.flow;

import java.util.List;
import java.util.Map;

import org.flow.core.models.assets.Asset;
import org.flow.core.models.flows.State;
import org.flow.core.models.property.Property;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskFixture {
    @NotNull
    private String id;

    private String value;

    @Builder.Default
    private State.Type state = State.Type.SUCCESS;

    private Map<String, Object> outputs;

    private List<Asset> assets;

    private Property<String> description;
}
