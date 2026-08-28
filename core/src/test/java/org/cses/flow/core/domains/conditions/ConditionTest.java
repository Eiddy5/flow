package org.cses.flow.core.domains.conditions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionTest {

    @Test
    void matchesAReferencedVariableAgainstAStringConstant() {
        Condition condition = Condition.parser(
            "{{ vars.level }} == A"
        );

        assertTrue(condition.matches(context(
            Map.of("vars", Map.of("level", "A"))
        )));
    }

    @Test
    void readsAllScopesAndNestedMapPaths() {
        Condition condition = Condition.parser("""
            {{ vars.environment }} == prod
            && {{ inputs.approved }} == true
            && {{ outputs.check.amount }} >= 100.50
            """);

        assertTrue(condition.matches(context(Map.of(
            "vars", Map.of("environment", "prod"),
            "inputs", Map.of("approved", true),
            "outputs", Map.of("check", Map.of("amount", 101L))
        ))));
        assertFalse(condition.matches(context(Map.of(
            "vars", Map.of("environment", "prod"),
            "inputs", Map.of("approved", true),
            "outputs", Map.of("check", Map.of("amount", 100.49D))
        ))));
    }

    @Test
    void comparesStringsBooleansCharactersAndNumbersBySupportedRules() {
        assertTrue(matches("{{ inputs.text }} != B", Map.of("text", "A")));
        assertFalse(matches("{{ inputs.text }} == A", Map.of("text", "a")));
        assertTrue(matches("{{ inputs.flag }} != false", Map.of("flag", true)));
        assertTrue(matches("{{ inputs.grade }} == A", Map.of("grade", 'A')));
        assertTrue(matches("{{ inputs.amount }} == 1.0", Map.of("amount", 1)));
        assertTrue(matches("{{ inputs.amount }} > -1.5", Map.of("amount", -1.49F)));
        assertTrue(matches("{{ inputs.amount }} <= 2", Map.of(
            "amount",
            new BigDecimal("2.00")
        )));
    }

    @Test
    void returnsFalseForMissingOrIncompatibleRuntimeValues() {
        assertFalse(matches("{{ inputs.value }} != A", Map.of()));
        assertFalse(Condition.parser("{{ inputs.value }} == A").matches(
            ConditionContext.from(Map.of())
        ));
        assertFalse(matches("{{ inputs.value }} == 1", Map.of("value", "1")));
        assertFalse(matches("{{ inputs.value }} > 1", Map.of("value", true)));
        assertFalse(Condition.parser("{{ outputs.task.value }} == 1").matches(
            context(Map.of("outputs", Map.of("task", "not-a-map")))
        ));

        Map<String, Object> inputsWithNull = new java.util.LinkedHashMap<>();
        inputsWithNull.put("value", null);
        assertFalse(matches("{{ inputs.value }} != A", inputsWithNull));
    }

    @Test
    void resolvesAnySafeRootAndTreatsAnAbsentRootAsMissing() {
        Condition condition = Condition.parser(
            "{{ futureRuntime.status }} == READY"
        );

        assertTrue(condition.matches(context(Map.of(
            "futureRuntime",
            Map.of("status", "READY")
        ))));
        assertFalse(condition.matches(context(Map.of())));
    }

    @Test
    void preservesAndOrDefinitionOrderAndShortCircuits() {
        AtomicInteger evaluations = new AtomicInteger();
        Comparison recordingComparison = new Comparison() {
            @Override
            public String symbol() {
                return "record";
            }

            @Override
            public boolean supports(
                org.cses.flow.core.domains.flows.DataType type,
                Object constant
            ) {
                return true;
            }

            @Override
            public boolean matches(Object actual, Object constant) {
                evaluations.incrementAndGet();
                return Boolean.TRUE.equals(constant);
            }
        };
        Condition trueCondition = Condition.compare(
            Operand.reference(List.of("inputs", "value")),
            recordingComparison,
            Operand.constant(true)
        );
        Condition falseCondition = Condition.compare(
            Operand.reference(List.of("inputs", "value")),
            recordingComparison,
            Operand.constant(false)
        );
        ConditionContext context = context(Map.of(
            "inputs", Map.of("value", true)
        ));

        assertFalse(Condition.combine(
            Logical.AND,
            List.of(falseCondition, trueCondition)
        ).matches(context));
        assertEquals(1, evaluations.get());

        evaluations.set(0);
        assertTrue(Condition.combine(
            Logical.OR,
            List.of(trueCondition, falseCondition)
        ).matches(context));
        assertEquals(1, evaluations.get());
    }

    @Test
    void oneConditionCanBeReadConcurrentlyWithoutSharingContextState() {
        Condition condition = Condition.parser("{{ inputs.value }} == 1");

        long matches = IntStream.range(0, 200)
            .parallel()
            .mapToObj(index -> condition.matches(context(Map.of(
                "inputs", Map.of("value", index % 2)
            ))))
            .filter(Boolean::booleanValue)
            .count();

        assertEquals(100, matches);
    }

    @Test
    void contextDefensivelyCopiesNestedMaps() {
        Map<String, Object> nested = new java.util.LinkedHashMap<>();
        nested.put("status", "READY");
        Map<String, Object> outputs = new java.util.LinkedHashMap<>();
        outputs.put("check", nested);
        ConditionContext context = context(Map.of("outputs", outputs));
        Condition condition = Condition.parser(
            "{{ outputs.check.status }} == READY"
        );

        nested.put("status", "CHANGED");
        outputs.clear();

        assertTrue(condition.matches(context));
    }

    private static boolean matches(
        String source,
        Map<String, ?> inputs
    ) {
        return Condition.parser(source).matches(context(Map.of(
            "inputs",
            inputs
        )));
    }

    private static ConditionContext context(Map<String, ?> variables) {
        return ConditionContext.from(variables);
    }
}
