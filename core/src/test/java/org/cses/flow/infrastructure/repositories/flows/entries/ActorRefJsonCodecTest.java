package org.cses.flow.infrastructure.repositories.flows.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.ActorRef;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActorRefJsonCodecTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void roundTripsActorRefThroughItsPersistedJsonShape() {
        ActorRef actor = ActorRef.create("actor-1", "Flow User");

        JSONB encoded = ActorRefJsonCodec.encode(actor);
        JsonObject stored = JsonObject.Parse(encoded.data());

        assertEquals(Set.of("id", "name"), stored.asMap().keySet());
        assertEquals("actor-1", stored.getString("id"));
        assertEquals("Flow User", stored.getString("name"));
        assertEquals(actor, ActorRefJsonCodec.decode(encoded, "creator"));
    }

    @Test
    void rejectsPersistedActorWithoutRequiredIdentity() {
        JSONB invalid = JSONB.valueOf("{\"name\":\"Flow User\"}");

        assertThrows(
            IllegalStateException.class,
            () -> ActorRefJsonCodec.decode(invalid, "creator")
        );
    }
}
