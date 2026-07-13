package org.cses.flow.runtime.command;

import org.cses.flow.runtime.context.FlowContext;

public interface Command<T> {

    T execute(FlowContext flowContext);
}
