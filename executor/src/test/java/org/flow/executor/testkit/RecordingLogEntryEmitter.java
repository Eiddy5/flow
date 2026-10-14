package org.flow.executor.testkit;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import org.flow.core.models.executions.LogEntry;
import org.flow.core.runners.LogEntryEmitter;

/**
 * In-memory {@link LogEntryEmitter} that records every emitted log entry for assertions.
 */
public class RecordingLogEntryEmitter implements LogEntryEmitter {
    private final List<LogEntry> emitted = new CopyOnWriteArrayList<>();

    @Override
    public CompletionStage<Void> emits(LogEntry entry) {
        emitted.add(entry);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> emits(List<LogEntry> entries) {
        emitted.addAll(entries);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Every log entry emitted, in emission order.
     */
    public List<LogEntry> emitted() {
        return List.copyOf(emitted);
    }
}
