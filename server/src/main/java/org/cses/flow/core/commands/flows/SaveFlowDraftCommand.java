package org.cses.flow.core.commands.flows;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;
import org.cses.flow.core.domains.flows.FlowDraft;

/**
 * Creates or revises a FlowDraft without parsing its raw YAML.
 */
public final class SaveFlowDraftCommand
    implements Command<FlowDraft> {

    private final String id;
    private final Long expectedLockVersion;
    private final String raw;

    public SaveFlowDraftCommand(
        String id,
        Long expectedLockVersion,
        String raw
    ) {
        this.id = id;
        this.expectedLockVersion = expectedLockVersion;
        if (raw == null) {
            throw new IllegalArgumentException(
                "FlowDraft raw must not be null"
            );
        }
        this.raw = raw;
    }

    public String id() {
        return id;
    }

    public Long expectedLockVersion() {
        return expectedLockVersion;
    }

    public String raw() {
        return raw;
    }

    @Override
    public void validate() {
        if (id == null) {
            if (expectedLockVersion != null) {
                throw new IllegalArgumentException(
                    "New FlowDraft must not declare an expected lock version"
                );
            }
            return;
        }
        FlowCommandValidation.requireFlowId(id);
        if (expectedLockVersion == null || expectedLockVersion < 0) {
            throw new IllegalArgumentException(
                "Existing FlowDraft save requires a non-negative "
                    + "expected lock version"
            );
        }
    }
}
