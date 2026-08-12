package org.cses.flow.infrastructure.jooq;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.x9.jooq.JOOQ;

/**
 * Matches only when the Flow-owned, named JOOQ facade is available.
 */
public final class FlowJooqCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        boolean present = context.getBeanContext().containsBean(
            JOOQ.class,
            Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
        );
        if (!present) {
            context.fail("The named Flow JOOQ facade is not available");
        }
        return present;
    }
}
