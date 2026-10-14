package org.flow.core.runners.configuration;

import java.util.List;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Getter;

@Getter
@ConfigurationProperties("flow.variables")
public class VariableConfiguration {
    public static final int DEFAULT_MAX_RENDER_DEPTH = 16;
    public static final long DEFAULT_MAX_OUTPUT_SIZE = 10 * 1024 * 1024L; // 10 MiB

    public VariableConfiguration() {
        this.cacheEnabled = true;
        this.cacheSize = 1000;
        this.recursiveRendering = false;
        this.maxRenderDepth = DEFAULT_MAX_RENDER_DEPTH;
        this.maxOutputSize = DEFAULT_MAX_OUTPUT_SIZE;
        this.redactedEnvVars = List.of(
            "FLOW_PLUGINS_PATH", "FLOW_CONFIGURATION_PATH", "FLOW_CONFIGURATION", "FLOW_JAVA_OPTS"
        );
    }

    Boolean cacheEnabled;
    Integer cacheSize;
    Boolean recursiveRendering;
    Integer maxRenderDepth;
    Long maxOutputSize;
    List<String> redactedEnvVars;
}
