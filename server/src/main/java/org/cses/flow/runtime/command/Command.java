package org.cses.flow.runtime.command;

import org.cses.flow.runtime.context.CommandContext;

@FunctionalInterface
public interface Command<T> {

    T execute(CommandContext context);
}
