package org.cses.flow.infrastructure.queues;

import io.micronaut.json.JsonMapper;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.queues.QueueException;
import org.cses.flow.queues.event.DispatchEvent;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.json.SerializableObject;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.flow.gen.flow.Tables.DISPATCH_QUEUE_MESSAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(
    named = "FLOW_POSTGRES_TEST_URL",
    matches = ".+"
)
final class DefaultDispatchQueueTransactionIntegrationTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    private final PostgresJooqTestAdapter database =
        PostgresJooqTestAdapter.fromEnvironment();
    private final String queuePrefix =
        "default-queue-transaction-test-" + StringUtil.newId();
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
    void synchronousEventUsesItsDslContext() {
        String queueName = queuePrefix + "-event-dsl";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        assertThrows(RollbackSignal.class, () -> database.run(dsl -> {
            queue.emit(new TestEvent("rollback", "discard", dsl));
            throw new RollbackSignal();
        }));
        assertEquals(0, pending(queueName));

        database.run(dsl -> queue.emit(
            new TestEvent("commit", "retain", dsl)
        ));
        assertEquals(1, pending(queueName));
    }

    @Test
    void synchronousEventWithoutDslUsesQueueTransaction() {
        String queueName = queuePrefix + "-owned-transaction";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        assertThrows(RollbackSignal.class, () -> database.run(dsl -> {
            queue.emit(new TestEvent("owned", "retain", null));
            throw new RollbackSignal();
        }));

        assertEquals(1, pending(queueName));
    }

    @Test
    void asynchronousEventAlwaysUsesQueueTransaction() {
        String queueName = queuePrefix + "-async-transaction";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);
        AtomicInteger dslReads = new AtomicInteger();

        assertThrows(RollbackSignal.class, () -> database.run(dsl -> {
            queue.emitAsync(new TestEvent(
                "async",
                "retain",
                dsl,
                dslReads
            ))
                .toCompletableFuture()
                .join();
            throw new RollbackSignal();
        }));

        assertEquals(0, dslReads.get());
        assertEquals(1, pending(queueName));
    }

    @Test
    void synchronousBatchUsesOneSharedDslContext() {
        String queueName = queuePrefix + "-shared-batch";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        assertThrows(RollbackSignal.class, () -> database.run(dsl -> {
            queue.emit(List.of(
                new TestEvent("rollback-1", "discard", dsl),
                new TestEvent("rollback-2", "discard", dsl)
            ));
            throw new RollbackSignal();
        }));
        assertEquals(0, pending(queueName));

        database.run(dsl -> queue.emit(List.of(
            new TestEvent("commit-1", "retain", dsl),
            new TestEvent("commit-2", "retain", dsl)
        )));
        assertEquals(2, pending(queueName));
    }

    @Test
    void synchronousBatchRejectsMixedTransactionScopes() {
        String queueName = queuePrefix + "-mixed-batch";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        database.run(dsl -> assertThrows(QueueException.class, () ->
            queue.emit(List.of(
                new TestEvent("transactional", "discard", dsl),
                new TestEvent("owned", "discard", null)
            ))
        ));

        assertEquals(0, pending(queueName));
    }

    @Test
    void synchronousBatchRejectsDifferentDslContexts() {
        String queueName = queuePrefix + "-different-dsl-batch";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        database.run(firstDsl -> database.run(secondDsl ->
            assertThrows(QueueException.class, () -> queue.emit(List.of(
                new TestEvent("first", "discard", firstDsl),
                new TestEvent("second", "discard", secondDsl)
            )))
        ));

        assertEquals(0, pending(queueName));
    }

    @Test
    void persistedJsonObjectContainsOnlyBusinessPayload() {
        String queueName = queuePrefix + "-jsonb";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        database.run(dsl -> queue.emit(
            new TestEvent("json-key", "json-value", dsl)
        ));

        JsonObject payload = database.runReturn(dsl -> JsonObject.Parse(
            dsl.select(DISPATCH_QUEUE_MESSAGES.PAYLOAD)
                .from(DISPATCH_QUEUE_MESSAGES)
                .where(DISPATCH_QUEUE_MESSAGES.QUEUE_NAME.eq(queueName))
                .fetchSingle(DISPATCH_QUEUE_MESSAGES.PAYLOAD)
                .data()
        ));
        assertEquals("json-key", payload.getString("key"));
        assertEquals("json-value", payload.getString("value"));
        assertFalse(payload.has("dsl"));
        assertFalse(payload.has("dslContext"));
    }

    private DefaultDispatchQueue<TestEvent> queue(String queueName) {
        DefaultDispatchQueue<TestEvent> queue = new DefaultDispatchQueue<>(
            queueName,
            database,
            TestEvent.class,
            10L
        );
        queues.add(queue);
        return queue;
    }

    private int pending(String queueName) {
        return database.runReturn(dsl -> dsl.fetchCount(
            DISPATCH_QUEUE_MESSAGES,
            DISPATCH_QUEUE_MESSAGES.QUEUE_NAME.eq(queueName)
        ));
    }

    public static final class TestEvent extends SerializableObject
        implements DispatchEvent {

        private String key;
        private String value;
        private transient DSLContext dsl;
        private transient AtomicInteger dslReads;

        public TestEvent() {
        }

        TestEvent(String key, String value, DSLContext dsl) {
            this(key, value, dsl, null);
        }

        TestEvent(
            String key,
            String value,
            DSLContext dsl,
            AtomicInteger dslReads
        ) {
            this.key = key;
            this.value = value;
            this.dsl = dsl;
            this.dslReads = dslReads;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public DSLContext dsl() {
            if (dslReads != null) {
                dslReads.incrementAndGet();
            }
            return dsl;
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

    private static final class RollbackSignal extends RuntimeException {
    }
}
