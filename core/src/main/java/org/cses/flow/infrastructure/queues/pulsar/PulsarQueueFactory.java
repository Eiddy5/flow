package org.cses.flow.infrastructure.queues.pulsar;

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.InjectionPoint;
import org.cses.flow.queues.QueueException;
import org.cses.flow.queues.Queue;
import org.cses.flow.queues.annotations.FlowQueue;
import org.paas.common.util.InjectUtil;
import org.paas.pulsar.PulsarFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves typed queue injection and rejects conflicting Flow-owned names and topics. */
@Factory
public class PulsarQueueFactory {
    private BeanProvider<PulsarFactory> paas;
    private Map<String, Class<?>> names = new HashMap<>();
    private Map<String, Class<?>> topics = new HashMap<>();
    private Map<Class<?>, PulsarQueue<?>> queues = new HashMap<>();

    /**
     * Retains a lazy PAAS provider so an application without Flow Pulsar usage stays inactive.
     * @param paas provider owned by the current application context
     */
    public PulsarQueueFactory(BeanProvider<PulsarFactory> paas) {
        this.paas = Objects.requireNonNull(paas, "paas");
    }

    /**
     * Resolves the concrete message type at a field or constructor injection point.
     * @param <T> requested message type
     * @param injectionPoint injection metadata; raw and untyped injection is rejected
     * @return cached typed publisher
     * @throws QueueException when the type is missing or its queue declaration is invalid
     */
    @Prototype
    @Bean(typed = {PulsarQueue.class, Queue.class})
    @SuppressWarnings("unchecked")
    <T> PulsarQueue<T> queue(InjectionPoint<?> injectionPoint) {
        Argument<?>[] parameters = InjectUtil.extractArgument(injectionPoint, "PulsarQueue").getTypeParameters();
        if (parameters.length != 1 || parameters[0].getType() == Object.class) {
            throw new QueueException("PulsarQueue injection requires a concrete message type");
        }
        return get((Class<T>) parameters[0].getType());
    }

    /**
     * Returns one publisher per declared message type, reusing PAAS's shared topic resource.
     * @param <T> concrete message type
     * @param messageType nonnull class declaring FlowQueue
     * @return cached publisher; no independent ownership of PAAS resources
     * @throws QueueException when declaration or PAAS initialization fails
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> PulsarQueue<T> get(Class<T> messageType) {
        FlowQueue declaration = declare(messageType);
        try {
            return (PulsarQueue<T>) queues.computeIfAbsent(messageType, ignored ->
                new PulsarQueue<>(declaration.name(), messageType,
                    paas.get().get(declaration.topic(), messageType)));
        } catch (Exception exception) {
            throw new QueueException("Cannot initialize Pulsar queue: " + declaration.name(), exception);
        }
    }

    /**
     * Validates and reserves a declaration without opening a broker connection.
     * @param messageType nonnull annotated type; parameterized message roots are unsupported
     * @return its annotation, whose name and topic have no surrounding whitespace
     * @throws QueueException when the declaration is absent, blank or conflicts with another type
     */
    synchronized FlowQueue declare(Class<?> messageType) {
        Objects.requireNonNull(messageType, "messageType");
        FlowQueue declaration = messageType.getDeclaredAnnotation(FlowQueue.class);
        if (declaration == null || messageType.getTypeParameters().length != 0) {
            throw new QueueException("A concrete @FlowQueue message type is required: " + messageType.getName());
        }
        requireName(declaration.name(), "Queue name");
        requireName(declaration.topic(), "Queue topic");
        if (names.containsKey(declaration.name()) && names.get(declaration.name()) != messageType
            || topics.containsKey(declaration.topic()) && topics.get(declaration.topic()) != messageType) {
            throw new QueueException("Conflicting Flow queue name or topic: " + messageType.getName());
        }
        names.put(declaration.name(), messageType);
        topics.put(declaration.topic(), messageType);
        return declaration;
    }

    /**
     * Validates a literal annotation name without silently normalizing its identity.
     * @param value configured name
     * @param label field name included in failure messages
     * @throws QueueException when the name is blank or has surrounding whitespace
     */
    static void requireName(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new QueueException(label + " must be nonblank and have no surrounding whitespace");
        }
    }
}
