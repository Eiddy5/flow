package org.flow.jdbc.runner;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.property.Property;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.core.utils.IdUtils;
import org.flow.plugin.core.debug.Return;

import jakarta.inject.Inject;

import static org.flow.core.utils.Rethrow.throwConsumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@FlowTest
abstract public class JdbcQueueTest {
    @Inject
    protected BroadcastQueueInterface<FlowInterface> flowQueue;

    @Inject
    protected DispatchQueueInterface<WorkerTaskResult> workerTaskResultQueue;

    @Test
    void noGroup() throws InterruptedException, QueueException {
        CountDownLatch countDownLatch = new CountDownLatch(2);

        flowQueue.addListener(throwConsumer(flow ->
        {
            if (flow.getNamespace().equals("org.flow.f1")) {
                flowQueue.emit(builder("org.flow.f2"));
            }

            countDownLatch.countDown();
        }));

        flowQueue.emit(builder("org.flow.f1"));

        assertTrue(countDownLatch.await(5, TimeUnit.SECONDS));

        assertThat(countDownLatch.getCount()).isEqualTo(0L);
    }

    @Test
    void withGroup() throws InterruptedException, QueueException {
        CountDownLatch countDownLatch = new CountDownLatch(2);

        flowQueue.addListener(throwConsumer(flow ->
        {
            if (flow.getNamespace().equals("org.flow.f1")) {
                flowQueue.emit(builder("org.flow.f2"));
            }

            countDownLatch.countDown();
        }));

        flowQueue.emit(builder("org.flow.f1"));

        assertTrue(countDownLatch.await(5, TimeUnit.SECONDS));

        assertThat(countDownLatch.getCount()).isEqualTo(0L);
    }

    @Test
    void withType() throws InterruptedException, QueueException {
        CountDownLatch countDownLatch = new CountDownLatch(2);
        flowQueue.addListener(throwConsumer(flow ->
        {
            if (flow.getNamespace().equals("org.flow.f1")) {
                // second one
                flowQueue.emit(builder("org.flow.f2"));
            }

            countDownLatch.countDown();
        }));

        // first one
        flowQueue.emit(builder("org.flow.f1"));

        assertTrue(countDownLatch.await(5, TimeUnit.SECONDS));

        assertThat(countDownLatch.getCount()).isEqualTo(0L);
    }

    // FIXME
    @Test
    void withGroupAndType() throws InterruptedException, QueueException {
        CountDownLatch countDownLatch = new CountDownLatch(2);
        flowQueue.addListener(throwConsumer(flow ->
        {
            if (flow.getNamespace().equals("org.flow.f1")) {
                flowQueue.emit(builder("org.flow.f2"));
            }

            countDownLatch.countDown();
        }));

        // first one
        flowQueue.emit(builder("org.flow.f1"));

        assertTrue(countDownLatch.await(5, TimeUnit.SECONDS));

        assertThat(countDownLatch.getCount()).isEqualTo(0L);
    }

    private static FlowWithSource builder(String namespace) {
        return FlowWithSource.builder()
            .id(IdUtils.create())
            .namespace(namespace == null ? "flow.test" : namespace)
            .tasks(Collections.singletonList(Return.builder().id("test").type(Return.class.getName()).format(Property.ofValue("test")).build()))
            .build();
    }
}
