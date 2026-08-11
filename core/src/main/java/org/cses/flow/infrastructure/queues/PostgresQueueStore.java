package org.cses.flow.infrastructure.queues;

import org.cses.flow.infrastructure.queues.entries.QueueMessageEntry;
import org.cses.flow.queues.QueueException;
import org.cses.flow.queues.event.DispatchEvent;
import org.flow.gen.flow.records.FlowQueuesRecord;
import org.jooq.DSLContext;
import org.jooq.InsertSetMoreStep;
import org.x9.jooq.JOOQ;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.flow.gen.flow.Tables.FLOW_QUEUES;

/**
 * PostgreSQL operations hidden behind the Default Dispatch Queue.
 */
final class PostgresQueueStore<T extends DispatchEvent> {

    static final String DISPATCH_QUEUE_TYPE = "DISPATCH";

    private final String queueName;
    private final JOOQ jooq;
    private final Class<T> eventType;

    PostgresQueueStore(
        String queueName,
        JOOQ jooq,
        Class<T> eventType
    ) {
        this.queueName = Objects.requireNonNull(queueName, "queueName");
        this.jooq = Objects.requireNonNull(jooq, "jooq");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
    }

    List<T> snapshot(List<T> events) {
        if (events == null) {
            throw new QueueException("Events must not be null");
        }
        List<T> snapshot = new ArrayList<>(events.size());
        for (T event : events) {
            snapshot.add(requireEvent(event));
        }
        return List.copyOf(snapshot);
    }

    T requireEvent(T event) {
        if (event == null) {
            throw new QueueException("Event must not be null");
        }
        return event;
    }

    List<QueueMessageEntry> prepare(T event) {
        return prepare(List.of(requireEvent(event)));
    }

    List<QueueMessageEntry> prepare(List<T> events) {
        List<QueueMessageEntry> entries = new ArrayList<>(
            events.size()
        );
        for (T event : events) {
            try {
                entries.add(QueueMessageEntry.create(
                    DISPATCH_QUEUE_TYPE,
                    queueName,
                    event
                ));
            } catch (QueueException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new QueueException(
                    "Event could not be encoded for Default Queue: "
                        + queueName,
                    exception
                );
            }
        }
        return List.copyOf(entries);
    }

    void publish(List<QueueMessageEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        try {
            jooq.run(dsl -> insert(dsl, entries));
        } catch (QueueException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw databaseFailure("publish", exception);
        }
    }

    void publish(
        DSLContext dsl,
        List<QueueMessageEntry> entries
    ) {
        if (dsl == null) {
            throw new QueueException("Transactional DSLContext is required");
        }
        if (entries.isEmpty()) {
            return;
        }
        try {
            insert(dsl, entries);
        } catch (QueueException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw databaseFailure("publish transactionally", exception);
        }
    }

    DeliveryAttempt deliverOne(
        Consumer<T> consumer,
        BooleanSupplier deliveryAllowed
    ) {
        try {
            return jooq.runReturn(dsl -> deliverOne(
                dsl,
                consumer,
                deliveryAllowed
            ));
        } catch (QueueException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw databaseFailure("deliver", exception);
        }
    }

    private void insert(
        DSLContext dsl,
        List<QueueMessageEntry> entries
    ) {
        QueueMessageEntry first = entries.getFirst();
        InsertSetMoreStep<FlowQueuesRecord> insert = dsl
            .insertInto(FLOW_QUEUES)
            .set(first.buildInsertMap());
        for (int index = 1; index < entries.size(); index++) {
            insert = insert.newRecord()
                .set(entries.get(index).buildInsertMap());
        }
        insert.execute();
    }

    private DeliveryAttempt deliverOne(
        DSLContext dsl,
        Consumer<T> consumer,
        BooleanSupplier deliveryAllowed
    ) {
        FlowQueuesRecord record = dsl
            .selectFrom(FLOW_QUEUES)
            .where(FLOW_QUEUES.QUEUE_TYPE.eq(DISPATCH_QUEUE_TYPE))
            .and(FLOW_QUEUES.QUEUE_NAME.eq(queueName))
            .orderBy(
                FLOW_QUEUES.CREATED_AT.asc(),
                FLOW_QUEUES.ID.asc()
            )
            .limit(1)
            .forUpdate()
            .skipLocked()
            .fetchOne();
        if (record == null || !deliveryAllowed.getAsBoolean()) {
            return DeliveryAttempt.empty();
        }

        QueueMessageEntry entry = QueueMessageEntry.fromRecord(record);
        T event;
        try {
            event = entry.toEvent(eventType);
            if (event == null) {
                throw new QueueException(
                    "Persisted Queue payload could not be restored"
                );
            }
        } catch (QueueException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new QueueException(
                "Event could not be decoded from Default Queue: "
                    + queueName,
                exception
            );
        }

        RuntimeException deliveryFailure = null;
        try {
            consumer.accept(event);
        } catch (RuntimeException exception) {
            deliveryFailure = exception;
        }

        int deleted = dsl.deleteFrom(FLOW_QUEUES)
            .where(FLOW_QUEUES.ID.eq(entry.getId()))
            .and(FLOW_QUEUES.QUEUE_TYPE.eq(DISPATCH_QUEUE_TYPE))
            .and(FLOW_QUEUES.QUEUE_NAME.eq(queueName))
            .execute();
        if (deleted != 1) {
            throw new QueueException(
                "Locked Default Queue message was not deleted: "
                    + entry.getId()
            );
        }
        return DeliveryAttempt.delivered(
            entry.getId(),
            entry.getEventKey(),
            deliveryFailure
        );
    }

    private QueueException databaseFailure(
        String operation,
        RuntimeException cause
    ) {
        return new QueueException(
            "Could not " + operation + " Default Queue: " + queueName,
            cause
        );
    }

    record DeliveryAttempt(
        boolean delivered,
        String messageId,
        String eventKey,
        RuntimeException failure
    ) {

        static DeliveryAttempt empty() {
            return new DeliveryAttempt(false, null, null, null);
        }

        static DeliveryAttempt delivered(
            String messageId,
            String eventKey,
            RuntimeException failure
        ) {
            return new DeliveryAttempt(
                true,
                messageId,
                eventKey,
                failure
            );
        }
    }
}
