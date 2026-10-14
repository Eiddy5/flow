package org.flow.core.runners;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.flow.core.runners.configuration.VariableConfiguration;

import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;

@Singleton
public class RunContextCache {
    @Inject
    private VariableConfiguration variableConfiguration;

    @Inject
    private ApplicationContext applicationContext;

    @Getter
    private Map<?, ?> globalVars = null;

    @Getter
    private Map<String, String> envVars = null;

    @PostConstruct
    void init() {
        String envPrefix = applicationContext.getProperty("flow.variables.env-vars-prefix", String.class, "ENV_");
        envVars = this.envVariables(envPrefix);

        globalVars = applicationContext
            .getProperty("flow.variables.globals", Map.class)
            .orElseGet(Map::of);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Map<String, String> envVariables(String envPrefix) {
        Map<String, String> result = new HashMap<>(System.getenv());
        result.putAll((Map) System.getProperties());

        return result
            .entrySet()
            .stream()
            .filter(e -> !variableConfiguration.getRedactedEnvVars().contains(e.getKey()) && e.getKey().startsWith(envPrefix))
            .map(
                e -> new AbstractMap.SimpleEntry<>(
                    e.getKey().substring(envPrefix.length()).toLowerCase(),
                    e.getValue()
                )
            )
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
