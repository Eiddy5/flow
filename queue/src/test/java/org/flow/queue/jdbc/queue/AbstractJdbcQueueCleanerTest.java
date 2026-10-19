package org.flow.queue.jdbc.queue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.queue.AbstractBroadcastQueueTest;
import org.flow.queue.jdbc.client.JdbcQueueCleaner;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public abstract class AbstractJdbcQueueCleanerTest {
    @Inject
    private JdbcQueueCleaner jdbcQueueCleaner;

    @Inject
    private BroadcastQueueInterface<AbstractBroadcastQueueTest.TestBroadcast> testQueue;

    @Test
    protected void shouldClean() throws QueueException {
        var message = new AbstractBroadcastQueueTest.TestBroadcast("key", 1);
        testQueue.emit(message);

        // Poll as some databases (e.g. MySQL) round the 'created' datetime to the next whole second,
        // which can push it briefly into the future relative to the cleaner's now()-based cutoff.
        // pollInSameThread() is required because of Micronaut's test-transaction support.
        long cleaned = await()
            .pollInSameThread()
            .atMost(Duration.ofSeconds(5))
            .until(jdbcQueueCleaner::deleteQueue, count -> count >= 1);

        assertThat(cleaned).isGreaterThanOrEqualTo(1);
    }
}
