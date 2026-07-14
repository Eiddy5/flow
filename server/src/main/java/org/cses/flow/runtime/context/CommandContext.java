package org.cses.flow.runtime.context;

import java.util.Objects;
import org.cses.flow.definition.session.DefinitionSession;
import org.cses.flow.runtime.execution.ExecutionQueue;
import org.cses.flow.runtime.execution.OperationScheduler;
import org.cses.flow.runtime.engine.EngineConfiguration;
import org.cses.flow.runtime.session.EngineTransaction;
import org.cses.flow.runtime.session.RuntimeSession;

public final class CommandContext implements AutoCloseable {

    private final EngineTransaction transaction;
    private final DefinitionSession definitionSession;
    private final RuntimeSession runtimeSession;
    private final ExecutionQueue executionQueue = new ExecutionQueue();
    private final OperationScheduler operationScheduler = new OperationScheduler(executionQueue);
    private final EngineConfiguration configuration;
    private CommandContextState state = CommandContextState.OPEN;
    private FlowContext flowContext;
    private Object result;
    private Throwable failure;

    CommandContext(
            EngineTransaction transaction,
            DefinitionSession definitionSession,
            RuntimeSession runtimeSession,
            EngineConfiguration configuration) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.definitionSession = Objects.requireNonNull(definitionSession, "definitionSession");
        this.runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public ExecutionQueue executionQueue() { return executionQueue; }
    public DefinitionSession definitionSession() { return definitionSession; }
    public RuntimeSession runtimeSession() { return runtimeSession; }

    public EngineConfiguration configuration() {
        return configuration;
    }

    public OperationContext operationContext() {
        return new OperationContext(flowContext(), runtimeSession, operationScheduler, configuration);
    }

    public void bindFlowContext(FlowContext flowContext) {
        requireOpen();
        if (this.flowContext != null) {
            throw new IllegalStateException("CommandContext already has a FlowContext");
        }
        this.flowContext = Objects.requireNonNull(flowContext, "flowContext");
    }

    public FlowContext flowContext() {
        if (flowContext == null) {
            throw new IllegalStateException("CommandContext has no FlowContext");
        }
        return flowContext;
    }

    public void setResult(Object result) { this.result = result; }

    @SuppressWarnings("unchecked")
    public <T> T result() { return (T) result; }

    public void commit() {
        requireOpen();
        transaction.commit();
        state = CommandContextState.COMMITTED;
    }

    public void rollback(Throwable failure) {
        requireOpen();
        this.failure = Objects.requireNonNull(failure, "failure");
        transaction.rollback();
        state = CommandContextState.ROLLED_BACK;
    }

    public CommandContextState state() { return state; }
    public Throwable failure() { return failure; }

    @Override
    public void close() {
        if (state == CommandContextState.OPEN) {
            throw new IllegalStateException("Open CommandContext must commit or rollback before close");
        }
        if (state == CommandContextState.CLOSED) {
            return;
        }
        transaction.close();
        state = CommandContextState.CLOSED;
    }

    private void requireOpen() {
        if (state != CommandContextState.OPEN) {
            throw new IllegalStateException("CommandContext must be OPEN but was " + state);
        }
    }
}
