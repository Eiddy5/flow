package org.cses.flow.core.commands;

import lombok.Getter;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Objects;

/**
 * Resources that belong to one command execution.
 *
 * @param <S> concrete PAAS session type
 * @param <U> user type carried by the session
 * @param <R> command result type
 * @param <C> concrete command type
 */
@Getter
public final class CommandContext<
    S extends Session<U>,
    U extends User,
    R,
    C extends Command<R>
> {

    private final C command;
    private final S session;
    private final DSLContext dsl;

    public CommandContext(C command, S session, DSLContext dsl) {
        this.command = Objects.requireNonNull(command, "command");
        this.session = Objects.requireNonNull(session, "session");
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

}
