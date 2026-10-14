package org.flow.scheduler.utils;

import java.util.ArrayList;
import java.util.List;

import org.flow.core.models.triggers.TriggerEvaluationResult;
import org.flow.core.models.triggers.TriggerId;
import org.flow.core.scheduler.service.TriggerExecutionPublisher;

public class CollectorTriggerExecutionPublisher implements TriggerExecutionPublisher {

    List<PublishedExecution> executions = new ArrayList<>();

    @Override
    public void send(TriggerId triggerId, TriggerEvaluationResult evaluation) {
        this.executions.add(new PublishedExecution(triggerId, evaluation));
    }

    public List<PublishedExecution> executions() {
        return executions;
    }

    public void clear() {
        this.executions.clear();
    }

    public record PublishedExecution(TriggerId triggerId, TriggerEvaluationResult evaluation) {
    }
}
