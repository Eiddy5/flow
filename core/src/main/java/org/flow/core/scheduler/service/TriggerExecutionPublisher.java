package org.flow.core.scheduler.service;

import org.flow.core.models.triggers.TriggerEvaluationResult;
import org.flow.core.models.triggers.TriggerId;

/**
 * Interface for publishing trigger execution events.
 */
public interface TriggerExecutionPublisher {

    /**
     * Publish the execution produced by a trigger evaluation.
     *
     * @param triggerId  the trigger identifier providing the tenant, namespace and flow.
     * @param evaluation the trigger evaluation result to publish.
     */
    void send(final TriggerId triggerId, final TriggerEvaluationResult evaluation);
}
