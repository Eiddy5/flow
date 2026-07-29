package org.cses.flow.core.commands.shared;

public final class FlowCommandValidation {

    private FlowCommandValidation() {
    }

    public static void requireFlowKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Flow key must not be blank");
        }
    }

    public static void requireFlowId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                "Flow id must not be blank"
            );
        }
    }
}
