package org.cses.flow.core.commands;

import jakarta.inject.Singleton;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.Objects;

/**
 * Validates and dispatches commands inside a JOOQ-managed transaction.
 */
@Singleton
public final class CommandExecutor {

    private final JOOQ jooq;

    private final CommandHandlerRegistry handlerRegistry;

    public CommandExecutor(
        JOOQ jooq,
        CommandHandlerRegistry handlerRegistry
    ) {
        this.jooq = Objects.requireNonNull(jooq, "jooq");
        this.handlerRegistry = Objects.requireNonNull(
            handlerRegistry,
            "handlerRegistry"
        );
    }

    /**
     * Executes a command and preserves its generic result type.
     */
    public <
        S extends Session<U>,
        U extends User,
        R,
        C extends Command<R>
        > R execute(
        S session,
        C command
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(command, "command");
        command.validate();

        CommandHandler<S, U, R, C> handler =
            handlerRegistry.require(command);
        return jooq.runReturn(dsl -> {
            Object previousSession = dsl.configuration().data(Session.class);
            Object previousContext = dsl.configuration().data(
                CommandContext.class
            );
            dsl.configuration().data(Session.class, session);
            CommandContext<S, U, R, C> context = new CommandContext<>(
                command,
                session,
                dsl
            );
            dsl.configuration().data(CommandContext.class, context);
            try {
                return handler.handle(context);
            } finally {
                if (previousSession == null) {
                    dsl.configuration().data().remove(Session.class);
                } else {
                    dsl.configuration().data(
                        Session.class,
                        previousSession
                    );
                }
                if (previousContext == null) {
                    dsl.configuration().data().remove(CommandContext.class);
                } else {
                    dsl.configuration().data(
                        CommandContext.class,
                        previousContext
                    );
                }
            }
        });
    }
}
