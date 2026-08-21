package org.cses.flow.core.services;

import jakarta.inject.Singleton;
import jakarta.inject.Named;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Validates and dispatches commands inside a JOOQ-managed transaction.
 */
@Singleton
public final class CommandExecutor {

    private final JOOQ jooq;

    private final CommandHandlerRegistry handlerRegistry;

    public CommandExecutor(
        @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
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
        return jooq.runReturn(dsl -> inScope(
            session,
            command,
            handler,
            dsl,
            null
        ));
    }

    /**
     * Executes a command and completes one caller-owned transaction step
     * before committing it.
     *
     * <p>The completion is intended for transport acceptance that must share
     * the command write transaction. Domain command handlers remain unaware
     * of Queue infrastructure and still cannot nest CommandExecutor calls.</p>
     */
    public <
        S extends Session<U>,
        U extends User,
        R,
        C extends Command<R>
        > R execute(
        S session,
        C command,
        BiConsumer<R, org.jooq.DSLContext> completion
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(completion, "completion");
        command.validate();

        CommandHandler<S, U, R, C> handler =
            handlerRegistry.require(command);
        return jooq.runReturn(dsl -> inScope(
            session,
            command,
            handler,
            dsl,
            completion
        ));
    }

    private static <
        S extends Session<U>,
        U extends User,
        R,
        C extends Command<R>
        > R inScope(
        S session,
        C command,
        CommandHandler<S, U, R, C> handler,
        org.jooq.DSLContext dsl,
        BiConsumer<R, org.jooq.DSLContext> completion
    ) {
        Object previousSession = dsl.configuration().data(Session.class);
        Object previousContext = dsl.configuration().data(
            CommandContext.class
        );
        dsl.configuration().data(Session.class, session);
        CommandContext<S, U, R, C> context = CommandContext.from(
            command,
            session,
            dsl
        );
        dsl.configuration().data(CommandContext.class, context);
        try {
            R result = handler.handle(context);
            if (completion != null) {
                completion.accept(result, dsl);
            }
            return result;
        } finally {
            restore(dsl, Session.class, previousSession);
            restore(dsl, CommandContext.class, previousContext);
        }
    }

    private static void restore(
        org.jooq.DSLContext dsl,
        Class<?> key,
        Object previous
    ) {
        if (previous == null) {
            dsl.configuration().data().remove(key);
        } else {
            dsl.configuration().data(key, previous);
        }
    }
}
