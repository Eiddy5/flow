package org.flow.core.models.hierarchies;

import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.scheduler.model.TriggerState;

public class GraphTrigger extends AbstractGraphTrigger {
    public GraphTrigger(AbstractTrigger triggerDeclaration, TriggerState trigger) {
        super(triggerDeclaration, trigger);
    }
}
