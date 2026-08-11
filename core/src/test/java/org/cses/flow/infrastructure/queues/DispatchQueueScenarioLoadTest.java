package org.cses.flow.infrastructure.queues;

import io.micronaut.json.JsonMapper;
import org.cses.flow.queues.DispatchQueue;
import org.cses.flow.queues.event.DispatchEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.jooq.DSLContext;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.json.SerializableObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in scenario load tests for the public Dispatch Queue seam.
 */
@Tag("queue-load")
@ResourceLock("flow-postgres-queue-load")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 12, unit = TimeUnit.HOURS)
final class DispatchQueueScenarioLoadTest {

    private LoadConfig config;
    private QueueLoadEnvironment environment;
    private String payload;
    private Path resultsFile;

    @BeforeAll
    void openProductionLikeDatabaseEnvironment()
        throws Exception {
        JsonFactory.instance = JsonMapper.createDefault();
        config = LoadConfig.fromEnvironment();
        resultsFile = Path.of(System.getProperty(
            "flow.queue.load.results-file",
            "build/reports/dispatchQueueLoadTest/results.jsonl"
        ));
        Files.createDirectories(resultsFile.toAbsolutePath().getParent());
        Files.writeString(
            resultsFile,
            "",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
        try {
            environment = QueueLoadEnvironment.open(
                config.poolSize(),
                config.shutdownTimeoutSeconds()
            );
            payload = "x".repeat(config.payloadBytes());
            warmUpQueue();
        } catch (Exception | Error failure) {
            if (environment != null) {
                try {
                    environment.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    @AfterAll
    void closeProductionLikeDatabaseEnvironment() {
        if (environment != null) {
            environment.close();
        }
    }

    private void warmUpQueue() throws InterruptedException {
        String runId = environment.runId("warmup", 0);
        String queueName = runId + "-queue";
        DispatchQueue<LoadEvent> queue = queue(queueName);
        int eventCount = Math.min(200, config.eventCount());
        CountDownLatch delivered = new CountDownLatch(eventCount);
        queue.subscribe(event -> delivered.countDown());
        queue.emit(events(runId, 0, eventCount));
        boolean completed = delivered.await(
            Math.min(config.drainTimeoutSeconds(), 30),
            TimeUnit.SECONDS
        );
        queue.close();
        int pending = environment.pendingMessages(queueName);
        int deleted = environment.deleteMessages(queueName);
        assertTrue(completed, "Queue warmup did not drain");
        assertEquals(0, pending, "Queue warmup left pending rows");
        assertEquals(0, deleted, "Queue warmup required cleanup");
    }

    @Test
    void acceptedBacklogDrainsAfterPublisherReopens(
        TestReporter reporter
    ) throws Exception {
        for (int repetition = 1;
             repetition <= config.repetitions();
             repetition++) {
            String runId = environment.runId("backlog", repetition);
            ScenarioResult result = runBacklogScenario(runId);
            report(reporter, result, repetition);
            assertScenario(result);
        }
    }

    @Test
    void concurrentSynchronousPublishAndCompetingConsumeDeliverOnce(
        TestReporter reporter
    ) throws Exception {
        runConcurrentScenario(PublishMode.SYNC_BATCH, reporter);
    }

    @Test
    void concurrentSinglePublishAndCompetingConsumeDeliverOnce(
        TestReporter reporter
    ) throws Exception {
        runConcurrentScenario(PublishMode.SYNC_SINGLE, reporter);
    }

    @Test
    void concurrentAsynchronousPublishAndCompetingConsumeDeliverOnce(
        TestReporter reporter
    ) throws Exception {
        runConcurrentScenario(PublishMode.ASYNC_BATCH, reporter);
    }

    private void runConcurrentScenario(
        PublishMode mode,
        TestReporter reporter
    ) throws Exception {
        for (int repetition = 1;
             repetition <= config.repetitions();
             repetition++) {
            String scenario = "live-" + mode.name().toLowerCase();
            String runId = environment.runId(scenario, repetition);
            ScenarioResult result = runLiveScenario(
                runId,
                scenario,
                config,
                mode
            );
            report(reporter, result, repetition);
            assertScenario(result);
        }
    }

    @Test
    void independentLogicalQueuesRemainIsolatedUnderConcurrentLoad(
        TestReporter reporter
    ) throws Exception {
        LoadConfig isolated = config.halfConcurrency();
        for (int repetition = 1;
             repetition <= config.repetitions();
             repetition++) {
            String firstRun = environment.runId(
                "isolated-a",
                repetition
            );
            String secondRun = environment.runId(
                "isolated-b",
                repetition
            );
            ExecutorService scenarios = Executors.newFixedThreadPool(2);
            List<Future<ScenarioResult>> scenarioTasks = new ArrayList<>(2);
            Throwable primary = null;
            try {
                Future<ScenarioResult> first = scenarios.submit(() ->
                    runLiveScenario(
                        firstRun,
                        "isolated-a",
                        isolated,
                        PublishMode.SYNC_BATCH
                    )
                );
                Future<ScenarioResult> second = scenarios.submit(() ->
                    runLiveScenario(
                        secondRun,
                        "isolated-b",
                        isolated,
                        PublishMode.SYNC_BATCH
                    )
                );
                scenarioTasks.add(first);
                scenarioTasks.add(second);
                Deadline deadline = Deadline.afterSeconds(
                    config.publishTimeoutSeconds()
                        + config.drainTimeoutSeconds()
                        + config.shutdownTimeoutSeconds()
                );
                ScenarioResult firstResult = get(first, deadline);
                ScenarioResult secondResult = get(second, deadline);
                report(reporter, firstResult, repetition);
                report(reporter, secondResult, repetition);
                assertScenario(firstResult);
                assertScenario(secondResult);
            } catch (Exception | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                terminateExecutor(
                    scenarios,
                    scenarioTasks,
                    primary != null,
                    config.shutdownTimeoutSeconds(),
                    primary
                );
            }
        }
    }

    private ScenarioResult runBacklogScenario(String runId)
        throws Exception {
        String queueName = runId + "-queue";
        List<DispatchQueue<LoadEvent>> queues = new ArrayList<>();
        DispatchQueue<LoadEvent> publisher = queue(queueName);
        queues.add(publisher);
        DeliveryProbe probe = new DeliveryProbe(
            runId,
            config.eventCount(),
            config.consumerSubscriptions()
        );
        try {
            PublishResult published = publishBacklog(
                publisher,
                runId,
                config
            );
            publisher.close();

            List<DispatchQueue<LoadEvent>> consumers = queues(
                queueName,
                config.queueInstances()
            );
            queues.addAll(consumers);
            DrainCheckpoint drain = probe.drainCheckpoint();
            subscribe(consumers, probe, config.consumerSubscriptions());
            boolean completed = probe.await(
                config.drainTimeoutSeconds()
            );
            long completedAt = System.nanoTime();
            double shutdownMillis = closeQueues(
                queues,
                config.shutdownTimeoutSeconds()
            );
            int pendingAfterClose = environment.pendingMessages(queueName);
            int cleanupDeleted = environment.deleteMessages(queueName);
            int pendingAfterCleanup = environment.pendingMessages(queueName);
            return probe.result(
                "backlog",
                config.backlog(),
                published.accepted(),
                completed,
                published.startedAt(),
                published.finishedAt(),
                drain.startedAt(),
                drain.remaining(),
                completedAt,
                shutdownMillis,
                pendingAfterClose,
                cleanupDeleted,
                pendingAfterCleanup
            );
        } catch (Exception | Error failure) {
            closeQueuesSuppressing(
                queues,
                config.shutdownTimeoutSeconds(),
                failure
            );
            deleteMessagesSuppressing(queueName, failure);
            throw failure;
        }
    }

    private ScenarioResult runLiveScenario(
        String runId,
        String scenario,
        LoadConfig scenarioConfig,
        PublishMode mode
    ) throws Exception {
        String queueName = runId + "-queue";
        List<DispatchQueue<LoadEvent>> queues = new ArrayList<>();
        List<DispatchQueue<LoadEvent>> publishers = queues(
            queueName,
            scenarioConfig.queueInstances()
        );
        List<DispatchQueue<LoadEvent>> consumers = queues(
            queueName,
            scenarioConfig.queueInstances()
        );
        queues.addAll(publishers);
        queues.addAll(consumers);
        DeliveryProbe probe = new DeliveryProbe(
            runId,
            scenarioConfig.eventCount(),
            scenarioConfig.consumerSubscriptions()
        );
        subscribe(
            consumers,
            probe,
            scenarioConfig.consumerSubscriptions()
        );

        try {
            PublishResult published = publishConcurrently(
                publishers,
                runId,
                scenarioConfig,
                mode
            );
            DrainCheckpoint drain = probe.drainCheckpoint();
            boolean completed = probe.await(
                scenarioConfig.drainTimeoutSeconds()
            );
            long completedAt = System.nanoTime();
            double shutdownMillis = closeQueues(
                queues,
                scenarioConfig.shutdownTimeoutSeconds()
            );
            int pendingAfterClose = environment.pendingMessages(queueName);
            int cleanupDeleted = environment.deleteMessages(queueName);
            int pendingAfterCleanup = environment.pendingMessages(queueName);
            return probe.result(
                scenario,
                scenarioConfig,
                published.accepted(),
                completed,
                published.startedAt(),
                published.finishedAt(),
                drain.startedAt(),
                drain.remaining(),
                completedAt,
                shutdownMillis,
                pendingAfterClose,
                cleanupDeleted,
                pendingAfterCleanup
            );
        } catch (Exception | Error failure) {
            closeQueuesSuppressing(
                queues,
                scenarioConfig.shutdownTimeoutSeconds(),
                failure
            );
            deleteMessagesSuppressing(queueName, failure);
            throw failure;
        }
    }

    private PublishResult publishConcurrently(
        List<DispatchQueue<LoadEvent>> publishers,
        String runId,
        LoadConfig scenarioConfig,
        PublishMode mode
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(
            scenarioConfig.publisherThreads()
        );
        CountDownLatch ready = new CountDownLatch(
            scenarioConfig.publisherThreads()
        );
        CountDownLatch start = new CountDownLatch(1);
        LongAdder accepted = new LongAdder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Semaphore asyncPermits = new Semaphore(
            scenarioConfig.maxAsyncInFlight()
        );
        ConcurrentLinkedQueue<CompletableFuture<Void>> stages =
            new ConcurrentLinkedQueue<>();
        List<Future<?>> producers = new ArrayList<>(
            scenarioConfig.publisherThreads()
        );
        for (int publisherIndex = 0;
             publisherIndex < scenarioConfig.publisherThreads();
             publisherIndex++) {
            int index = publisherIndex;
            producers.add(executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    publishPartition(
                        publishers.get(index % publishers.size()),
                        runId,
                        scenarioConfig,
                        mode,
                        index,
                        accepted,
                        asyncPermits,
                        stages
                    );
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                    throw throwable;
                }
            }));
        }
        Deadline publishDeadline = Deadline.afterSeconds(
            scenarioConfig.publishTimeoutSeconds()
        );
        Throwable primary = null;
        try {
            assertTrue(
                ready.await(
                    publishDeadline.remainingNanos(),
                    TimeUnit.NANOSECONDS
                ),
                "Publishers did not become ready before the deadline"
            );
            long startedAt = System.nanoTime();
            start.countDown();
            for (Future<?> producer : producers) {
                get(producer, publishDeadline);
            }
            CompletableFuture<Void> allStages = CompletableFuture.allOf(
                stages.toArray(CompletableFuture[]::new)
            );
            get(allStages, publishDeadline);
            long finishedAt = System.nanoTime();
            if (failure.get() != null) {
                throw new AssertionError(
                    "Queue publisher failed",
                    failure.get()
                );
            }
            return new PublishResult(
                accepted.intValue(),
                startedAt,
                finishedAt
            );
        } catch (Exception | Error publishFailure) {
            primary = publishFailure;
            throw publishFailure;
        } finally {
            start.countDown();
            terminateExecutor(
                executor,
                producers,
                primary != null,
                scenarioConfig.shutdownTimeoutSeconds(),
                primary
            );
        }
    }

    private void publishPartition(
        DispatchQueue<LoadEvent> publisher,
        String runId,
        LoadConfig scenarioConfig,
        PublishMode mode,
        int publisherIndex,
        LongAdder accepted,
        Semaphore asyncPermits,
        ConcurrentLinkedQueue<CompletableFuture<Void>> stages
    ) {
        int from = scenarioConfig.eventCount()
            * publisherIndex
            / scenarioConfig.publisherThreads();
        int to = scenarioConfig.eventCount()
            * (publisherIndex + 1)
            / scenarioConfig.publisherThreads();
        for (int offset = from;
             offset < to;
             offset += scenarioConfig.batchSize()) {
            int batchEnd = Math.min(
                offset + scenarioConfig.batchSize(),
                to
            );
            if (mode == PublishMode.SYNC_BATCH) {
                List<LoadEvent> batch = events(runId, offset, batchEnd);
                publisher.emit(batch);
                accepted.add(batch.size());
                continue;
            }
            if (mode == PublishMode.SYNC_SINGLE) {
                for (int sequence = offset;
                     sequence < batchEnd;
                     sequence++) {
                    publisher.emit(event(runId, sequence));
                    accepted.increment();
                }
                continue;
            }

            acquire(asyncPermits);
            List<LoadEvent> batch = events(runId, offset, batchEnd);
            CompletableFuture<Void> stage;
            try {
                stage = publisher.emitAsync(batch).toCompletableFuture();
            } catch (RuntimeException exception) {
                asyncPermits.release();
                throw exception;
            }
            CompletableFuture<Void> accounted = stage.whenComplete(
                (ignored, throwable) -> {
                if (throwable == null) {
                    accepted.add(batch.size());
                }
                asyncPermits.release();
            });
            stages.add(accounted);
        }
    }

    private PublishResult publishBacklog(
        DispatchQueue<LoadEvent> publisher,
        String runId,
        LoadConfig scenarioConfig
    ) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long startedAt = System.nanoTime();
        Future<Integer> publish = executor.submit(() -> {
            int accepted = 0;
            for (int offset = 0;
                 offset < scenarioConfig.eventCount();
                 offset += scenarioConfig.batchSize()) {
                int end = Math.min(
                    offset + scenarioConfig.batchSize(),
                    scenarioConfig.eventCount()
                );
                List<LoadEvent> batch = events(runId, offset, end);
                publisher.emit(batch);
                accepted += batch.size();
            }
            return accepted;
        });
        Throwable primary = null;
        try {
            int accepted = get(
                publish,
                Deadline.afterSeconds(
                    scenarioConfig.publishTimeoutSeconds()
                )
            );
            return new PublishResult(
                accepted,
                startedAt,
                System.nanoTime()
            );
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            terminateExecutor(
                executor,
                List.of(publish),
                primary != null,
                scenarioConfig.shutdownTimeoutSeconds(),
                primary
            );
        }
    }

    private List<LoadEvent> events(String runId, int from, int to) {
        List<LoadEvent> events = new ArrayList<>(to - from);
        for (int sequence = from; sequence < to; sequence++) {
            events.add(event(runId, sequence));
        }
        return List.copyOf(events);
    }

    private LoadEvent event(String runId, int sequence) {
        return new LoadEvent(
            runId,
            sequence,
            System.nanoTime(),
            payload
        );
    }

    private void subscribe(
        List<DispatchQueue<LoadEvent>> queues,
        DeliveryProbe probe,
        int subscriptions
    ) {
        for (int index = 0; index < subscriptions; index++) {
            DispatchQueue<LoadEvent> queue = queues.get(
                index % queues.size()
            );
            queue.subscribe(probe.consumer(index));
        }
    }

    private List<DispatchQueue<LoadEvent>> queues(
        String queueName,
        int count
    ) {
        List<DispatchQueue<LoadEvent>> queues = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            queues.add(queue(queueName));
        }
        return queues;
    }

