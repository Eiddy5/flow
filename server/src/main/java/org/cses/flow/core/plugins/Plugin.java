package org.cses.flow.core.plugins;

/**
 * Root interface for every automatically discovered plugin.
 */
public interface Plugin {

    /**
     * Stable identifier within one extension point.
     */
    String type();

    /**
     * Interface whose implementations share one type namespace.
     */
    default Class<? extends Plugin> extensionPoint() {
        return Plugin.class;
    }
}
