package org.cses.flow.core.commands.shared;

import io.micronaut.context.annotation.Context;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a handler by the exact runtime class of a command.
 */
@Context
public final class CommandHandlerRegistry {

    private final Map<Class<?>, CommandHandler<?, ?, ?, ?>> handlers;

    public CommandHandlerRegistry(
        Collection<CommandHandler<?, ?, ?, ?>> discoveredHandlers
    ) {
        Objects.requireNonNull(discoveredHandlers, "discoveredHandlers");

        Map<Class<?>, CommandHandler<?, ?, ?, ?>> registered =
            new LinkedHashMap<>();
        for (CommandHandler<?, ?, ?, ?> handler : discoveredHandlers) {
            Objects.requireNonNull(handler, "discoveredHandlers contains null");
            Class<?> commandType = Objects.requireNonNull(
                handler.commandType(),
                "handler commandType"
            );
            CommandHandler<?, ?, ?, ?> existing = registered.putIfAbsent(
                commandType,
                handler
            );
            if (existing != null) {
                throw new IllegalStateException(
                    "Multiple command handlers registered for " + commandType.getName()
                );
            }
        }
        handlers = Map.copyOf(registered);
    }

    /**
     * Returns the handler whose declared command type exactly matches the command class.
     */
    @SuppressWarnings("unchecked")
    public <
        S extends Session<U>,
        U extends User,
        R,
        C extends Command<R>
    > CommandHandler<S, U, R, C> require(C command) {

        Objects.requireNonNull(command, "command");

        CommandHandler<S, U, R, C> handler =
            (CommandHandler<S, U, R, C>) handlers.get(command.getClass());
        if (handler == null) {
            throw new IllegalStateException(
                "No command handler registered for " + command.getClass().getName()
            );
        }
        return handler;
    }
}
