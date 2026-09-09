package org.cses.flow.infrastructure.queues.pulsar;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Executable;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.pulsar.client.api.*;
import org.apache.pulsar.client.impl.auth.AuthenticationDisabled;
import org.cses.flow.queues.QueueException;
import org.cses.flow.queues.annotations.FlowQueue;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.junit.jupiter.api.Test;
import org.paas.pulsar.PulsarFactory;
import org.paas.pulsar.PulsarLifecycleManager;
import org.paas.pulsar.JacksonSchema;
import org.paas.pulsar.config.PulsarClientConfiguration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs actual Micronaut discovery, PAAS publishing and PAAS consumer runners with
 * a controlled client boundary. These tests prove callback/ACK wiring, not broker durability.
 */
class PulsarQueueTest {

    /** Verifies field/constructor injection, distinct topics, publishing modes and asynchronous errors. */
    @Test
    void injectsTypedPublishersAndPreservesSendFailures() {
        Transport transport = new Transport();
        try (ApplicationContext context = open("valid", transport)) {
            Publisher publisher = context.getBean(Publisher.class);
            assertEquals("first", publisher.first.queueName());
            assertEquals("second", publisher.second.queueName());
            First event = First.from("hello");
            JacksonSchema<First> schema = new JacksonSchema<>(First.class);
            assertEquals(event, schema.decode(schema.encode(event)));
            publisher.first.emit(event);
            publisher.first.emitWithKey(event, "tenant/execution");
            publisher.first.emitAt(event, 1234L);
            publisher.second.emitAsync(Second.from("world")).toCompletableFuture().join();
            assertEquals(List.of("first-topic:send", "first-topic:send", "first-topic:send", "second-topic:sendAsync"), transport.sends);
            assertEquals("tenant/execution", transport.lastKey);
            assertEquals(1234L, transport.lastDeliveryTime);
            assertSame(publisher.first, context.getBean(PulsarQueueFactory.class).get(First.class));

            transport.failSend = true;
            assertThrows(QueueException.class, () -> publisher.first.emit(event));
            CompletionException failure = assertThrows(CompletionException.class,
                () -> publisher.first.emitAsync(event).toCompletableFuture().join());
            assertInstanceOf(QueueException.class, failure.getCause());
            transport.failImmediately = true;
            CompletionStage<Void> failedStage = assertDoesNotThrow(() -> publisher.first.emitAsync(event));
            assertInstanceOf(QueueException.class, assertThrows(CompletionException.class,
                () -> failedStage.toCompletableFuture().join()).getCause());
            assertThrows(QueueException.class, () -> publisher.first.emit(null));
            assertThrows(QueueException.class, () -> publisher.first.emitAt(event, -1));
            assertThrows(QueueException.class, () -> publisher.first.emitWithKey(event, " "));
        }
        assertTrue(transport.consumers.stream().allMatch(consumer -> consumer.closed));
    }

    /** Verifies one registration per group, configured concurrency, and ACK only after callback completion. */
    @Test
    void acknowledgesOnlyAfterCallbackAndStartsEachGroupOnce() throws Exception {
        Transport transport = new Transport();
        try (ApplicationContext context = open("valid", transport)) {
            Handler handler = context.getBean(Handler.class);
            assertEquals(4, transport.consumers.size());
            assertEquals(2, transport.consumers.stream().filter(value -> value.topic.equals("first-topic") && value.subscription.equals("work")).count());
            assertTrue(transport.consumers.stream().allMatch(value -> value.subscriptionType == SubscriptionType.Shared));
            context.getBean(PulsarQueueListeners.class).onStartup(new StartupEvent(context));
            assertEquals(4, transport.consumers.size());

            Receiver work = transport.receiver("first-topic", "work");
            Message<First> message = message(First.from("block"));
            work.deliver(message);
            assertTrue(handler.entered.await(5, TimeUnit.SECONDS));
            assertTrue(work.actions.isEmpty(), "No ACK while the callback is still running");
            handler.release.countDown();
            assertEquals("ack", work.actions.poll(5, TimeUnit.SECONDS));
            assertSame(message, work.lastAcknowledged);
            assertEquals("block", handler.completed.poll(5, TimeUnit.SECONDS));

            Receiver audit = transport.receiver("first-topic", "audit");
            audit.deliver(message(First.from("audit-event")));
            assertEquals("ack", audit.actions.poll(5, TimeUnit.SECONDS));
            assertEquals("audit-event", handler.audited.poll(5, TimeUnit.SECONDS));
            assertTrue(work.actions.isEmpty());

            Receiver second = transport.receiver("second-topic", "work");
            second.deliver(message(Second.from("other-queue")));
            assertEquals("ack", second.actions.poll(5, TimeUnit.SECONDS));
            assertEquals("other-queue", handler.secondCompleted.poll(5, TimeUnit.SECONDS));
            assertTrue(work.actions.isEmpty());
        }
    }

