package org.cses.flow.core.services;

import org.cses.flow.common.Fields;

import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.SessionUser;

import java.util.Objects;

/**
 * Resources that belong to one command execution.
 *
 * @param <S> concrete PAAS session type
 * @param <U> user type carried by the session
 * @param <R> command result type
 * @param <C> concrete command type
 */
public record CommandContext<
        S extends Session<U>,
        U extends SessionUser,
        R,
        C extends Command<R>
        >(C command, S session, DSLContext dsl) {

    public static <
            S extends Session<U>,
            U extends SessionUser,
            R,
            C extends Command<R>
        > CommandContext<S, U, R, C> from(
            C command,
            S session,
            DSLContext dsl
        ) {
        return new CommandContext<>(command, session, dsl);
    }

    public CommandContext(C command, S session, DSLContext dsl) {
        this.command = Objects.requireNonNull(command, "command");
        this.session = Objects.requireNonNull(session, "session");
        this.dsl = Objects.requireNonNull(dsl, Fields.DSL);
    }

}
