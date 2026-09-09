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
 * Validates commands and saves domain changes without a business transaction.
 */
@Singleton
public class CommandExecutor {

    private JOOQ jooq;

    private CommandHandlerRegistry handlerRegistry;

    /**
     * Creates the command boundary with its named database and handler registry.
     * @param jooq named Flow database access
     * @param handlerRegistry command-to-handler mapping
     */
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
     * Loads and changes domains through a handler without opening a business transaction.
     * @param <S> session type
     * @param <U> session user type
     * @param <R> command result type
     * @param <C> command type
     * @param session current tenant and actor
     * @param command command to validate and apply
     * @return saved domain result
     * @throws RuntimeException when validation or persistence fails
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
        return FlowDatabase.execute(jooq.createDSLContext(),
                dsl -> handle(session, command, handler, dsl, null));
    }

    /**
     * Executes a domain command, then invokes its transport completion after persistence.
     * The callback uses the same context, without a shared business transaction.
     * @param <S> session type
     * @param <U> session user type
     * @param <R> command result type
     * @param <C> command type
     * @param session current tenant and actor
     * @param command command to validate and apply
     * @param completion transport action after the handler returns
     * @return saved domain result
     * @throws RuntimeException when validation, persistence or completion fails
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
        return FlowDatabase.execute(jooq.createDSLContext(),
                dsl -> handle(session, command, handler, dsl, completion));
    }

    /**
     * 绑定用户上下文后调用 Handler；仓储元数据由外层数据库入口自动管理。
     * @param <S> 会话类型
     * @param <U> 用户类型
     * @param <R> 结果类型
     * @param <C> 命令类型
     * @param session 已验证会话
     * @param command 已验证命令
     * @param handler 匹配的处理器
     * @param dsl 本次数据库操作上下文
     * @param completion 可空的保存后传输动作
     * @return Handler 的结果
     */
    private static <
            S extends Session<U>,
            U extends User,
            R,
            C extends Command<R>
            > R handle(
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