    /** Verifies business failure and decode failure reach NACK, and a redelivered message may later ACK. */
    @Test
    void negativelyAcknowledgesExceptionsAndAcceptsSuccessfulRedelivery() throws Exception {
        Transport transport = new Transport();
        try (ApplicationContext context = open("valid", transport)) {
            Handler handler = context.getBean(Handler.class);
            Receiver work = transport.receiver("first-topic", "work");
            Message<First> message = message(First.from("retry"));
            work.deliver(message);
            assertEquals("nack", work.actions.poll(5, TimeUnit.SECONDS));
            assertNull(work.lastAcknowledged);
            work.deliver(message);
            assertEquals("ack", work.actions.poll(5, TimeUnit.SECONDS));
            assertEquals(2, handler.attempts.get());
            assertSame(message, work.lastAcknowledged);

            work.deliver(proxy(Message.class, (ignored, method, args) -> {
                if (method.getName().equals("getValue")) throw new IllegalArgumentException("invalid payload");
                if (method.getName().equals("getProperties")) return Map.of();
                return MessageId.earliest;
            }));
            assertEquals("nack", work.actions.poll(5, TimeUnit.SECONDS));
            assertEquals(2, handler.attempts.get());
        }
    }

    /** Records PAAS's current boundary: failed asynchronous ACK does not itself NACK or repeat the callback. */
    @Test
    void exposesPaasAsyncAckFailureBoundary() throws Exception {
        Transport transport = new Transport();
        try (ApplicationContext context = open("valid", transport)) {
            Receiver work = transport.receiver("first-topic", "work");
            work.failAck = true;
            work.deliver(message(First.from("ack-fails")));
            assertEquals("ack-failed", work.actions.poll(5, TimeUnit.SECONDS));
            assertEquals("ack-fails", context.getBean(Handler.class).completed.poll(5, TimeUnit.SECONDS));
            assertNull(work.lastAcknowledged);
            // A second callback proves the real runner continued after the failed ACK future.
            work.failAck = false;
            work.deliver(message(First.from("next")));
            assertEquals("ack", work.actions.poll(5, TimeUnit.SECONDS));
            assertEquals("next", context.getBean(Handler.class).completed.poll(5, TimeUnit.SECONDS));
            assertTrue(work.actions.isEmpty());
        }
    }

    /** Rejects invalid definitions before creating any broker consumers. */
    @Test
    void rejectsInvalidListenerDeclarationsBeforeSubscription() {
        for (String scenario : List.of("duplicate", "return", "concurrency", "unannotated")) {
            Transport transport = new Transport();
            RuntimeException failure = assertThrows(RuntimeException.class, () -> {
                try (ApplicationContext ignored = open(scenario, transport)) {
                    fail("Invalid declaration was accepted: " + scenario);
                }
            });
            assertTrue(causeMessages(failure).contains("Queue" ) || causeMessages(failure).contains("concurrency")
                || causeMessages(failure).contains("subscription"), causeMessages(failure));
            assertTrue(transport.consumers.isEmpty(), scenario);
        }
    }

