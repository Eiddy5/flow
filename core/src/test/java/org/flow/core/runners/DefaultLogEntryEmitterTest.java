package org.flow.core.runners;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.flow.core.models.executions.LogEntry;
import org.flow.core.queues.DispatchQueueInterface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultLogEntryEmitterTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completesOnlyAfterLogQueueAcknowledges(boolean batch) {
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        CompletableFuture<Void> emission = emit(batch, acknowledgement);

        assertThat(emission).isNotDone();
        acknowledgement.complete(null);
        assertThat(emission).isCompleted();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesLogQueueFailure(boolean batch) {
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        CompletableFuture<Void> emission = emit(batch, acknowledgement);
        IllegalStateException failure = new IllegalStateException("queue unavailable");

        acknowledgement.completeExceptionally(failure);
        assertThatThrownBy(emission::join)
            .isInstanceOf(CompletionException.class)
            .hasCause(failure);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> emit(boolean batch, CompletableFuture<Void> acknowledgement) {
        DispatchQueueInterface<LogEntry> queue = mock(DispatchQueueInterface.class);
        LogEntry entry = LogEntry.builder().message("log entry").build();
        DefaultLogEntryEmitter emitter = new DefaultLogEntryEmitter(queue);
        if (batch) {
            List<LogEntry> entries = List.of(entry);
            when(queue.emitAsync(entries)).thenReturn(acknowledgement);
            return emitter.emits(entries).toCompletableFuture();
        }
        when(queue.emitAsync(entry)).thenReturn(acknowledgement);
        return emitter.emits(entry).toCompletableFuture();
    }
}
