package org.flow.core.models.assets;

import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.models.tasks.Output;
import org.flow.core.runners.RunContext;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

import static org.flow.core.utils.RegexPatterns.JAVA_IDENTIFIER_REGEX;

@Plugin
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
public abstract class AssetExporter<T extends Output> implements org.flow.plugin.api.Plugin {
    @NotNull
    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_-]*")
    protected String id;

    @NotBlank
    @Pattern(regexp = JAVA_IDENTIFIER_REGEX)
    protected String type;

    public abstract T sendAssets(RunContext runContext, Flux<AssetLineage> records) throws Exception;

}
