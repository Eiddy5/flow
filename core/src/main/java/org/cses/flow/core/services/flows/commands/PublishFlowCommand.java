package org.cses.flow.core.services.flows.commands;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.services.Command;

/**
 * Saves one Flow definition state.
 *
 * <p>{@code draft=true} saves an editable source definition;
 * {@code draft=false} materializes and saves a deployed version.</p>
 */
public record PublishFlowCommand(
        String key,
        Long expectedLockVersion,
        String source,
        Boolean draft
) implements Command<Flow> {

    public PublishFlowCommand {
        draft = draft == null ? Boolean.TRUE : draft;
    }

    public static PublishFlowCommand from(String source) {
        return from(null, null, source, true);
    }

    public static PublishFlowCommand from(String key, String source) {
        return from(key, null, source, true);
    }

    public static PublishFlowCommand from(
            String key,
            Long expectedLockVersion,
            String source,
            Boolean draft
    ) {
        return new PublishFlowCommand(
                key,
                expectedLockVersion,
                source,
                draft
        );
    }

    public static PublishFlowCommand from(String key, boolean draft) {
        return from(key, null, null, draft);
    }

    @Override
    public void validate() {
        if (source == null && (key == null || key.isBlank())) {
            throw new IllegalArgumentException(
                    "Flow key or source must be provided"
            );
        }
        if (draft && source == null) {
            throw new IllegalArgumentException(
                    "Draft Flow source must not be null"
            );
        }
        if (expectedLockVersion != null && expectedLockVersion < 0) {
            throw new IllegalArgumentException(
                    "Flow expected lock version must not be negative"
            );
        }
        if (!draft && expectedLockVersion != null) {
            throw new IllegalArgumentException(
                    "Deployed Flow does not accept a draft lock version"
            );
        }
    }
}
