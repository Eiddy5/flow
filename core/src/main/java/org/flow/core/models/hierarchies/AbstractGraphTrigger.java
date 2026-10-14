package org.flow.core.models.hierarchies;

import org.flow.core.models.triggers.*;
import org.flow.core.scheduler.model.TriggerState;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@ToString
@Getter
public abstract class AbstractGraphTrigger extends AbstractGraph {
    @Setter
    private TriggerInterface triggerDeclaration;
    private final TriggerState trigger;

    public AbstractGraphTrigger(AbstractTrigger triggerDeclaration, TriggerState trigger) {
        super();

        this.triggerDeclaration = triggerDeclaration;
        this.trigger = trigger;
    }

    @Override
    public String getUid() {
        if (this.uid == null && this.triggerDeclaration != null) {
            return this.triggerDeclaration.getId();
        }

        return this.uid;
    }

    @Override
    public AbstractGraph forExecution() {
        this.setTriggerDeclaration(AbstractTriggerForExecution.of((AbstractTrigger) this.getTriggerDeclaration()));

        return this;
    }
}
