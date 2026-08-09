package org.cses.flow.core.plugins;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Root interface for every automatically discovered plugin.
 */
public interface Plugin {

    /**
     * Exact class identifier used in definitions and persistence.
     */
    @NotNull
    @JsonProperty(value = "type", access = JsonProperty.Access.READ_ONLY)
    default String getType() {
        return getClass().getCanonicalName();
    }
}
