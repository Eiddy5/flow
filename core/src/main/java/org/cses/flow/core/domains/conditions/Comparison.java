package org.cses.flow.core.domains.conditions;

import org.cses.flow.core.domains.flows.DataType;

public interface Comparison {

    String symbol();

    boolean supports(DataType type, Object constant);

    boolean matches(Object actual, Object constant);
}
