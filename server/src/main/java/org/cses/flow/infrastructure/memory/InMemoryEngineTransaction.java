package org.cses.flow.infrastructure.memory;

import java.util.Objects;
import org.cses.flow.runtime.session.EngineTransaction;

public final class InMemoryEngineTransaction implements EngineTransaction {

    private final InMemoryRuntimeState state;
    private final InMemoryRuntimeState.Snapshot snapshot;
    private boolean completed;
    private boolean closed;

    InMemoryEngineTransaction(InMemoryRuntimeState state) {
        this.state = Objects.requireNonNull(state, "state");
        this.snapshot = state.snapshot();
    }

    InMemoryRuntimeState.Snapshot snapshot() {
        if (completed || closed) {
            throw new IllegalStateException("Transaction is no longer active");
        }
        return snapshot;
    }

    @Override
    public void commit() {
        requireActive();
        state.publish(snapshot);
        completed = true;
    }

    @Override
    public void rollback() {
        requireActive();
        completed = true;
    }

    @Override
    public void close() {
        if (!completed) {
            throw new IllegalStateException("Transaction must commit or rollback before close");
        }
        closed = true;
    }

    private void requireActive() {
        if (completed || closed) {
            throw new IllegalStateException("Transaction is no longer active");
        }
    }
}
