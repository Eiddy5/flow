package org.cses.flow.core.commands;

import org.paas.session.Session;
import org.paas.session.User;

/**
 * Executes one concrete command type.
 *
 * @param <S> concrete PAAS session type
 * @param <U> user type carried by the session
 * @param <R> command result type
 * @param <C> concrete command type
 */
public interface CommandHandler<
    S extends Session<U>,
    U extends User,
    R,
    C extends Command<R>
> {

    /**
     * Returns the exact command type handled by this handler.
     */
    Class<C> commandType();

    /**
     * Executes the command using the transaction-scoped context.
     */
    R handle(CommandContext<S, U, R, C> context);
}
