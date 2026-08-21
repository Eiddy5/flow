package org.cses.flow.infrastructure.queues;

import io.micronaut.json.JsonMapper;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.queues.event.DispatchEvent;
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

import static org.flow.gen.flow.Tables.QUEUES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(
    named = "FLOW_POSTGRES_TEST_URL",
    matches = ".+"
)
final class DefaultDispatchQueueTransactionIntegrationTest {

    private static final String DISPATCH_QUEUE_TYPE = "DISPATCH";

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
        database.run(dsl -> dsl.deleteFrom(QUEUES)
            .where(QUEUES.QUEUE_TYPE.eq(DISPATCH_QUEUE_TYPE))
            .and(QUEUES.QUEUE_NAME.startsWith(queuePrefix))
            .execute());
    }

    @Test
    void synchronousEventUsesExplicitTransaction() {
        String queueName = queuePrefix + "-explicit-dsl";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        assertThrows(RollbackSignal.class, () -> database.run(dsl -> {
            queue.emitInTransaction(
                new TestEvent("rollback", "discard"),
                dsl
            );
            throw new RollbackSignal();
        }));
        assertEquals(0, pending(queueName));

        database.run(dsl -> queue.emitInTransaction(
            new TestEvent("commit", "retain"),
            dsl
        ));
        assertEquals(1, pending(queueName));
    }

    @Test
    void ordinarySynchronousEventUsesQueueTransaction() {
        String queueName = queuePrefix + "-owned-transaction";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        assertThrows(RollbackSignal.class, () -> database.run(dsl -> {
            queue.emit(new TestEvent("owned", "retain"));
            throw new RollbackSignal();
        }));

        assertEquals(1, pending(queueName));
    }

    @Test
    void asynchronousEventAlwaysUsesQueueTransaction() {
        String queueName = queuePrefix + "-async-transaction";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        assertThrows(RollbackSignal.class, () -> database.run(dsl -> {
            queue.emitAsync(new TestEvent("async", "retain"))
                .toCompletableFuture()
                .join();
            throw new RollbackSignal();
        }));

        assertEquals(1, pending(queueName));
    }

    @Test
    void synchronousBatchUsesExplicitTransaction() {
        String queueName = queuePrefix + "-shared-batch";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        assertThrows(RollbackSignal.class, () -> database.run(dsl -> {
            queue.emitInTransaction(
                List.of(
                    new TestEvent("rollback-1", "discard"),
                    new TestEvent("rollback-2", "discard")
                ),
                dsl
            );
            throw new RollbackSignal();
        }));
        assertEquals(0, pending(queueName));

        database.run(dsl -> queue.emitInTransaction(
            List.of(
                new TestEvent("commit-1", "retain"),
                new TestEvent("commit-2", "retain")
            ),
            dsl
        ));
        assertEquals(2, pending(queueName));
    }

    @Test
    void ordinarySynchronousBatchUsesQueueTransaction() {
        String queueName = queuePrefix + "-owned-batch";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        assertThrows(RollbackSignal.class, () -> database.run(dsl -> {
            queue.emit(List.of(
                new TestEvent("owned-1", "retain"),
                new TestEvent("owned-2", "retain")
            ));
            throw new RollbackSignal();
        }));

        assertEquals(2, pending(queueName));
    }

    @Test
    void persistedJsonObjectContainsOnlyBusinessPayload() {
        String queueName = queuePrefix + "-jsonb";
        DefaultDispatchQueue<TestEvent> queue = queue(queueName);

        queue.emit(new TestEvent("json-key", "json-value"));

        JsonObject payload = database.runReturn(dsl -> JsonObject.Parse(
            dsl.select(QUEUES.PAYLOAD)
                .from(QUEUES)
                .where(QUEUES.QUEUE_TYPE.eq(DISPATCH_QUEUE_TYPE))
                .and(QUEUES.QUEUE_NAME.eq(queueName))
                .fetchSingle(QUEUES.PAYLOAD)
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
            QUEUES,
            QUEUES.QUEUE_TYPE.eq(DISPATCH_QUEUE_TYPE)
                .and(QUEUES.QUEUE_NAME.eq(queueName))
        ));
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