    /** Rejects reused topic/name identities, unannotated types and raw publisher injection. */
    @Test
    void rejectsConflictingTypesAndRawInjection() {
        Transport transport = new Transport();
        try (ApplicationContext context = open("valid", transport)) {
            PulsarQueueFactory factory = context.getBean(PulsarQueueFactory.class);
            assertThrows(QueueException.class, () -> factory.get(TopicConflict.class));
            assertThrows(QueueException.class, () -> factory.get(NameConflict.class));
            assertThrows(QueueException.class, () -> factory.get(String.class));
            assertThrows(QueueException.class, () -> factory.get(Blank.class));
        }
        try (ApplicationContext context = open("raw", new Transport())) {
            assertThrows(RuntimeException.class, () -> context.getBean(RawPublisher.class));
        }
    }

    /** Proves absence of listener annotations creates neither producers nor consumers. */
    @Test
    void leavesExistingQueueAssemblyInactiveWithoutAnnotations() {
        Transport transport = new Transport();
        try (ApplicationContext context = open("none", transport)) {
            assertNotNull(context.getBean(PulsarQueueFactory.class));
            assertNotNull(context.getBean(PulsarQueueListeners.class));
            assertTrue(transport.consumers.isEmpty());
            assertTrue(transport.sends.isEmpty());
        }
    }

    /**
     * Starts actual Micronaut and PAAS beans while replacing only the network client boundary.
     * @param scenario enabled test-only declarations
     * @param transport controlled producer and consumer endpoints
     * @return running context; caller closes it
     */
    private static ApplicationContext open(String scenario, Transport transport) {
        ApplicationContext context = ApplicationContext.builder().deduceEnvironment(false)
            .properties(properties(scenario)).build();
        context.registerSingleton(PulsarClient.class, transport.client());
        context.registerSingleton(PulsarClientConfiguration.class, proxy(PulsarClientConfiguration.class,
            (ignored, method, args) -> switch (method.getName()) {
                case "getServiceUrl" -> "pulsar://test.invalid:6650";
                case "getAuthentication" -> new AuthenticationDisabled();
                default -> Optional.empty();
            }));
        try {
            return context.start();
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    /**
     * Disables unrelated remote services, without adding Flow queue YAML configuration.
     * @param scenario test bean selection
     * @return immutable context properties
     */
    private static Map<String, Object> properties(String scenario) {
        return Map.of("flow.queue.test.case", scenario,
            "datasources.default.enabled", false, "micronaut.config-client.enabled", false,
            "consul.client.registration.enabled", false, "consul.client.watch.service.enabled", false,
            "grpc.server.enabled", false, "thrift.server.enabled", false,
            "jooq.send-event", false);
    }

    /**
     * Collects nested container failures for useful validation assertions.
     * @param failure outer failure
     * @return nested messages
     */
    private static String causeMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) messages.append(current.getMessage());
        return messages.toString();
    }

