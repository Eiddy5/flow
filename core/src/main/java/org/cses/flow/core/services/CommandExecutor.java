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
     * 通过处理器加载并修改领域，不创建 CAS 作用域或业务事务。
     * @param <S> 会话类型
     * @param <U> 会话用户类型
     * @param <R> 命令结果类型
     * @param <C> 命令类型
     * @param session 当前租户与操作者
     * @param command 待校验并执行的命令
     * @return 已保存的领域结果
     * @throws RuntimeException 校验或持久化失败时抛出
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
        return handle(session, command, handler, jooq.createDSLContext(), null);
    }

    /**
     * 执行领域命令并在保存后调用传输回调；复用上下文，但不共享业务事务。
     * @param <S> 会话类型
     * @param <U> 会话用户类型
     * @param <R> 命令结果类型
     * @param <C> 命令类型
     * @param session 当前租户与操作者
     * @param command 待校验并执行的命令
     * @param completion 处理器返回后的传输动作
     * @return 已保存的领域结果
     * @throws RuntimeException 校验、持久化或回调失败时抛出
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
        return handle(session, command, handler, jooq.createDSLContext(), completion);
    }

    /**
     * 绑定用户上下文后调用 Handler，最后恢复原上下文；不建立 CAS 会话或数据库事务。
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
