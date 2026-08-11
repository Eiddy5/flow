package org.cses.flow.infrastructure.queues;

import io.micronaut.json.JsonMapper;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.queues.DispatchQueue;
import org.cses.flow.queues.QueueException;
import org.cses.flow.queues.QueueSubscription;
import org.cses.flow.queues.event.DispatchEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonFactory;
import org.paas.json.SerializableObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.flow.gen.flow.Tables.DISPATCH_QUEUE_MESSAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(
    named = "FLOW_POSTGRES_TEST_URL",
    matches = ".+"
)
final class DefaultDispatchQueueIntegrationTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    private final PostgresJooqTestAdapter database =
        PostgresJooqTestAdapter.fromEnvironment();
    private final String queuePrefix =
        "default-queue-test-" + StringUtil.newId();
    private final Set<DefaultDispatchQueue<TestEvent>> queues =
        ConcurrentHashMap.newKeySet();

    @AfterEach
    void closeQueuesAndDeleteMessages() {
        for (DefaultDispatchQueue<TestEvent> queue : queues) {
            queue.close();
        }
        database.run(dsl -> dsl.deleteFrom(DISPATCH_QUEUE_MESSAGES)
            .where(DISPATCH_QUEUE_MESSAGES.QUEUE_NAME.startsWith(queuePrefix))
            .execute());
    }

    @Test
    void persistsWithoutConsumerAndCompetesAcrossQueueInstances()
        throws InterruptedException {
        String name = queueName("competition");
        DefaultDispatchQueue<TestEvent> publisher = queue(name);
        DefaultDispatchQueue<TestEvent> secondInstance = queue(name);
        int messageCount = 24;
        List<TestEvent> events = new ArrayList<>(messageCount);
        for (int index = 0; index < messageCount; index++) {
            events.add(new TestEvent("key-" + index, "value-" + index));
        }

        publisher.emit(events);
        assertEquals(messageCount, pending(name));

        CountDownLatch delivered = new CountDownLatch(messageCount);
        Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        java.util.function.Consumer<TestEvent> consumer = event -> {
            deliveries.computeIfAbsent(
                event.value(),
                ignored -> new AtomicInteger()
            ).incrementAndGet();
            delivered.countDown();
        };
        publisher.subscribe(consumer);
        secondInstance.subscribe(consumer);

        assertTrue(delivered.await(10, TimeUnit.SECONDS));
        awaitPending(name, 0);
        assertEquals(messageCount, deliveries.size());
        assertTrue(deliveries.values().stream()
            .allMatch(count -> count.get() == 1));
    }

    @Test
    void deletesAfterConsumerRuntimeFailureAndContinues()
        throws InterruptedException {
        String name = queueName("consumer-failure");
        DefaultDispatchQueue<TestEvent> queue = queue(name);
        CountDownLatch attempted = new CountDownLatch(2);
        AtomicInteger attempts = new AtomicInteger();
        queue.subscribe(event -> {
            attempts.incrementAndGet();
            attempted.countDown();
            if (event.value().equals("fail")) {
                throw new IllegalStateException("business failure");
            }
        });

        queue.emit(List.of(
            new TestEvent("first", "fail"),
            new TestEvent("second", "continue")
        ));

        assertTrue(attempted.await(10, TimeUnit.SECONDS));
        awaitPending(name, 0);
        Thread.sleep(150L);
        assertEquals(2, attempts.get());
    }

    @Test
    void rollsBackOriginalMessageWhenDeliveryTransactionAborts() {
        String name = queueName("delivery-rollback");
        DefaultDispatchQueue<TestEvent> queue = queue(name);
        PostgresQueueStore<TestEvent> store = new PostgresQueueStore<>(
            name,
            database,
            TestEvent.class
        );
        AtomicInteger attempts = new AtomicInteger();
        queue.emit(new TestEvent("same-message", "recover"));

        assertThrows(FatalDelivery.class, () -> store.deliverOne(event -> {
            attempts.incrementAndGet();
            throw new FatalDelivery();
        }, () -> true));
        assertEquals(1, pending(name));

        PostgresQueueStore.DeliveryAttempt recovered = store.deliverOne(
            event -> attempts.incrementAndGet(),
            () -> true
        );
        assertTrue(recovered.delivered());
        assertEquals(0, pending(name));
        assertEquals(2, attempts.get());
    }

    @Test
    void keepsOriginalMessageWhenPayloadCannotBeDecoded() {
        String name = queueName("decode-failure");
        database.run(dsl -> dsl.insertInto(DISPATCH_QUEUE_MESSAGES)
            .set(DISPATCH_QUEUE_MESSAGES.ID, StringUtil.newId())
            .set(DISPATCH_QUEUE_MESSAGES.QUEUE_NAME, name)
            .set(DISPATCH_QUEUE_MESSAGES.EVENT_KEY, "unreadable")
            .set(
                DISPATCH_QUEUE_MESSAGES.PAYLOAD,
                JSONB.valueOf(
                    """
                    {"key":"unreadable","value":{"nested":true}}
                    """
                )
            )
            .execute());
        PostgresQueueStore<TestEvent> store = new PostgresQueueStore<>(
            name,
            database,
            TestEvent.class
        );

        assertThrows(
            QueueException.class,
            () -> store.deliverOne(event -> {
            }, () -> true)
        );

        assertEquals(1, pending(name));
    }

    @Test
    void keepsLockedMessageWhenSubscriptionPausesBeforeCallback() {
        String name = queueName("paused-after-lock");
        DefaultDispatchQueue<TestEvent> queue = queue(name);
        PostgresQueueStore<TestEvent> store = new PostgresQueueStore<>(
            name,
            database,
            TestEvent.class
        );
        AtomicInteger deliveries = new AtomicInteger();
        queue.emit(new TestEvent("paused", "retain"));

        PostgresQueueStore.DeliveryAttempt paused = store.deliverOne(
            event -> deliveries.incrementAndGet(),
            () -> false
        );

        assertFalse(paused.delivered());
        assertEquals(0, deliveries.get());
        assertEquals(1, pending(name));
    }

    @Test
    void validatesCompleteBatchBeforeWritingAnyRows() {
        String name = queueName("atomic-batch");
        DefaultDispatchQueue<TestEvent> queue = queue(name);
        List<TestEvent> events = new ArrayList<>();
        events.add(new TestEvent("first", "good"));
        events.add(null);

        assertThrows(QueueException.class, () -> queue.emit(events));
        assertEquals(0, pending(name));
    }

    @Test
    void publishesOneBatchWithNullableEventKeys() {
        String name = queueName("nullable-event-keys");
        DefaultDispatchQueue<TestEvent> queue = queue(name);

        queue.emit(List.of(
            new TestEvent(null, "without-key"),
            new TestEvent("present", "with-key"),
            new TestEvent(null, "without-key-again")
        ));

        assertEquals(3, pending(name));
    }

    @Test
    void pausesResumesAndSupportsConsumerSelfClose()
        throws InterruptedException {
        String name = queueName("lifecycle");
        DefaultDispatchQueue<TestEvent> queue = queue(name);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<QueueSubscription> registration =
            new AtomicReference<>();
        QueueSubscription subscription = queue.subscribe(event -> {
            registration.get().close();
            delivered.countDown();
        });
        registration.set(subscription);
        subscription.pause();

        queue.emit(new TestEvent(null, "paused"));
        assertFalse(delivered.await(150, TimeUnit.MILLISECONDS));
        assertTrue(subscription.isPaused());

        subscription.resume();
        assertTrue(delivered.await(10, TimeUnit.SECONDS));
        awaitInactive(subscription);
        assertFalse(subscription.isActive());
        awaitPending(name, 0);
    }

    @Test
    void pauseDoesNotWaitForCurrentCallback()
        throws InterruptedException {
        String name = queueName("non-blocking-pause");
        DefaultDispatchQueue<TestEvent> queue = queue(name);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch pauseFinished = new CountDownLatch(1);
        QueueSubscription subscription = queue.subscribe(event -> {
            callbackStarted.countDown();
            await(releaseCallback);
        });
        queue.emit(new TestEvent("pause", "blocking"));
        boolean callbackBegan = callbackStarted.await(
            10,
            TimeUnit.SECONDS
        );
        if (!callbackBegan) {
            releaseCallback.countDown();
        }
        assertTrue(callbackBegan);

        Thread.ofVirtual().start(() -> {
            subscription.pause();
            pauseFinished.countDown();
        });

        boolean pausedWithoutWaiting = pauseFinished.await(
            1,
            TimeUnit.SECONDS
        );
        releaseCallback.countDown();
        assertTrue(pausedWithoutWaiting);
        assertTrue(subscription.isPaused());
        awaitPending(name, 0);
    }

    @Test
    void closeWaitsForCurrentCallbackAndRejectsNewOperations()
        throws InterruptedException {
        String name = queueName("close");
        DefaultDispatchQueue<TestEvent> queue = queue(name);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        QueueSubscription subscription = queue.subscribe(event -> {
            callbackStarted.countDown();
            await(releaseCallback);
        });
        queue.emit(new TestEvent("close", "blocking"));
        boolean callbackBegan = callbackStarted.await(
            10,
            TimeUnit.SECONDS
        );
        if (!callbackBegan) {
            releaseCallback.countDown();
        }
        assertTrue(callbackBegan);

        Thread.ofVirtual().start(() -> {
            subscription.close();
            closeFinished.countDown();
        });
        boolean closeWaitedForCallback = !closeFinished.await(
            150,
            TimeUnit.MILLISECONDS
        );
        releaseCallback.countDown();
        assertTrue(closeWaitedForCallback);
        assertTrue(closeFinished.await(10, TimeUnit.SECONDS));

        queue.close();
        assertThrows(
            QueueException.class,
            () -> queue.emit(new TestEvent("closed", "sync"))
        );
        assertThrows(
            QueueException.class,
            () -> queue.subscribe(event -> {
            })
        );
        CompletionException asyncFailure = assertThrows(
            CompletionException.class,
            () -> queue.emitAsync(
                new TestEvent("closed", "async")
            ).toCompletableFuture().join()
        );
        assertInstanceOf(QueueException.class, asyncFailure.getCause());
    }

    @Test
    void queueCloseStopsEverySubscriptionBeforeWaitingForCallbacks()
        throws InterruptedException {
        String name = queueName("two-phase-close");
        DefaultDispatchQueue<TestEvent> queue = queue(name);
        DefaultDispatchQueue<TestEvent> externalPublisher = queue(name);
        CountDownLatch blockingCallbackStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockingCallback = new CountDownLatch(1);
        CountDownLatch secondSubscriptionDelivered =
            new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        queue.subscribe(event -> {
            blockingCallbackStarted.countDown();
            await(releaseBlockingCallback);
        });
        queue.emit(new TestEvent("first", "blocking"));
        boolean blockingCallbackBegan = blockingCallbackStarted.await(
            10,
            TimeUnit.SECONDS
        );
        if (!blockingCallbackBegan) {
            releaseBlockingCallback.countDown();
        }
        assertTrue(blockingCallbackBegan);
        queue.subscribe(event -> secondSubscriptionDelivered.countDown());

        Thread.ofVirtual().start(() -> {
            closeEntered.countDown();
            try {
                queue.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            } finally {
                closeFinished.countDown();
            }
        });
        boolean closeBegan = closeEntered.await(1, TimeUnit.SECONDS);
        if (!closeBegan) {
            releaseBlockingCallback.countDown();
        }
        assertTrue(closeBegan);
        boolean closeWaitedForCallback = !closeFinished.await(
            150,
            TimeUnit.MILLISECONDS
        );

        boolean deliveredWhileClosing;
        try {
            externalPublisher.emit(new TestEvent("second", "must-remain"));
            deliveredWhileClosing = secondSubscriptionDelivered.await(
                300,
                TimeUnit.MILLISECONDS
            );
        } finally {
            releaseBlockingCallback.countDown();
        }
        boolean closeCompleted = closeFinished.await(
            10,
            TimeUnit.SECONDS
        );

        assertTrue(closeWaitedForCallback);
        assertFalse(deliveredWhileClosing);
        assertTrue(closeCompleted);
        assertNull(closeFailure.get());
        assertEquals(1, pending(name));
    }

    @Test
    void concurrentQueueCloseCallsBothWaitForCurrentCallback()
        throws InterruptedException {
        String name = queueName("concurrent-close");
        DefaultDispatchQueue<TestEvent> queue = queue(name);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(2);
        CountDownLatch firstCloseFinished = new CountDownLatch(1);
        CountDownLatch secondCloseFinished = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        queue.subscribe(event -> {
            callbackStarted.countDown();
            await(releaseCallback);
        });
        queue.emit(new TestEvent("close", "blocking"));
        boolean callbackBegan = callbackStarted.await(
            10,
            TimeUnit.SECONDS
        );
        if (!callbackBegan) {
            releaseCallback.countDown();
        }
        assertTrue(callbackBegan);

        startClose(
            queue,
            closeEntered,
            firstCloseFinished,
            firstFailure
        );
        startClose(
            queue,
            closeEntered,
            secondCloseFinished,
            secondFailure
        );
        boolean bothCloseCallsEntered = closeEntered.await(
            1,
            TimeUnit.SECONDS
        );
        if (!bothCloseCallsEntered) {
            releaseCallback.countDown();
        }
        assertTrue(bothCloseCallsEntered);

        boolean firstWaited = !firstCloseFinished.await(
            150,
            TimeUnit.MILLISECONDS
        );
        boolean secondWaited = !secondCloseFinished.await(
            150,
            TimeUnit.MILLISECONDS
        );
        releaseCallback.countDown();

        assertTrue(firstCloseFinished.await(10, TimeUnit.SECONDS));
        assertTrue(secondCloseFinished.await(10, TimeUnit.SECONDS));
        assertTrue(firstWaited);
        assertTrue(secondWaited);
        assertNull(firstFailure.get());
        assertNull(secondFailure.get());
    }

    @Test
    void concurrentConsumerQueueCloseCallsDoNotWaitForEachOther()
        throws InterruptedException {
        String name = queueName("consumer-concurrent-close");
        DefaultDispatchQueue<TestEvent> queue = queue(name);
        CountDownLatch callbacksStarted = new CountDownLatch(2);
        CountDownLatch closeCallsReturned = new CountDownLatch(2);
        CountDownLatch callbacksReturned = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        java.util.function.Consumer<TestEvent> consumer = event -> {
            callbacksStarted.countDown();
            try {
                if (!callbacksStarted.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                        "Both Queue callbacks did not start"
                    );
                }
                queue.close();
                closeCallsReturned.countDown();
            } catch (InterruptedException closeFailure) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, closeFailure);
            } catch (Throwable closeFailure) {
                failure.compareAndSet(null, closeFailure);
            } finally {
                callbacksReturned.countDown();
            }
        };
        queue.subscribe(consumer);
        queue.subscribe(consumer);

        queue.emit(List.of(
            new TestEvent("first", "close"),
            new TestEvent("second", "close")
        ));

        assertTrue(callbacksStarted.await(5, TimeUnit.SECONDS));
        assertTrue(closeCallsReturned.await(5, TimeUnit.SECONDS));
        assertTrue(callbacksReturned.await(5, TimeUnit.SECONDS));
        assertNull(failure.get());
        awaitPending(name, 0);
    }

    @Test
    void consumerQueueCloseDoesNotWaitForAsyncPublishNeedingItsConnection()
        throws Exception {
        try (QueueLoadEnvironment environment =
                 QueueLoadEnvironment.open(1, 5)) {
            String name = environment.runId("worker-close-async", 1);
            DispatchQueue<TestEvent> queue = environment.queue(
                name,
                TestEvent.class,
                10L
            );
            CountDownLatch closeReturned = new CountDownLatch(1);
            AtomicReference<CompletionStage<Void>> asyncPublish =
                new AtomicReference<>();

            queue.subscribe(event -> {
                asyncPublish.set(queue.emitAsync(
                    new TestEvent("async", "after-close")
                ));
                queue.close();
                closeReturned.countDown();
            });
            queue.emit(new TestEvent("initial", "close-worker"));

            assertTrue(closeReturned.await(5, TimeUnit.SECONDS));
            asyncPublish.get().toCompletableFuture().get(
                5,
                TimeUnit.SECONDS
            );
            assertEquals(1, environment.pendingMessages(name));
        }
    }

    private DefaultDispatchQueue<TestEvent> queue(String name) {
        DefaultDispatchQueue<TestEvent> queue = new DefaultDispatchQueue<>(
            name,
            database,
            TestEvent.class,
            10L
        );
        queues.add(queue);
        return queue;
    }

    private static void startClose(
        DefaultDispatchQueue<TestEvent> queue,
        CountDownLatch entered,
        CountDownLatch finished,
        AtomicReference<Throwable> failure
    ) {
        Thread.ofVirtual().start(() -> {
            entered.countDown();
            try {
                queue.close();
            } catch (Throwable closeFailure) {
                failure.set(closeFailure);
            } finally {
                finished.countDown();
            }
        });
    }

    private String queueName(String suffix) {
        return queuePrefix + "-" + suffix;
    }

    private int pending(String name) {
        return database.runReturn(dsl -> dsl.fetchCount(
            DISPATCH_QUEUE_MESSAGES,
            DISPATCH_QUEUE_MESSAGES.QUEUE_NAME.eq(name)
        ));
    }

    private void awaitPending(String name, int expected)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pending(name) != expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(expected, pending(name));
    }

    private static void awaitInactive(QueueSubscription subscription)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (subscription.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    public static final class TestEvent extends SerializableObject
        implements DispatchEvent {

        private String key;
        private String value;

        public TestEvent() {
        }

        TestEvent(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public DSLContext dsl() {
            return null;
        }

        public String value() {
            return value;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    private static final class FatalDelivery extends Error {
    }
}
