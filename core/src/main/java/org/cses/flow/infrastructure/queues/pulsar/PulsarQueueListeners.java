package org.cses.flow.infrastructure.queues.pulsar;

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.context.processor.ExecutableMethodProcessor;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.apache.pulsar.client.api.Message;
import org.cses.flow.queues.QueueException;
import org.cses.flow.queues.annotations.FlowQueue;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.paas.pulsar.PulsarConsumerRegistration;
import org.paas.pulsar.PulsarFactory;

import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Validates Micronaut executable methods, then hands their callbacks to PAAS once
 * at startup. The generated registrations are not beans, so PAAS's own startup
 * scanner cannot register them a second time. PAAS owns their threads and shutdown.
 */
@Singleton
@Requires(property = "pulsar.consumer.enabled", notEquals = "false")
public class PulsarQueueListeners implements ExecutableMethodProcessor<FlowQueueListener> {
    private BeanContext context;
    private PulsarQueueFactory queues;
    private BeanProvider<PulsarFactory> paas;
    private Map<String, Map<String, Registration<?>>> registrations = new LinkedHashMap<>();
    private boolean started;

    /**
     * Retains context metadata and lazy PAAS access without connecting to a broker.
     * @param context application owning the callback beans
     * @param queues declaration validator shared with publisher injection
     * @param paas lazy application-owned PAAS factory
     */
    public PulsarQueueListeners(BeanContext context, PulsarQueueFactory queues, BeanProvider<PulsarFactory> paas) {
        this.context = context;
        this.queues = queues;
        this.paas = paas;
    }

    /**
     * Registers one annotated method's metadata before any Flow consumer is started.
     * @param <B> callback bean type
     * @param beanDefinition owning singleton definition; not instantiated during validation
     * @param method compiled public instance method with one annotated message parameter and void return
     * @throws QueueException when signature, annotation values or subscription identity conflict
     */
    @Override
    public synchronized <B> void process(BeanDefinition<B> beanDefinition, ExecutableMethod<B, ?> method) {
        if (started) {
            throw new QueueException("Flow queue listeners must be declared before startup");
        }
        int modifiers = method.getTargetMethod().getModifiers();
        if (!beanDefinition.isSingleton() || !Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)
            || method.getArguments().length != 1 || method.getReturnType().getType() != void.class) {
            throw new QueueException("@FlowQueueListener requires a public void instance method with one message argument on a singleton: " + method);
        }
        var annotation = method.getAnnotationMetadata().getAnnotation(FlowQueueListener.class);
        String subscription = annotation.stringValue("subscription").orElse("");
        int concurrency = annotation.intValue("concurrency").orElse(1);
        PulsarQueueFactory.requireName(subscription, "Subscription");
        if (concurrency <= 0) {
            throw new QueueException("Listener concurrency must be positive: " + method);
        }
        Class<?> messageType = method.getArguments()[0].getType();
        FlowQueue queue = queues.declare(messageType);
        Map<String, Registration<?>> subscriptions = registrations.computeIfAbsent(queue.topic(), ignored -> new LinkedHashMap<>());
        if (subscriptions.containsKey(subscription)) {
            throw new QueueException("Duplicate Flow subscription: " + queue.topic() + "/" + subscription);
        }
        subscriptions.put(subscription, registration(beanDefinition, method, messageType, queue.topic(), subscription, concurrency));
    }

    /**
     * Starts validated consumers once; an application with no declarations never resolves PAAS.
     * @param event application startup notification
     * @throws RuntimeException when callback bean or PAAS initialization fails
     */
    @EventListener
    public synchronized void onStartup(StartupEvent event) {
        if (started) {
            return;
        }
        started = true;
        for (Map<String, Registration<?>> subscriptions : registrations.values()) {
            for (Registration<?> registration : subscriptions.values()) {
                registration.prepare();
            }
        }
        for (Map<String, Registration<?>> subscriptions : registrations.values()) {
            for (Registration<?> registration : subscriptions.values()) {
                registration.pulsarFactory = paas.get();
                registration.listen();
            }
        }
    }

    /**
     * Captures a type-checked executable method as a PAAS callback without exposing broker messages to business code.
     * @param <B> callback bean type
     * @param <T> message type
     * @param beanDefinition callback owner
     * @param method validated compiled method
     * @param messageType validated message class
     * @param topic literal PAAS topic
     * @param subscription durable consumer group
     * @param concurrency positive number of consumers
     * @return registration whose thrown business errors reach PAAS's NACK path
     */
    private <B, T> Registration<T> registration(
        BeanDefinition<B> beanDefinition, ExecutableMethod<B, ?> method,
        Class<T> messageType, String topic, String subscription, int concurrency
    ) {
        return new Registration<>(messageType, topic, subscription, concurrency,
            () -> {
                B bean = context.getBean(beanDefinition);
                return event -> method.invoke(bean, event);
            });
    }

    /** Adapts one application callback to PAAS's boolean acknowledgment convention. */
    private static class Registration<T> extends PulsarConsumerRegistration<T> {
        private Class<T> type;
        private String topic;
        private String subscription;
        private int concurrency;
        private Supplier<Consumer<T>> initializer;
        private Consumer<T> callback;

        /**
         * Captures validated metadata and a synchronous callback.
         * @param type message type
         * @param topic PAAS topic
         * @param subscription durable group
         * @param concurrency positive consumer count
         * @param initializer resolves the callback bean before any consumer is started
         */
        Registration(Class<T> type, String topic, String subscription, int concurrency,
                     Supplier<Consumer<T>> initializer) {
            this.type = type;
            this.topic = topic;
            this.subscription = subscription;
            this.concurrency = concurrency;
            this.initializer = initializer;
        }

        /** Resolves the singleton callback once, surfacing injection failures before subscriptions start. */
        void prepare() { callback = initializer.get(); }

        /**
         * Supplies the destination to PAAS.
         * @return literal topic; PAAS applies environment naming
         */
        @Override
        public String getTopic() { return topic; }

        /**
         * Supplies the consumer group to PAAS.
         * @return durable group; PAAS applies environment naming
         */
        @Override
        public String getSubscriptionName() { return subscription; }

        /**
         * Supplies the configured receiver count to PAAS.
         * @return number of serial PAAS consumers for this registration
         */
        @Override
        public int getCustomerCount() { return concurrency; }

        /**
         * Supplies the payload class to PAAS's schema creation.
         * @return declared class used by PAAS's JacksonSchema
         */
        @Override
        public Class<T> getContentType() { return type; }

        /**
         * Invokes the business callback before allowing PAAS to acknowledge.
         * @param message broker message, required to contain a nonnull value of the declared type
         * @return true only after the callback returns normally
         * @throws RuntimeException when decoding, validation or business handling fails; PAAS requests redelivery
         */
        @Override
        public boolean onTopicMessage(Message<T> message) {
            callback.accept(type.cast(Objects.requireNonNull(message.getValue(), "message value")));
            return true;
        }
    }
}
