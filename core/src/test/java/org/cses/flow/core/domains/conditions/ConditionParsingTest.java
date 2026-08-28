package org.cses.flow.core.domains.conditions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionParsingTest {

    @Test
    void distinguishesExplicitReferencesFromTypedAndLiteralConstants() {
        Condition text = Condition.parser(
            "{{ inputs.username }} == inputs.username"
        );

        assertEquals(
            Operand.reference(List.of("inputs", "username")),
            text.left().orElseThrow()
        );
        assertEquals(
            "inputs.username",
            text.right().orElseThrow().value()
        );
        assertEquals(
            "{{ inputs.username }} == inputs.username",
            text.source()
        );

        assertEquals(
            Boolean.TRUE,
            Condition.parser("{{ inputs.enabled }} == true")
                .right().orElseThrow().value()
        );
        assertEquals(
            new BigDecimal("18"),
            Condition.parser("{{ inputs.age }} >= 18")
                .right().orElseThrow().value()
        );
        assertEquals(
            "Alice Smith",
            Condition.parser("{{ inputs.displayName }} == Alice Smith")
                .right().orElseThrow().value()
        );
        assertEquals(
            "true",
            Condition.parser("{{ inputs.code }} == \"true\"")
                .right().orElseThrow().value()
        );
        assertEquals(
            "null",
            Condition.parser("{{ inputs.code }} == null")
                .right().orElseThrow().value()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Condition.parser("inputs.username == admin")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Condition.parser(
                "{{ inputs.username }} == {{ inputs.other }}"
            )
        );
    }

    @Test
    void parsesCompleteSafePathsWithoutRestrictingTheRoot() {
        Condition condition = Condition.parser("""
            {{ vars.release-channel }} == stable
            && {{ inputs.request_id }} != ""
            && {{ outputs.check-1.result_code }} == 200
            && {{ execution.id }} == execution-1
            """);

        assertEquals(
            List.of(
                Operand.reference(
                    List.of("vars", "release-channel")
                ),
                Operand.reference(
                    List.of("inputs", "request_id")
                ),
                Operand.reference(
                    List.of("outputs", "check-1", "result_code")
                ),
                Operand.reference(
                    List.of("execution", "id")
                )
            ),
            condition.references()
        );
    }

    @Test
    void parsesTypedConstantsAndAllBasicComparisons() {
        List<String> operators = List.of("==", "!=", ">", ">=", "<", "<=");
        List<BasicComparison> comparisons = List.of(
            BasicComparison.EQUALS,
            BasicComparison.NOT_EQUALS,
            BasicComparison.GREATER_THAN,
            BasicComparison.GREATER_THAN_OR_EQUALS,
            BasicComparison.LESS_THAN,
            BasicComparison.LESS_THAN_OR_EQUALS
        );

        for (int index = 0; index < operators.size(); index++) {
            Condition parsed = Condition.parser(
                "{{ inputs.amount }} " + operators.get(index) + " -100.50"
            );
            assertEquals(comparisons.get(index), parsed.comparison().orElseThrow());
            assertEquals(
                new BigDecimal("-100.50"),
                parsed.right().orElseThrow().value()
            );
        }

        assertEquals(
            "line\n\"quoted\"\\tail",
            Condition.parser(
                "{{ inputs.text }} == \"line\\n\\\"quoted\\\"\\\\tail\""
            ).right().orElseThrow().value()
        );
        assertEquals(
            Boolean.TRUE,
            Condition.parser("{{ inputs.flag }} == true")
                .right().orElseThrow().value()
        );
    }

    @Test
    void preservesConstantMeaningWhenCanonicalSourceIsParsedAgain() {
        List<String> values = List.of(
            "true",
            "18",
            "A && B",
            "contains(parentheses)",
            " leading",
            "quoted \"text\"",
            "line\nbreak",
            "{{ inputs.other }}"
        );

        for (String value : values) {
            Condition condition = Condition.compare(
                Operand.reference(List.of("inputs", "value")),
                BasicComparison.EQUALS,
                Operand.constant(value)
            );

            assertEquals(
                value,
                Condition.parser(condition.source())
                    .right().orElseThrow().value(),
                value
            );
        }
    }

    @Test
    void appliesAndBeforeOrAndLetsParenthesesOverridePrecedence() {
        Condition defaultPrecedence = Condition.parser("""
            {{ inputs.a }} == 1
            || {{ inputs.b }} == 2 && {{ inputs.c }} == 3
            """);

        assertEquals(Logical.OR, defaultPrecedence.operator().orElseThrow());
        assertEquals(2, defaultPrecedence.conditions().size());
        assertEquals(
            Logical.AND,
            defaultPrecedence.conditions().get(1).operator().orElseThrow()
        );

        Condition grouped = Condition.parser("""
            ({{ inputs.a }} == 1 || {{ inputs.b }} == 2)
            && {{ inputs.c }} == 3
            """);

        assertEquals(Logical.AND, grouped.operator().orElseThrow());
        assertEquals(
            Logical.OR,
            grouped.conditions().getFirst().operator().orElseThrow()
        );

        Condition nested = Condition.parser("""
            (({{ inputs.a }} == 1 || {{ inputs.b }} == 2)
            && {{ inputs.c }} == 3) || {{ inputs.d }} == 4
            """);
        assertEquals(Logical.OR, nested.operator().orElseThrow());
        assertEquals(
            Logical.AND,
            nested.conditions().getFirst().operator().orElseThrow()
        );
    }

    @Test
    void flattensAdjacentEqualLogicalNodesAndKeepsOrder() {
        Condition condition = Condition.parser("""
            {{ inputs.a }} == 1
            && ({{ inputs.b }} == 2 && {{ inputs.c }} == 3)
            """);

        assertEquals(Logical.AND, condition.operator().orElseThrow());
        assertEquals(
            List.of("a", "b", "c"),
            condition.conditions().stream()
                .map(child -> child.left().orElseThrow().path().getLast())
                .toList()
        );
    }

    @Test
    void emitsOneStableCanonicalSource() {
        Condition condition = Condition.parser("""
              {{inputs.a}}==1
              ||{{inputs.b}} == 2 && {{ inputs.text }} == "x\\ny"
            """);

        assertEquals(
            "{{ inputs.a }} == 1 || ({{ inputs.b }} == 2 "
                + "&& {{ inputs.text }} == \"x\\ny\")",
            condition.source()
        );
        assertEquals(condition.source(), condition.toString());
    }

    @Test
    void rejectsUnsafeOrUnsupportedSyntaxWithPosition() {
        List<String> invalid = List.of(
            "",
            "{{ outputs.status }} = DONE",
            "outputs.status == DONE",
            "{{ outputs.1status }} == DONE",
            "{{ outputs.a\u0661 }} == 1",
            "({{ outputs.status }} == DONE",
            "{{ outputs.status }} == DONE)",
            "{{ outputs.amount }} + 1 > 10",
            "Runtime.exec(\"command\") == \"x\"",
            "{{ outputs.items[0] }} == A",
            "{{ outputs.value }} == {{ inputs.other }}",
            "{{ outputs.value }} == value ||",
            "{{ outputs.value }} == \"bad\\qescape\"",
            "{{ inputs.text }} == \"line\nbreak\"",
            "{ inputs.value } == A",
            "{{ inputs.value } == A",
            "{{ inputs.value }} =="
        );

        for (String source : invalid) {
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Condition.parser(source),
                source
            );
            if (!source.isBlank()) {
                assertTrue(
                    exception.getMessage().contains("position"),
                    exception::getMessage
                );
            }
        }
    }

    @Test
    void rejectsSourcesBeyondLengthDepthAndNodeLimits() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Condition.parser("x".repeat(4097))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Condition.parser(
                " ".repeat(4090) + "{{ inputs.a }} == 1"
            )
        );

        String deeplyNested = "(".repeat(33)
            + "{{ inputs.a }} == 1"
            + ")".repeat(33);
        assertThrows(
            IllegalArgumentException.class,
            () -> Condition.parser(deeplyNested)
        );
    }
}
