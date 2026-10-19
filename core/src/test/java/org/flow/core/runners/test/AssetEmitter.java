package org.flow.core.runners.test;

import org.flow.plugin.api.annotations.Plugin;
import org.flow.plugin.api.annotations.PluginProperty;
import org.flow.core.models.tasks.*;
import org.flow.core.runners.AssetEmit;
import org.flow.core.runners.RunContext;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin
public class AssetEmitter extends Task implements RunnableTask<VoidOutput> {
    @NotNull
    @PluginProperty
    private AssetEmit assetsToEmit;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        runContext.assets().emit(assetsToEmit);
        return null;
    }
}
