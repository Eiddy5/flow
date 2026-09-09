package org.cses.flow.infrastructure.queues.pulsar;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.executor.commands.Cancel;
import org.cses.flow.executor.commands.Create;
import org.cses.flow.executor.commands.ExecutionCommand;
import org.cses.flow.executor.commands.Resume;
import org.cses.flow.executor.commands.Rewind;
import org.cses.flow.queues.Queue;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.junit.jupiter.api.Test;
import org.paas.pulsar.JacksonSchema;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real broker proof for the exact production message types and both annotation-driven queues. */
class ExecutorPulsarIntegrationTest {
    private static final String COMMAND_SUBSCRIPTION = "executor-command-integration";
    private static final String EVENT_SUBSCRIPTION = "executor-event-integration";

    /** Verifies every concrete durable command and the scheduling event with PAAS's real schema. */
    @Test
    void roundTripsAllProductionPayloadsThroughPaasSchema() {
        try (ApplicationContext ignored = open(false)) {
            User user = new User();
            user.setId("actor-1");
            user.setCompanyId("company-1");
            Session<User> session = new Session<>();
            session.setUser(user);
            List<ExecutionCommand> commands = List.of(
                Create.from("company-1", "execution-1", "actor-1", "flow-1", 3, Map.of("value", "hello")),
                Resume.from("execution-1", "company-1", "actor-1", "pause-1", Map.of("approved", true)),
                Cancel.from("execution-1", "company-1", "actor-1"),
                Rewind.from(session, "execution-1", "pause-1", "task-1", "repeat review")
            );
            JacksonSchema<ExecutionCommand> schema = new JacksonSchema<>(ExecutionCommand.class);
            for (ExecutionCommand command : commands) {
                ExecutionCommand decoded = schema.decode(schema.encode(command));
                assertEquals(command.getClass(), decoded.getClass());
                assertEquals(command, decoded);
                assertDoesNotThrow(decoded::validate);
            }
            ExecutorEvent event = ExecutorEvent.from("execution-1", "company-1", ExecutorEvent.EventType.UPDATED);
            JacksonSchema<ExecutorEvent> events = new JacksonSchema<>(ExecutorEvent.class);
            assertEquals(event, events.decode(events.encode(event)));
        }
    }

    /**
     * Verifies real subscription delivery, callback-before-ACK, cross-queue publish and NACK redelivery.
     * @throws Exception when broker access or bounded callback waits fail
     */
    @Test
    void consumesBothProductionQueuesAndAcknowledgesAfterCallbacks() throws Exception {
        try (PulsarAdmin admin = PulsarTestEnvironment.admin()) {
            try (ApplicationContext context = open(true)) {
                Publisher publisher = context.getBean(Publisher.class);
                Handler handler = context.getBean(Handler.class);
                assertSame(publisher.commands, publisher.concrete);
                assertInstanceOf(PulsarQueue.class, publisher.events);
                assertEquals(ExecutionCommand.QUEUE_NAME, publisher.commands.queueName());
                assertEquals(ExecutorEvent.QUEUE_NAME, publisher.events.queueName());
                Create command = Create.from("broker-test", "broker-execution", "actor", "flow", 1, Map.of("value", "from-broker"));
                publisher.commands.emitAsync(command).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertTrue(handler.entered.await(10, TimeUnit.SECONDS));
                assertEquals(command, handler.received);
                var before = admin.topics().getStats(topic(ExecutionCommand.QUEUE_NAME))
                    .getSubscriptions().get(COMMAND_SUBSCRIPTION);
                assertTrue(before.getMsgBacklog() > 0, "Running callback remains unacknowledged on the broker");
                handler.release.countDown();
                ExecutorEvent expected = ExecutorEvent.from("broker-execution", "broker-test", ExecutorEvent.EventType.CREATED);
                assertEquals(expected, handler.completed.poll(15, TimeUnit.SECONDS));
                assertEquals(2, handler.attempts.get(), "Exactly one callback failure followed by broker redelivery");
                assertEquals(List.of(expected, expected), handler.deliveries);
                awaitAcknowledged(admin, ExecutionCommand.QUEUE_NAME, COMMAND_SUBSCRIPTION);
                awaitAcknowledged(admin, ExecutorEvent.QUEUE_NAME, EVENT_SUBSCRIPTION);
            } finally {
                admin.topics().deleteSubscription(topic(ExecutionCommand.QUEUE_NAME), COMMAND_SUBSCRIPTION, true);
                admin.topics().deleteSubscription(topic(ExecutorEvent.QUEUE_NAME), EVENT_SUBSCRIPTION, true);
                // Production subscriptions can already exist when the full suite repeats these exact topics.
                PulsarTestEnvironment.clearExecutorBacklogs();
            }
        }
    }

