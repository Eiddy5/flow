package org.cses.flow.core.commands.externaltasks;

import org.cses.flow.core.commands.shared.Command;
import org.cses.flow.core.domains.executions.Execution;

import java.util.Map;

public final class CompleteExternalTaskCommand
    implements Command<Execution> {

    private final String externalTaskId;
    private final Map<String, Object> outputs;

    public CompleteExternalTaskCommand(
        String externalTaskId,
        Map<String, Object> outputs
    ) {
        this.externalTaskId = externalTaskId;
        this.outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    public String externalTaskId() {
        return externalTaskId;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    @Override
    public void validate() {
        if (externalTaskId == null || externalTaskId.isBlank()) {
            throw new IllegalArgumentException(
                "ExternalTask id must not be blank"
            );
        }
    }
}
