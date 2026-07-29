package org.cses.flow.core.domains.flows;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataTest {

    @Test
    void dataContractExposesOnlyKeyAndType() {
        Set<String> methods = Arrays.stream(Data.class.getDeclaredMethods())
            .map(method -> method.getName())
            .collect(Collectors.toSet());

        assertEquals(Set.of("getKey", "getType"), methods);
    }

    @Test
    void inputAndOutputImplementTheNormalizedDataContract() {
        Data input = Input.create(" request ", " JSON ");
        Data output = Output.create(" result ", " STRING ");

        assertEquals("request", input.getKey());
        assertEquals("JSON", input.getType());
        assertEquals("result", output.getKey());
        assertEquals("STRING", output.getType());
        assertEquals(
            Input.create("request", "JSON"),
            Input.rehydrate("request", "JSON")
        );
        assertEquals(
            Output.create("result", "STRING"),
            Output.rehydrate("result", "STRING")
        );
    }

    @Test
    void inputAndOutputRejectBlankKeyOrType() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Input.create(" ", "STRING")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Input.create("request", " ")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Output.create(" ", "STRING")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Output.create("result", " ")
        );
    }
}
