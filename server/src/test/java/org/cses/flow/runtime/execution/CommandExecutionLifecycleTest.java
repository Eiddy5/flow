package org.cses.flow.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.session.DefinitionSession;
import org.cses.flow.runtime.context.CommandContextFactory;
import org.cses.flow.runtime.behavior.ActivityBehaviorRegistry;
import org.cses.flow.runtime.context.ResumeTarget;
import org.cses.flow.runtime.engine.EngineConfiguration;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.Task;
import org.cses.flow.runtime.session.EngineSessionFactory;
import org.cses.flow.runtime.session.EngineTransaction;
import org.cses.flow.runtime.session.RuntimeSession;
import org.junit.jupiter.api.Test;

final class CommandExecutionLifecycleTest {

    @Test
    void executesOperationsInFifoOrderAndCommitsOnce() {
        RecordingSessionFactory sessions = new RecordingSessionFactory();
        CommandExecutor executor = new CommandExecutor(
                new CommandContextFactory(sessions, configuration()),
                new ExecutionRunner());
        List<String> events = new ArrayList<>();

        String result = executor.execute(context -> {
            events.add("command");
            context.executionQueue().plan(ignored -> events.add("first"));
            context.executionQueue().plan(ignored -> events.add("second"));
            return "done";
        });

        assertEquals("done", result);
        assertEquals(List.of("command", "first", "second"), events);
        assertEquals(1, sessions.transaction.commitCount);
        assertEquals(0, sessions.transaction.rollbackCount);
        assertEquals(1, sessions.transaction.closeCount);
    }

    @Test
    void rollsBackOnceAndStopsConsumingWhenAnOperationFails() {
        RecordingSessionFactory sessions = new RecordingSessionFactory();
        CommandExecutor executor = new CommandExecutor(
                new CommandContextFactory(sessions, configuration()),
                new ExecutionRunner());
        List<String> events = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                executor.execute(context -> {
                    events.add("command");
                    context.executionQueue().plan(ignored -> {
                        events.add("failing");
                        throw new IllegalStateException("boom");
                    });
                    context.executionQueue().plan(ignored -> events.add("must-not-run"));
                    return "unreachable";
                }));

        assertEquals("boom", failure.getMessage());
        assertEquals(List.of("command", "failing"), events);
        assertEquals(0, sessions.transaction.commitCount);
        assertEquals(1, sessions.transaction.rollbackCount);
        assertEquals(1, sessions.transaction.closeCount);
    }

    private EngineConfiguration configuration() {
        return new EngineConfiguration(
                prefix -> prefix + "-id",
                new ActivityBehaviorRegistry(Map.of()));
    }

    private static final class RecordingSessionFactory implements EngineSessionFactory {

        private final RecordingTransaction transaction = new RecordingTransaction();

        @Override
        public EngineTransaction openTransaction() {
            return transaction;
        }

        @Override
        public DefinitionSession openDefinitionSession(EngineTransaction transaction) {
            return new DefinitionSession() {
                @Override
                public Flow resolveStartFlow(String requestedFlowId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Flow loadBoundFlow(String flowId) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public RuntimeSession openRuntimeSession(EngineTransaction transaction) {
            return new RuntimeSession() {
                @Override
                public ResumeTarget loadResumeTarget(String taskId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void insert(Process process) {
                }

                @Override
                public void insert(Activity activity) {
                }

                @Override
                public void insert(Task task) {
                }

                @Override
                public void update(Process process) {
                }

                @Override
                public void update(Activity activity) {
                }

                @Override
                public void update(Task task) {
                }
            };
        }
    }

    private static final class RecordingTransaction implements EngineTransaction {

        private int commitCount;
        private int rollbackCount;
        private int closeCount;

        @Override
        public void commit() {
            commitCount++;
        }

        @Override
        public void rollback() {
            rollbackCount++;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