    /**
     * Creates a strict interface test double; unexpected protocol calls fail the test.
     * @param <T> interface type
     * @param type interface to proxy
     * @param handler supported protocol behavior
     * @return proxy implementing the interface
     */
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "Test " + type.getSimpleName();
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == args[0];
                default -> throw new AssertionError(method);
            };
            return handler.invoke(instance, method, args);
        }));
    }

    /**
     * Wraps one typed value in a broker message supplied to the actual PAAS runner.
     * @param <T> value type
     * @param value payload
     * @return message with stable test identity
     */
    @SuppressWarnings("unchecked")
    private static <T> Message<T> message(T value) {
        return proxy(Message.class, (ignored, method, args) -> switch (method.getName()) {
            case "getValue" -> value;
            case "getMessageId" -> MessageId.earliest;
            case "getProperties" -> Map.of();
            default -> throw new AssertionError(method);
        });
    }

    @FlowQueue(name = "first", topic = "first-topic")
    public record First(String text) {
        /**
         * @param text payload text
         * @return new test message
         */
        static First from(String text) { return new First(text); }
    }

    @FlowQueue(name = "second", topic = "second-topic")
    public record Second(String text) {
        /**
         * @param text payload text
         * @return new test message
         */
        static Second from(String text) { return new Second(text); }
    }

    @FlowQueue(name = "other", topic = "first-topic")
    public record TopicConflict(String text) {}
    @FlowQueue(name = "first", topic = "other-topic")
    public record NameConflict(String text) {}
    @FlowQueue(name = " ", topic = "blank-topic")
    public record Blank(String text) {}

    /** Keeps PAAS's actual lifecycle while excluding unrelated host WebSocket/Redis consumers from this test. */
    @Singleton
    @Executable
    @Replaces(PulsarLifecycleManager.class)
    @Requires(property = "flow.queue.test.case")
    public static class TestLifecycle extends PulsarLifecycleManager {
        /** @param factory real PAAS factory */
        public TestLifecycle(PulsarFactory factory) {
            super(List.of(), factory);
        }
    }

    @Singleton
    @Requires(property = "flow.queue.test.case", value = "valid")
    public static class Publisher {
        private PulsarQueue<First> first;
        @Inject PulsarQueue<Second> second;
        /** @param first constructor-injected first queue */
        public Publisher(PulsarQueue<First> first) { this.first = first; }
    }

    @Singleton
    @Requires(property = "flow.queue.test.case", value = "raw")
    public static class RawPublisher {
        @Inject PulsarQueue queue;
    }

    @Singleton
    @Requires(property = "flow.queue.test.case", value = "valid")
    public static class Handler {
        private CountDownLatch entered = new CountDownLatch(1);
        private CountDownLatch release = new CountDownLatch(1);
        private BlockingQueue<String> completed = new LinkedBlockingQueue<>();
        private BlockingQueue<String> audited = new LinkedBlockingQueue<>();
        private BlockingQueue<String> secondCompleted = new LinkedBlockingQueue<>();
        private AtomicInteger attempts = new AtomicInteger();

        /**

         * @param event message under test

         * @throws InterruptedException if the controlled callback is interrupted

         */
        @FlowQueueListener(subscription = "work", concurrency = 2)
        public void handle(First event) throws InterruptedException {
            if (event.text().equals("retry") && attempts.incrementAndGet() == 1) throw new IllegalStateException("retry once");
            if (event.text().equals("block")) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("callback not released");
            }
            completed.add(event.text());
        }

        /** @param event independently consumed audit message */
        @FlowQueueListener(subscription = "audit")
        public void audit(First event) { audited.add(event.text()); }

        /** @param event message from a different topic using the same subscription name */
        @FlowQueueListener(subscription = "work")
        public void second(Second event) { secondCompleted.add(event.text()); }
    }

    @Singleton
    @Requires(property = "flow.queue.test.case", value = "duplicate")
    public static class Duplicate {
        /** @param event unused test message */
        @FlowQueueListener(subscription = "duplicate") public void first(First event) {}
        /** @param event unused test message */
        @FlowQueueListener(subscription = "duplicate") public void second(First event) {}
    }

    @Singleton
    @Requires(property = "flow.queue.test.case", value = "return")
    public static class InvalidReturn {
        /**
         * @param event unused test message
         * @return invalid async work
         */
        @FlowQueueListener(subscription = "return") public CompletionStage<Void> handle(First event) { return CompletableFuture.completedFuture(null); }
    }

    @Singleton
    @Requires(property = "flow.queue.test.case", value = "concurrency")
    public static class InvalidConcurrency {
        /** @param event unused test message */
        @FlowQueueListener(subscription = "concurrency", concurrency = 0) public void handle(First event) {}
    }

    @Singleton
    @Requires(property = "flow.queue.test.case", value = "unannotated")
    public static class Unannotated {
        /** @param event unannotated message type */
        @FlowQueueListener(subscription = "unannotated") public void handle(String event) {}
    }

    /** Controlled network boundary; all PAAS factory, producer and runner code remains real. */
    private static class Transport {
        private List<Receiver> consumers = new CopyOnWriteArrayList<>();
        private List<String> sends = new CopyOnWriteArrayList<>();
        private String lastKey;
        private long lastDeliveryTime;
        private boolean failSend;
        private boolean failImmediately;

        /** @return client recording only operations used by the real PAAS implementation */
        PulsarClient client() {
            return proxy(PulsarClient.class, (ignored, method, args) -> switch (method.getName()) {
                case "newConsumer" -> consumerBuilder();
                case "newProducer" -> producerBuilder();
                case "close", "shutdown" -> null;
                case "isClosed" -> false;
                default -> throw new AssertionError(method);
            });
        }

        /** @return builder preserving PAAS subscription settings and starting a controllable endpoint */
        ConsumerBuilder<?> consumerBuilder() {
            Receiver receiver = new Receiver();
            return proxy(ConsumerBuilder.class, (instance, method, args) -> {
                switch (method.getName()) {
                    case "topic" -> receiver.topic = ((String[]) args[0])[0];
                    case "subscriptionName" -> receiver.subscription = (String) args[0];
                    case "subscriptionType" -> receiver.subscriptionType = (SubscriptionType) args[0];
                    case "negativeAckRedeliveryBackoff", "deadLetterPolicy" -> { }
                    case "subscribe" -> { consumers.add(receiver); return receiver.consumer(); }
                    default -> throw new AssertionError(method);
                }
                return instance;
            });
        }

        /** @return builder recording the publication topic */
        ProducerBuilder<?> producerBuilder() {
            List<String> topic = new ArrayList<>();
            return proxy(ProducerBuilder.class, (instance, method, args) -> switch (method.getName()) {
                case "topic" -> { topic.add((String) args[0]); yield instance; }
                case "create" -> proxy(Producer.class, (ignored, producerMethod, producerArgs) -> switch (producerMethod.getName()) {
                    case "newMessage" -> messageBuilder(topic.getFirst());
                    case "flush", "close" -> null;
                    default -> throw new AssertionError(producerMethod);
                });
                default -> throw new AssertionError(method);
            });
        }

        /**

         * @param topic destination

         * @return builder allowing normal and failing sends

         */
        TypedMessageBuilder<?> messageBuilder(String topic) {
            return proxy(TypedMessageBuilder.class, (instance, method, args) -> {
                switch (method.getName()) {
                    case "value", "property" -> { }
                    case "key" -> lastKey = (String) args[0];
                    case "deliverAt" -> lastDeliveryTime = (long) args[0];
                    case "send", "sendAsync" -> {
                        sends.add(topic + ":" + method.getName());
                        if (failImmediately || failSend && method.getName().equals("send")) throw new PulsarClientException("send failed");
                        if (method.getName().equals("send")) return MessageId.earliest;
                        return failSend ? CompletableFuture.failedFuture(new PulsarClientException("async send failed"))
                            : CompletableFuture.completedFuture(MessageId.earliest);
                    }
                    default -> throw new AssertionError(method);
                }
                return instance;
            });
        }

        /**
         * @param topic target topic
         * @param subscription target group
         * @return first endpoint in the group
         */
        Receiver receiver(String topic, String subscription) {
            return consumers.stream().filter(value -> value.topic.equals(topic) && value.subscription.equals(subscription)).findFirst().orElseThrow();
        }
    }

    /** Supplies deterministic messages and records ACK/NACK calls from the actual PAAS runner. */
    private static class Receiver {
        private String topic;
        private String subscription;
        private SubscriptionType subscriptionType;
        private BlockingQueue<Message<?>> messages = new LinkedBlockingQueue<>();
        private BlockingQueue<String> actions = new LinkedBlockingQueue<>();
        private volatile boolean closed;
        private volatile boolean failAck;
        private volatile Message<?> lastAcknowledged;

        /** @param message message supplied to the runner */
        void deliver(Message<?> message) { messages.add(message); }

        /** @return blocking consumer endpoint with explicit acknowledgment outcomes */
        Consumer<?> consumer() {
            return proxy(Consumer.class, (ignored, method, args) -> switch (method.getName()) {
                case "receive" -> {
                    Message<?> next = messages.take();
                    if (closed) throw new PulsarClientException.AlreadyClosedException("test consumer closed");
                    yield next;
                }
                case "acknowledgeAsync" -> {
                    if (failAck) {
                        actions.add("ack-failed");
                        yield CompletableFuture.failedFuture(new PulsarClientException("ACK failed"));
                    }
                    lastAcknowledged = (Message<?>) args[0];
                    actions.add("ack");
                    yield CompletableFuture.completedFuture(null);
                }
                case "negativeAcknowledge" -> { actions.add("nack"); yield null; }
                case "close" -> { closed = true; messages.add(message("close")); yield null; }
                case "isConnected" -> !closed;
                default -> throw new AssertionError(method);
            });
        }
    }
}
