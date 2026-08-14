package org.cses.flow.core.domains.expressions;

import org.cses.flow.core.domains.flows.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressTest {

    @Test
    void parsesAndMatchesOneOutputPath() {
        Express expression = Express.parse(
            " outputs.decision == \"APPROVED\" "
        );

        assertEquals(
            "outputs.decision == \"APPROVED\"",
            expression.source()
        );
        assertEquals(List.of("decision"), expression.outputPath());
        assertTrue(expression.matches(Map.of("decision", "APPROVED")));
        assertFalse(expression.matches(Map.of("decision", "approved")));
    }

    @Test
    void traversesNestedLoopIterationOutputsSafely() {
        Express expression = Express.parse(
            "outputs.check.status == \"DONE\""
        );

        assertEquals(List.of("check", "status"), expression.outputPath());
        assertTrue(expression.matches(Map.of(
            "check",
            Map.of("status", "DONE")
        )));
        assertFalse(expression.matches(Map.of(
            "check",
            Map.of("status", "WAIT")
        )));
        assertFalse(expression.matches(Map.of("check", "DONE")));
        assertFalse(expression.matches(Map.of()));
        assertFalse(expression.matches(null));
    }

    @Test
    void preservesImmutableDefinitionValueSemantics() {
        Express first = Express.parse(
            "outputs.check.status == \"DONE\""
        );
        Express restored = Express.parse(
            "outputs.check.status == \"DONE\""
        );

        assertEquals(first, restored);
        assertEquals(first.hashCode(), restored.hashCode());
        assertEquals(first.source(), first.toString());
        assertThrows(
            UnsupportedOperationException.class,
            () -> first.outputPath().add("other")
        );
    }

    @Test
    void evaluatesConcurrentOutputContextsWithoutSharingRuntimeState() {
        Express expression = Express.parse(
            "outputs.status == \"DONE\""
        );

        long matches = IntStream.range(0, 200)
            .parallel()
            .mapToObj(index -> expression.matches(Map.of(
                "status",
                index % 2 == 0 ? "DONE" : "WAIT"
            )))
            .filter(Boolean::booleanValue)
            .count();

        assertEquals(100, matches);
    }

    @Test
    void comparesConfirmedInputsWithoutAcceptingExecutableCode() {
        Express greater = Express.parse("inputs.amount > 1000");
        Express lowerOrEqual = Express.parse("inputs.amount <= 1000");
        Express booleanEquals = Express.parse("inputs.urgent == true");

        assertTrue(greater.referencesInputs());
        assertEquals("amount", greater.referencedKey());
        assertTrue(greater.matches(Map.of(), Map.of("amount", 1000.01)));
        assertFalse(greater.matches(Map.of(), Map.of("amount", 1000)));
        assertTrue(lowerOrEqual.matches(Map.of(), Map.of("amount", 1000L)));
        assertTrue(booleanEquals.matches(Map.of(), Map.of("urgent", true)));
        assertThrows(
            IllegalArgumentException.class,
            () -> Express.parse("inputs.amount > Runtime.exec(\"bad\")")
        );
    }

    @Test
    void comparesFlowLevelVariables() {
        Express expression = Express.parse(
            "variables.environment == \"prod\""
        );

        assertTrue(expression.referencesVariables());
        assertEquals("environment", expression.referencedKey());
        assertTrue(expression.matches(
            Map.of(),
            Map.of(),
            Map.of("environment", "prod")
        ));
        assertFalse(expression.matches(
            Map.of(),
            Map.of(),
            Map.of("environment", "staging")
        ));
    }

    @Test
    void requiresOneCharacterLiteralForCharacterInputs() {
        assertTrue(Express.parse("inputs.grade == \"A\"")
            .supports(DataType.CHARACTER));
        assertFalse(Express.parse("inputs.grade == \"AB\"")
            .supports(DataType.CHARACTER));
    }
}
