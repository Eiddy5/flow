package org.cses.flow.infrastructure.queues.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.queues.event.DispatchEvent;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.json.SerializableObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

final class DispatchQueueMessageEntryTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void createsTechnicalIdentityAndSnapshotsJsonPayload() {
        TestEvent source = new TestEvent(
            null,
            "original",
            DSL.using(SQLDialect.POSTGRES)
        );

        DispatchQueueMessageEntry entry =
            DispatchQueueMessageEntry.create("", source);
        source.setValue("changed");

        assertFalse(entry.getId().isBlank());
        assertEquals("", entry.getQueueName());
        assertNull(entry.getEventKey());
        assertEquals("original", entry.payloadJson().getString("value"));
        assertFalse(entry.payloadJson().has("dsl"));

        JsonObject returned = entry.payloadJson();
        returned.put("value", "returned-change");
        assertEquals("original", entry.payloadJson().getString("value"));

        TestEvent restored = entry.toEvent(TestEvent.class);
        assertEquals("original", restored.getValue());
        assertNull(restored.dsl());
    }

    public static final class TestEvent extends SerializableObject
        implements DispatchEvent {

        private String key;
        private String value;
        private transient DSLContext dsl;

        public TestEvent() {
        }

        private TestEvent(String key, String value, DSLContext dsl) {
            this.key = key;
            this.value = value;
            this.dsl = dsl;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public DSLContext dsl() {
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
}
