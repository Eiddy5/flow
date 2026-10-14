package org.flow.plugin.core.trigger;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.flow.core.models.Label;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionTrigger;
import org.flow.core.models.flows.State;

public record WebhookResponse(
    String tenantId,
    String id,
    String namespace,
    String flowId,
    Integer flowRevision,
    ExecutionTrigger trigger,
    Map<String, Object> outputs,
    List<Label> labels,
    State state,
    URI url) {
    /**
     * Builds the webhook response from an execution.
     *
     * @param outputs the flow-level outputs of the execution, they are stored outside of the execution so they must be
     *                loaded by the caller via the {@link org.flow.core.services.ExecutionOutputService}.
     */
    public static WebhookResponse fromExecution(Execution execution, Map<String, Object> outputs, URI url) {
        return new WebhookResponse(
            execution.getTenantId(),
            execution.getId(),
            execution.getNamespace(),
            execution.getFlowId(),
            execution.getFlowRevision(),
            execution.getTrigger(),
            outputs,
            execution.getLabels(),
            execution.getState(),
            url
        );
    }
}