    /**
     * Opens actual PAAS components without a Flow repository, so only the test callback is registered.
     * @param callbacks whether to register the controlled callback behavior
     * @return context owned by the caller
     */
    private static ApplicationContext open(boolean callbacks) {
        Map<String, Object> properties = new LinkedHashMap<>(PulsarTestEnvironment.properties());
        properties.putAll(Map.of("datasources.default.enabled", false,
            "micronaut.config-client.enabled", false, "consul.client.registration.enabled", false,
            "consul.client.watch.service.enabled", false, "grpc.server.enabled", false,
            "thrift.server.enabled", false, "jooq.send-event", false,
            "flow.executor.pulsar.callback-test", callbacks));
        return ApplicationContext.builder().deduceEnvironment(false).properties(properties).start();
    }

    /**
     * Waits for the broker's durable subscription cursor to acknowledge the tested messages.
     * @param admin real broker admin connection
     * @param queue topic local name
     * @param subscription tested durable group
     * @throws Exception when querying the broker fails
     */
    private static void awaitAcknowledged(PulsarAdmin admin, String queue, String subscription) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var stats = admin.topics().getStats(topic(queue)).getSubscriptions().get(subscription);
            if (stats.getMsgBacklog() == 0 && stats.getUnackedMessages() == 0) return;
            new CountDownLatch(1).await(20, TimeUnit.MILLISECONDS);
        } while (System.nanoTime() < deadline);
        fail("Broker subscription did not acknowledge all messages: " + queue + "/" + subscription);
    }

    /**
     * @param name queue local name
     * @return fully qualified test broker topic
     */
    private static String topic(String name) { return "persistent://public/default/" + name; }

    /** Publishes through the same generic interfaces injected into production services. */
    @Singleton
    @Requires(property = "flow.executor.pulsar.callback-test", value = "true")
    public static class Publisher {
        private Queue<ExecutionCommand> commands;
        private Queue<ExecutorEvent> events;
        @Inject PulsarQueue<ExecutionCommand> concrete;

        /**
         * @param commands command publisher
         * @param events scheduling publisher
         */
        public Publisher(Queue<ExecutionCommand> commands, Queue<ExecutorEvent> events) {
            this.commands = commands;
            this.events = events;
        }
    }

    /** Uses actual production message types but controlled callback outcomes at the consumer boundary. */
    @Singleton
    @Requires(property = "flow.executor.pulsar.callback-test", value = "true")
    public static class Handler {
        private Queue<ExecutorEvent> events;
        private CountDownLatch entered = new CountDownLatch(1);
        private CountDownLatch release = new CountDownLatch(1);
        private AtomicInteger attempts = new AtomicInteger();
        private List<ExecutorEvent> deliveries = new CopyOnWriteArrayList<>();
        private BlockingQueue<ExecutorEvent> completed = new LinkedBlockingQueue<>();
        private volatile ExecutionCommand received;

        /** @param events actual production event queue publisher */
        public Handler(Queue<ExecutorEvent> events) { this.events = events; }

        /**
         * Holds one command open, then publishes its scheduling signal before permitting ACK.
         * @param command message received from the actual broker
         * @throws InterruptedException when the controlled wait is interrupted
         */
        @FlowQueueListener(subscription = COMMAND_SUBSCRIPTION)
        public void command(ExecutionCommand command) throws InterruptedException {
            received = command;
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("callback not released");
            Create create = (Create) command;
            events.emit(ExecutorEvent.from(create.executionId(), create.companyId(), ExecutorEvent.EventType.CREATED));
        }

        /** @param event message retried by the real broker after the first callback throws */
        @FlowQueueListener(subscription = EVENT_SUBSCRIPTION)
        public void event(ExecutorEvent event) {
            deliveries.add(event);
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("retry this broker delivery once");
            completed.add(event);
        }
    }
}
