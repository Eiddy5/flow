package org.flow.core.runners;

import java.util.List;
import java.util.concurrent.CompletionStage;

import org.flow.core.models.executions.LogEntry;
import org.flow.core.queues.DispatchQueueInterface;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
@Requires(property = "flow.queue.type")
public class DefaultLogEntryEmitter implements LogEntryEmitter {

    private final DispatchQueueInterface<LogEntry> logQueue;

    @Inject
    public DefaultLogEntryEmitter(DispatchQueueInterface<LogEntry> logQueue) {
        this.logQueue = logQueue;
    }

    @Override
    public CompletionStage<Void> emits(LogEntry entry) {
        return this.logQueue.emitAsync(entry);
    }

    @Override
    public CompletionStage<Void> emits(List<LogEntry> entries) {
        return this.logQueue.emitAsync(entries);
    }
}
