package org.cses.flow.runtime.context;

public enum CommandContextState {
    OPEN,
    COMMITTED,
    ROLLED_BACK,
    CLOSED
}
