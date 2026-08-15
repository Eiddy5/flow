package org.cses.flow.core.commands.flows;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;
import org.cses.flow.core.domains.flows.FlowDraft;

/**
 * Creates or revises a FlowDraft without parsing its raw YAML.
 */
public record SaveFlowDraftCommand(
    String id,
    Long expectedLockVersion,
    String raw
) implements Command<FlowDraft> {

    public SaveFlowDraftCommand {
        if (raw == null) {
            throw new IllegalArgumentException("FlowDraft raw must not be null");
        }
    }

    @Override
    public void validate() {
        if (id == null) {
            if (expectedLockVersion != null) {
                throw new IllegalArgumentException("New FlowDraft must not declare an expected lock version");
            }
            return;
        }
        FlowCommandValidation.requireFlowId(id);
        if (expectedLockVersion == null || expectedLockVersion < 0) {
            throw new IllegalArgumentException("Existing FlowDraft save requires a non-negative " + "expected lock version");
        }
    }
}
