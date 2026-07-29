package org.cses.flow.core.commands.flows;

import org.cses.flow.core.commands.shared.Command;
import org.cses.flow.core.commands.shared.FlowCommandValidation;
import org.cses.flow.core.domains.flows.FlowWithSource;

/**
 * Creates a Flow source or revises its raw YAML without parsing it.
 */
public final class SaveFlowSourceCommand
    implements Command<FlowWithSource> {

    private final String id;
    private final Long expectedLockVersion;
    private final String raw;

    public SaveFlowSourceCommand(
        String id,
        Long expectedLockVersion,
        String raw
    ) {
        this.id = id;
        this.expectedLockVersion = expectedLockVersion;
        if (raw == null) {
            throw new IllegalArgumentException(
                "Flow source raw must not be null"
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
                    "New Flow source must not declare an expected lock version"
                );
            }
            return;
        }
        FlowCommandValidation.requireFlowId(id);
        if (expectedLockVersion == null || expectedLockVersion < 0) {
            throw new IllegalArgumentException(
                "Existing Flow source save requires a non-negative "
                    + "expected lock version"
            );
        }
    }
}
