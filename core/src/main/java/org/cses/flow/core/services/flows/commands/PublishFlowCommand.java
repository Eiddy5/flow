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
        String source,
        Boolean draft
) implements Command<Flow> {

    public PublishFlowCommand {
        draft = draft == null ? Boolean.TRUE : draft;
    }

    public static PublishFlowCommand from(String source) {
        return from(null, source, true);
    }

    public static PublishFlowCommand from(String key, String source) {
        return from(key, source, true);
    }

    public static PublishFlowCommand from(
            String key,
            String source,
            Boolean draft
    ) {
        return new PublishFlowCommand(
                key,
                source,
                draft
        );
    }

    public static PublishFlowCommand from(String key, boolean draft) {
        return from(key, null, draft);
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
    }
}
