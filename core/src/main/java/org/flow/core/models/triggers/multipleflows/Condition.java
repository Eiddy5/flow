package org.flow.core.models.triggers.multipleflows;

import org.flow.core.exceptions.InternalException;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.utils.Rethrow;

public interface Condition extends Rethrow.PredicateChecked<ConditionContext, InternalException> {
}
