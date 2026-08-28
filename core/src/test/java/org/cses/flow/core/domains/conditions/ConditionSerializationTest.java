package org.cses.flow.core.domains.conditions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConditionSerializationTest {

    @Test
    void roundTripsAsAConditionStringInsteadOfAnInternalTree() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Condition condition = Condition.parser(
            "{{ inputs.a }} == 1 || {{ inputs.enabled }} == true"
        );

        String json = mapper.writeValueAsString(condition);
        Condition restored = mapper.readValue(json, Condition.class);

        assertEquals(
            "\"{{ inputs.a }} == 1 || {{ inputs.enabled }} == true\"",
            json
        );
        assertEquals(condition, restored);
        assertEquals(condition.source(), restored.source());
    }
}
