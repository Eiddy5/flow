package org.cses.flow.core.services.flows.commands;

import org.cses.flow.core.services.Command;
import org.cses.flow.core.domains.flows.FlowDraft;

/**
 * Saves a FlowDraft by business key without parsing its raw YAML.
 */
public record SaveFlowDraftCommand(
    String key,
    Long expectedLockVersion,
    String raw
) implements Command<FlowDraft> {

    public static SaveFlowDraftCommand from(
        String key,
        Long expectedLockVersion,
        String raw
    ) {
        return new SaveFlowDraftCommand(key, expectedLockVersion, raw);
    }

    public SaveFlowDraftCommand {
        if (raw == null) {
            throw new IllegalArgumentException("FlowDraft raw must not be null");
        }
    }

    @Override
    public void validate() {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Flow key must not be blank");
        }
        if (expectedLockVersion != null && expectedLockVersion < 0) {
            throw new IllegalArgumentException(
                "FlowDraft expected lock version must not be negative"
            );
        }
    }
}
