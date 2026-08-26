package org.cses.flow.core.commands.shared;

import org.cses.flow.core.services.*;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;
import org.x9.jooq.intf.JooqRunnableResult;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandExecutorTest {

    @Test
    void executesExactHandlerAndPreservesGenericResultType() {
        TextHandler handler = new TextHandler();
        CommandHandlerRegistry registry = new CommandHandlerRegistry(List.of(handler));
        DSLContext dsl = mockDsl();
        CommandExecutor executor = commandExecutor(mockJooq(dsl), registry);
        TestSession session = session("user-1");

        String result = executor.execute(session, new TextCommand("flow"));

        assertEquals("handled:flow", result);
        assertNotNull(handler.transactionalDsl);
        assertSame(session, handler.session);
        assertTrue(handler.sessionWasBound);
        assertTrue(handler.contextWasBound);
        assertNull(dsl.configuration().data(Session.class));
        assertNull(dsl.configuration().data(CommandContext.class));
    }

    @Test
    void validatesBeforeStartingHandler() {
        AtomicBoolean handled = new AtomicBoolean();
        RejectingHandler handler = new RejectingHandler(handled);
        CommandExecutor executor = commandExecutor(
            mockJooq(mockDsl()),
            new CommandHandlerRegistry(List.of(handler))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> executor.execute(session("user-1"), new RejectingCommand(""))
        );

        assertEquals("value must not be blank", exception.getMessage());
        assertFalse(handled.get());
    }

    @Test
    void completesTransportAcceptanceInsideTheCommandScope() {
        TextHandler handler = new TextHandler();
        DSLContext dsl = mockDsl();
        CommandExecutor executor = commandExecutor(
            mockJooq(dsl),
            new CommandHandlerRegistry(List.of(handler))
        );
        TestSession session = session("user-1");
        AtomicBoolean completed = new AtomicBoolean();

        String result = executor.execute(
            session,
            new TextCommand("flow"),
            (handled, transactionalDsl) -> {
                assertEquals("handled:flow", handled);
                assertSame(dsl, transactionalDsl);
                assertSame(
                    session,
                    transactionalDsl.configuration().data(Session.class)
                );
                assertNotNull(transactionalDsl.configuration().data(
                    CommandContext.class
                ));
                completed.set(true);
            }
        );

        assertEquals("handled:flow", result);
        assertTrue(completed.get());
        assertNull(dsl.configuration().data(Session.class));
        assertNull(dsl.configuration().data(CommandContext.class));
    }

    @Test
    void rejectsDuplicateHandlersForTheSameCommand() {
        TextHandler first = new TextHandler();
        TextHandler second = new TextHandler();

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> new CommandHandlerRegistry(List.of(first, second))
        );

        assertTrue(exception.getMessage().contains(TextCommand.class.getName()));
    }

    @Test
    void rejectsCommandWithoutHandler() {
        CommandHandlerRegistry registry = new CommandHandlerRegistry(List.of());

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> registry.require(new MissingCommand())
        );

        assertTrue(exception.getMessage().contains(MissingCommand.class.getName()));
    }

    private static DSLContext mockDsl() {
        MockConnection connection = new MockConnection(
            context -> new MockResult[0]
        );
        return DSL.using(connection, SQLDialect.POSTGRES);
    }

    private static JOOQ mockJooq(DSLContext dsl) {
        return new JOOQ(dsl.configuration(), null) {
            @Override
            public <T> T runReturn(JooqRunnableResult<T> runnable) {
                try {
                    return runnable.run(dsl);
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
    }

    private static CommandExecutor commandExecutor(
        JOOQ jooq,
        CommandHandlerRegistry registry
    ) {
        return new CommandExecutor(jooq, registry);
    }

    private static TestSession session(String userId) {
        User user = new User();
        user.setId(userId);
        TestSession session = new TestSession();
        session.setUser(user);
        return session;
    }

    private static final class TextCommand implements Command<String> {

        private final String value;

        private TextCommand(String value) {
            this.value = value;
        }

        private String value() {
            return value;
        }
    }

    private static final class TextHandler
        implements CommandHandler<TestSession, User, String, TextCommand> {

        private DSLContext transactionalDsl;
        private TestSession session;
        private boolean sessionWasBound;
        private boolean contextWasBound;

        @Override
        public Class<TextCommand> type() {
            return TextCommand.class;
        }

        @Override
        public String handle(
            CommandContext<TestSession, User, String, TextCommand> context
        ) {
            transactionalDsl = context.dsl();
            session = context.session();
            sessionWasBound = context.dsl().configuration()
                .data(Session.class) == context.session();
            contextWasBound = context.dsl().configuration()
                .data(CommandContext.class) == context;
            return "handled:" + context.command().value();
        }
    }

    private static final class RejectingCommand implements Command<Integer> {

        private final String value;

        private RejectingCommand(String value) {
            this.value = value;
        }

        @Override
        public void validate() {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("value must not be blank");
            }
        }
    }

    private static final class RejectingHandler
        implements CommandHandler<
            TestSession,
            User,
            Integer,
            RejectingCommand
        > {

        private final AtomicBoolean handled;

        private RejectingHandler(AtomicBoolean handled) {
            this.handled = handled;
        }

        @Override
        public Class<RejectingCommand> type() {
            return RejectingCommand.class;
        }

        @Override
        public Integer handle(
            CommandContext<TestSession, User, Integer, RejectingCommand> context
        ) {
            handled.set(true);
            return 1;
        }
    }

    private static final class MissingCommand implements Command<Void> {
    }

    private static final class TestSession extends Session<User> {
    }
}
