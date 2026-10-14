package org.flow.worker.processors.internals;

import java.util.Optional;

import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.flows.State;
import org.flow.core.models.triggers.PollingTriggerInterface;
import org.flow.core.models.triggers.TriggerContext;
import org.flow.core.models.triggers.TriggerEvaluationResult;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.WorkerTrigger;

import lombok.Getter;

import static org.flow.core.models.flows.State.Type.SUCCESS;

public class WorkerTriggerCallable extends AbstractWorkerTriggerCallable {
    PollingTriggerInterface pollingTrigger;
    ConditionContext conditionContext;
    TriggerContext triggerContext;

    // Initialized to empty so getEvaluate() is never null when the evaluation ends
    // before the assignment (killed, timed out, or failed).
    @Getter
    Optional<TriggerEvaluationResult> evaluate = Optional.empty();

    public WorkerTriggerCallable(RunContext runContext, ConditionContext conditionContext, TriggerContext triggerContext, WorkerTrigger workerTrigger, PollingTriggerInterface pollingTrigger) {
        super(runContext, pollingTrigger.getClass().getName(), workerTrigger);
        this.pollingTrigger = pollingTrigger;
        this.conditionContext = conditionContext;
        this.triggerContext = triggerContext;
    }

    @Override
    public State.Type doCall() throws Exception {
        this.evaluate = this.pollingTrigger.eval(conditionContext.withRunContext(runContext), triggerContext);
        return SUCCESS;
    }
}
