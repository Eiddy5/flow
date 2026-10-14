package org.flow.core.trace;

import java.util.Map;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.format.MapFormat;
import jakarta.validation.constraints.NotNull;

@ConfigurationProperties("flow.traces")
public record TracesConfiguration(
    @NotNull @Bindable(defaultValue = "DISABLED") TraceLevel root,

    @NotNull @Bindable(defaultValue = "{}")
    @MapFormat(transformation = MapFormat.MapTransformation.FLAT) Map<String, TraceLevel> categories) {
}