    private DispatchQueue<LoadEvent> queue(String queueName) {
        return environment.queue(
            queueName,
            LoadEvent.class,
            config.pollIntervalMillis()
        );
    }

    private static double closeQueues(
        List<DispatchQueue<LoadEvent>> queues,
        int timeoutSeconds
    ) throws Exception {
        long startedAt = System.nanoTime();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Deadline deadline = Deadline.afterSeconds(timeoutSeconds);
        List<Future<?>> closures = new ArrayList<>(queues.size());
        Throwable primary = null;
        try {
            for (DispatchQueue<LoadEvent> queue : queues) {
                closures.add(executor.submit(queue::close));
            }
            for (Future<?> closure : closures) {
                get(closure, deadline);
            }
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            terminateExecutor(
                executor,
                closures,
                primary != null,
                timeoutSeconds,
                primary
            );
        }
        return (System.nanoTime() - startedAt) / 1_000_000.0;
    }

    private static void closeQueuesSuppressing(
        List<DispatchQueue<LoadEvent>> queues,
        int timeoutSeconds,
        Throwable primary
    ) {
        try {
            closeQueues(queues, timeoutSeconds);
        } catch (Exception | Error closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private void deleteMessagesSuppressing(
        String queueName,
        Throwable primary
    ) {
        try {
            environment.deleteMessages(queueName);
        } catch (RuntimeException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private static void terminateExecutor(
        ExecutorService executor,
        List<? extends Future<?>> tasks,
        boolean cancelTasks,
        int timeoutSeconds,
        Throwable primary
    ) throws Exception {
        if (cancelTasks) {
            tasks.forEach(task -> task.cancel(true));
            executor.shutdownNow();
        } else {
            executor.shutdown();
        }

        try {
            if (executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                return;
            }
            tasks.forEach(task -> task.cancel(true));
            executor.shutdownNow();
            TimeoutException timeout = new TimeoutException(
                "Queue load-test Executor did not terminate within "
                    + timeoutSeconds
                    + " seconds"
            );
            if (primary != null) {
                primary.addSuppressed(timeout);
                return;
            }
            throw timeout;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            tasks.forEach(task -> task.cancel(true));
            executor.shutdownNow();
            if (primary != null) {
                primary.addSuppressed(exception);
                return;
            }
            throw exception;
        }
    }

    private static void assertScenario(ScenarioResult result) {
        assertTrue(
            result.completedBeforeDeadline(),
            () -> result.scenario() + " did not drain before the deadline"
        );
        assertEquals(result.requested(), result.accepted());
        assertEquals(result.requested(), result.unique());
        assertEquals(result.requested(), result.callbacks());
        assertEquals(0, result.duplicates());
        assertEquals(0, result.missing());
        assertEquals(0, result.crossQueue());
        assertEquals(0, result.pendingAfterClose());
        assertEquals(0, result.cleanupDeleted());
        assertEquals(0, result.pendingAfterCleanup());
        assertTrue(
            result.drainStartRemaining() >= 0
                && result.drainStartRemaining() <= result.requested()
        );
        assertEquals(1, result.maxSubscriptionConcurrency());
        assertNull(result.failure());
    }

    private void report(
        TestReporter reporter,
        ScenarioResult result,
        int repetition
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("scenario", result.scenario());
        values.put("run_id", result.runId());
        values.put("logical_queue", result.runId() + "-queue");
        values.put("profile", config.profile());
        values.put("repetition", Integer.toString(repetition));
        values.put("requested", Integer.toString(result.requested()));
        values.put("events", Integer.toString(result.requested()));
        values.put("accepted", Integer.toString(result.accepted()));
        values.put("callbacks", Integer.toString(result.callbacks()));
        values.put("unique", Integer.toString(result.unique()));
        values.put("duplicates", Integer.toString(result.duplicates()));
        values.put("missing", Integer.toString(result.missing()));
        values.put("cross_queue", Integer.toString(result.crossQueue()));
        values.put(
            "pending_after_close",
            Integer.toString(result.pendingAfterClose())
        );
        values.put(
            "cleanup_deleted",
            Integer.toString(result.cleanupDeleted())
        );
        values.put(
            "pending_after_cleanup",
            Integer.toString(result.pendingAfterCleanup())
        );
        values.put("failures", hasScenarioFailure(result) ? "1" : "0");
        values.put(
            "failure_type",
            result.failure() == null
                ? "none"
                : result.failure().getClass().getName()
        );
        values.put(
            "failure_message",
            result.failure() == null
                ? "none"
                : String.valueOf(result.failure().getMessage())
        );
        values.put(
            "completed_before_deadline",
            Boolean.toString(result.completedBeforeDeadline())
        );
        values.put(
            "max_subscription_concurrency",
            Integer.toString(result.maxSubscriptionConcurrency())
        );
        LoadConfig actual = result.loadConfig();
        values.put(
            "configured_repetitions",
            Integer.toString(config.repetitions())
        );
        values.put(
            "publisher_threads",
            Integer.toString(actual.publisherThreads())
        );
        values.put(
            "consumer_subscriptions",
            Integer.toString(actual.consumerSubscriptions())
        );
        values.put(
            "queue_instances",
            Integer.toString(actual.queueInstances())
        );
        values.put("batch_size", Integer.toString(actual.batchSize()));
        values.put(
            "max_async_in_flight",
            Integer.toString(actual.maxAsyncInFlight())
        );
        values.put("payload_bytes", Integer.toString(actual.payloadBytes()));
        values.put("pool_size", Integer.toString(actual.poolSize()));
        values.put(
            "pool_minimum_idle",
            Integer.toString(Math.min(actual.poolSize(), 2))
        );
        values.put("connection_timeout_ms", "10000");
        values.put(
            "statement_timeout_ms",
            Long.toString(TimeUnit.SECONDS.toMillis(
                actual.shutdownTimeoutSeconds()
            ))
        );
        values.put(
            "socket_timeout_seconds",
            Integer.toString(actual.shutdownTimeoutSeconds())
        );
        values.put(
            "poll_interval_ms",
            Integer.toString(actual.pollIntervalMillis())
        );
        values.put(
            "publish_timeout_seconds",
            Integer.toString(actual.publishTimeoutSeconds())
        );
        values.put(
            "drain_timeout_seconds",
            Integer.toString(actual.drainTimeoutSeconds())
        );
        values.put(
            "shutdown_timeout_seconds",
            Integer.toString(actual.shutdownTimeoutSeconds())
        );
        values.put("publish_ms", decimal(result.publishMillis()));
        values.put(
            "drain_start_remaining",
            Integer.toString(result.drainStartRemaining())
        );
        values.put("drain_ms", decimal(result.drainMillis()));
        values.put("total_ms", decimal(result.totalMillis()));
        values.put("shutdown_ms", decimal(result.shutdownMillis()));
        values.put("publish_per_second", decimal(result.publishPerSecond()));
        values.put("delivery_per_second", decimal(result.deliveryPerSecond()));
        values.put(
            "end_to_end_per_second",
            decimal(result.endToEndPerSecond())
        );
        values.put("latency_p50_ms", decimal(result.p50Millis()));
        values.put("latency_p95_ms", decimal(result.p95Millis()));
        values.put("latency_p99_ms", decimal(result.p99Millis()));
        values.put("latency_max_ms", decimal(result.maxMillis()));
        values.put("java_version", System.getProperty("java.version"));
        values.put("java_vendor", System.getProperty("java.vendor"));
        values.put("os_name", System.getProperty("os.name"));
        values.put("os_arch", System.getProperty("os.arch"));
        values.put(
            "available_processors",
            Integer.toString(Runtime.getRuntime().availableProcessors())
        );
        values.put(
            "max_heap_bytes",
            Long.toString(Runtime.getRuntime().maxMemory())
        );
        values.put(
            "consumer_distribution",
            result.consumerDistribution().toString()
        );
        reporter.publishEntry(values);
        Map<String, Object> jsonValues = new LinkedHashMap<>(values);
        String json = JsonObject.FromMap(jsonValues).toJson();
        try {
            Files.writeString(
                resultsFile,
                json + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write Queue load-test result",
                exception
            );
        }
        System.out.println("QUEUE_LOAD_RESULT " + json);
    }

    private static boolean hasScenarioFailure(ScenarioResult result) {
        return !result.completedBeforeDeadline()
            || result.requested() != result.accepted()
            || result.requested() != result.unique()
            || result.requested() != result.callbacks()
            || result.duplicates() != 0
            || result.missing() != 0
            || result.crossQueue() != 0
            || result.pendingAfterClose() != 0
            || result.cleanupDeleted() != 0
            || result.pendingAfterCleanup() != 0
            || result.drainStartRemaining() < 0
            || result.drainStartRemaining() > result.requested()
            || result.maxSubscriptionConcurrency() != 1
            || result.failure() != null;
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void acquire(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static <T> T get(Future<T> future, Deadline deadline)
        throws Exception {
        try {
            return future.get(
                deadline.remainingNanos(),
                TimeUnit.NANOSECONDS
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static void get(
        CompletableFuture<Void> future,
        Deadline deadline
    ) throws Exception {
        try {
            future.get(
                deadline.remainingNanos(),
                TimeUnit.NANOSECONDS
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof CompletionException completion
                && completion.getCause() != null) {
                cause = completion.getCause();
            }
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private enum PublishMode {
        SYNC_SINGLE,
        SYNC_BATCH,
        ASYNC_BATCH
    }

    private record PublishResult(
        int accepted,
        long startedAt,
        long finishedAt
    ) {
    }

    private record DrainCheckpoint(long startedAt, int remaining) {
    }

    public static final class LoadEvent extends SerializableObject
        implements DispatchEvent {

        private String runId;
        private int sequence;
        private long publishedNanos;
        private String payload;

        public LoadEvent() {
        }

        LoadEvent(
            String runId,
            int sequence,
            long publishedNanos,
            String payload
        ) {
            this.runId = runId;
            this.sequence = sequence;
            this.publishedNanos = publishedNanos;
            this.payload = payload;
        }

        @Override
        public String key() {
            return runId + ":" + sequence;
        }

        @Override
        public DSLContext dsl() {
            return null;
        }

        public String runId() {
            return runId;
        }

        public int sequence() {
            return sequence;
        }

        public long publishedNanos() {
            return publishedNanos;
        }

        public String payload() {
            return payload;
        }

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }

        public int getSequence() {
            return sequence;
        }

        public void setSequence(int sequence) {
            this.sequence = sequence;
        }

        public long getPublishedNanos() {
            return publishedNanos;
        }

        public void setPublishedNanos(long publishedNanos) {
            this.publishedNanos = publishedNanos;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }

    private static final class DeliveryProbe {

        private final String runId;
        private final int requested;
        private final AtomicIntegerArray seen;
        private final AtomicLongArray latencyNanos;
        private final CountDownLatch uniqueDelivered;
        private final LongAdder callbacks = new LongAdder();
        private final LongAdder duplicates = new LongAdder();
        private final LongAdder crossQueue = new LongAdder();
        private final AtomicReference<Throwable> failure =
            new AtomicReference<>();
        private final List<SubscriptionProbe> subscriptions;

        private DeliveryProbe(
            String runId,
            int requested,
            int subscriptionCount
        ) {
            this.runId = runId;
            this.requested = requested;
            this.seen = new AtomicIntegerArray(requested);
            this.latencyNanos = new AtomicLongArray(requested);
            this.uniqueDelivered = new CountDownLatch(requested);
            this.subscriptions = new ArrayList<>(subscriptionCount);
            for (int index = 0; index < subscriptionCount; index++) {
                subscriptions.add(new SubscriptionProbe());
            }
        }

        private Consumer<LoadEvent> consumer(int index) {
            SubscriptionProbe subscription = subscriptions.get(index);
            return event -> {
                subscription.enter();
                try {
                    callbacks.increment();
                    subscription.deliveries.increment();
                    if (!runId.equals(event.runId())) {
                        crossQueue.increment();
                        failure.compareAndSet(
                            null,
                            new AssertionError(
                                "Event crossed logical Queue boundary: "
                                    + event.runId()
                            )
                        );
                        return;
                    }
                    int sequence = event.sequence();
                    if (sequence < 0 || sequence >= requested) {
                        failure.compareAndSet(
                            null,
                            new AssertionError(
                                "Event sequence is outside the scenario: "
                                    + sequence
                            )
                        );
                        return;
                    }
                    int count = seen.incrementAndGet(sequence);
                    if (count == 1) {
                        latencyNanos.set(
                            sequence,
                            System.nanoTime() - event.publishedNanos()
                        );
                        uniqueDelivered.countDown();
                    } else {
                        duplicates.increment();
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    subscription.exit();
                }
            };
        }

        private boolean await(int timeoutSeconds)
            throws InterruptedException {
            return uniqueDelivered.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        private DrainCheckpoint drainCheckpoint() {
            long startedAt = System.nanoTime();
            int remaining = Math.toIntExact(uniqueDelivered.getCount());
            return new DrainCheckpoint(startedAt, remaining);
        }

        private ScenarioResult result(
            String scenario,
            LoadConfig loadConfig,
            int accepted,
            boolean completed,
            long publishStarted,
            long publishFinished,
            long drainStarted,
            int drainStartRemaining,
            long completedAt,
            double shutdownMillis,
            int pendingAfterClose,
            int cleanupDeleted,
            int pendingAfterCleanup
        ) {
            int unique = 0;
            int missing = 0;
            long[] latencies = new long[requested];
            int latencyCount = 0;
            for (int sequence = 0; sequence < requested; sequence++) {
                if (seen.get(sequence) == 0) {
                    missing++;
                } else {
                    unique++;
                    latencies[latencyCount++] = latencyNanos.get(sequence);
                }
            }
            latencies = Arrays.copyOf(latencies, latencyCount);
            Arrays.sort(latencies);
            Map<Integer, Long> distribution = new LinkedHashMap<>();
            int maxConcurrency = 0;
            for (int index = 0; index < subscriptions.size(); index++) {
                SubscriptionProbe subscription = subscriptions.get(index);
                distribution.put(index, subscription.deliveries.sum());
                maxConcurrency = Math.max(
                    maxConcurrency,
                    subscription.maxActive.get()
                );
            }
            long publishNanos = publishFinished - publishStarted;
            long drainNanos = completedAt - drainStarted;
            long totalNanos = completedAt - publishStarted;
            return new ScenarioResult(
                scenario,
                runId,
                loadConfig,
                requested,
                accepted,
                callbacks.intValue(),
                unique,
                duplicates.intValue(),
                missing,
                crossQueue.intValue(),
                pendingAfterClose,
                cleanupDeleted,
                pendingAfterCleanup,
                drainStartRemaining,
                maxConcurrency,
                completed,
                failure.get(),
                millis(publishNanos),
                millis(drainNanos),
                millis(totalNanos),
                shutdownMillis,
                perSecond(accepted, publishNanos),
                perSecond(drainStartRemaining, drainNanos),
                perSecond(unique, totalNanos),
                percentileMillis(latencies, 0.50),
                percentileMillis(latencies, 0.95),
                percentileMillis(latencies, 0.99),
                percentileMillis(latencies, 1.00),
                Collections.unmodifiableMap(distribution)
            );
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0;
        }

        private static double perSecond(int count, long nanos) {
            if (nanos <= 0) {
                return 0;
            }
            return count * 1_000_000_000.0 / nanos;
        }

        private static double percentileMillis(
            long[] sorted,
            double percentile
        ) {
            if (sorted.length == 0) {
                return 0;
            }
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            int bounded = Math.max(0, Math.min(index, sorted.length - 1));
            return millis(sorted[bounded]);
        }
    }

    private static final class SubscriptionProbe {

        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final LongAdder deliveries = new LongAdder();

        private void enter() {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
        }

        private void exit() {
            active.decrementAndGet();
        }
    }

    private record ScenarioResult(
        String scenario,
        String runId,
        LoadConfig loadConfig,
        int requested,
        int accepted,
        int callbacks,
        int unique,
        int duplicates,
        int missing,
        int crossQueue,
        int pendingAfterClose,
        int cleanupDeleted,
        int pendingAfterCleanup,
        int drainStartRemaining,
        int maxSubscriptionConcurrency,
        boolean completedBeforeDeadline,
        Throwable failure,
        double publishMillis,
        double drainMillis,
        double totalMillis,
        double shutdownMillis,
        double publishPerSecond,
        double deliveryPerSecond,
        double endToEndPerSecond,
        double p50Millis,
        double p95Millis,
        double p99Millis,
        double maxMillis,
        Map<Integer, Long> consumerDistribution
    ) {
    }

    private record Deadline(long expiresAt) {

        private static Deadline afterSeconds(int seconds) {
            return new Deadline(
                System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            );
        }

        private long remainingNanos() throws TimeoutException {
            long remaining = expiresAt - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Queue load-test deadline elapsed");
            }
            return remaining;
        }
    }

    private record LoadConfig(
        String profile,
        int eventCount,
        int publisherThreads,
        int consumerSubscriptions,
        int queueInstances,
        int batchSize,
        int maxAsyncInFlight,
        int payloadBytes,
        int repetitions,
        int publishTimeoutSeconds,
        int drainTimeoutSeconds,
        int shutdownTimeoutSeconds,
        int poolSize,
        int pollIntervalMillis
    ) {

        private LoadConfig {
            positive(eventCount, "eventCount");
            positive(publisherThreads, "publisherThreads");
            positive(consumerSubscriptions, "consumerSubscriptions");
            positive(queueInstances, "queueInstances");
            positive(batchSize, "batchSize");
            if (batchSize > eventCount) {
                throw new IllegalArgumentException(
                    "batchSize must not exceed eventCount"
                );
            }
            positive(maxAsyncInFlight, "maxAsyncInFlight");
            if (payloadBytes < 0) {
                throw new IllegalArgumentException(
                    "payloadBytes must not be negative"
                );
            }
            positive(repetitions, "repetitions");
            positive(publishTimeoutSeconds, "publishTimeoutSeconds");
            positive(drainTimeoutSeconds, "drainTimeoutSeconds");
            positive(shutdownTimeoutSeconds, "shutdownTimeoutSeconds");
            positive(poolSize, "poolSize");
            positive(pollIntervalMillis, "pollIntervalMillis");
            long methodBudgetSeconds = (long) repetitions
                * ((long) publishTimeoutSeconds
                    + drainTimeoutSeconds
                    + shutdownTimeoutSeconds);
            if (methodBudgetSeconds >= TimeUnit.HOURS.toSeconds(12)) {
                throw new IllegalArgumentException(
                    "Configured Queue load-test deadlines must fit within "
                        + "the 12-hour method timeout"
                );
            }
        }

        private static LoadConfig fromEnvironment() {
            String profile = environment(
                "FLOW_QUEUE_LOAD_PROFILE",
                "smoke"
            ).toLowerCase(java.util.Locale.ROOT);
            LoadConfig defaults = switch (profile) {
                case "smoke" -> new LoadConfig(
                    profile, 2_000, 4, 4, 2, 50, 64, 128,
                    1, 60, 60, 30, 16, 10
                );
                case "baseline" -> new LoadConfig(
                    profile, 50_000, 8, 8, 4, 100, 128, 1_024,
                    3, 300, 300, 60, 48, 5
                );
                case "soak" -> new LoadConfig(
                    profile, 1_000_000, 16, 32, 8, 250, 512, 1_024,
                    10, 1_800, 1_800, 120, 80, 10
                );
                default -> throw new IllegalArgumentException(
                    "Unknown FLOW_QUEUE_LOAD_PROFILE: " + profile
                );
            };
            return new LoadConfig(
                profile,
                integer("FLOW_QUEUE_LOAD_EVENT_COUNT", defaults.eventCount),
                integer(
                    "FLOW_QUEUE_LOAD_PUBLISHER_THREADS",
                    defaults.publisherThreads
                ),
                integer(
                    "FLOW_QUEUE_LOAD_CONSUMER_SUBSCRIPTIONS",
                    defaults.consumerSubscriptions
                ),
                integer(
                    "FLOW_QUEUE_LOAD_QUEUE_INSTANCES",
                    defaults.queueInstances
                ),
                integer("FLOW_QUEUE_LOAD_BATCH_SIZE", defaults.batchSize),
                integer(
                    "FLOW_QUEUE_LOAD_MAX_ASYNC_IN_FLIGHT",
                    defaults.maxAsyncInFlight
                ),
                integer(
                    "FLOW_QUEUE_LOAD_PAYLOAD_BYTES",
                    defaults.payloadBytes
                ),
                integer(
                    "FLOW_QUEUE_LOAD_REPETITIONS",
                    defaults.repetitions
                ),
                integer(
                    "FLOW_QUEUE_LOAD_PUBLISH_TIMEOUT_SECONDS",
                    defaults.publishTimeoutSeconds
                ),
                integer(
                    "FLOW_QUEUE_LOAD_DRAIN_TIMEOUT_SECONDS",
                    defaults.drainTimeoutSeconds
                ),
                integer(
                    "FLOW_QUEUE_LOAD_SHUTDOWN_TIMEOUT_SECONDS",
                    defaults.shutdownTimeoutSeconds
                ),
                integer("FLOW_QUEUE_LOAD_POOL_SIZE", defaults.poolSize),
                integer(
                    "FLOW_QUEUE_LOAD_POLL_INTERVAL_MILLIS",
                    defaults.pollIntervalMillis
                )
            );
        }

        private LoadConfig halfConcurrency() {
            return new LoadConfig(
                profile,
                Math.max(1, eventCount / 2),
                Math.max(1, publisherThreads / 2),
                Math.max(1, consumerSubscriptions / 2),
                Math.max(1, queueInstances / 2),
                Math.min(batchSize, Math.max(1, eventCount / 2)),
                Math.max(1, maxAsyncInFlight / 2),
                payloadBytes,
                1,
                publishTimeoutSeconds,
                drainTimeoutSeconds,
                shutdownTimeoutSeconds,
                poolSize,
                pollIntervalMillis
            );
        }

        private LoadConfig backlog() {
            return new LoadConfig(
                profile,
                eventCount,
                1,
                consumerSubscriptions,
                queueInstances,
                batchSize,
                maxAsyncInFlight,
                payloadBytes,
                1,
                publishTimeoutSeconds,
                drainTimeoutSeconds,
                shutdownTimeoutSeconds,
                poolSize,
                pollIntervalMillis
            );
        }

        private static int integer(String name, int defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                    name + " must be an integer",
                    exception
                );
            }
        }

        private static String environment(
            String name,
            String defaultValue
        ) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? defaultValue : value;
        }

        private static void positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                    name + " must be positive"
                );
            }
        }
    }
}
