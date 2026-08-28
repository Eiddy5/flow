package org.cses.flow.core.domains.conditions;

import org.cses.flow.core.domains.flows.DataType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionStructureTest {

    @Test
    void onlyAllowsCompleteComparisonOrLogicalShapes() {
        Operand reference = Operand.reference(
            List.of("inputs", "value")
        );
        Operand constant = Operand.constant(1);

        assertThrows(
            IllegalArgumentException.class,
            () -> Condition.compare(
                constant,
                BasicComparison.EQUALS,
                constant
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Condition.compare(
                reference,
                BasicComparison.EQUALS,
                reference
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Condition.compare(reference, null, constant)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Condition.combine(Logical.AND, List.of(
                Condition.compare(reference, BasicComparison.EQUALS, constant)
            ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Operand.reference(
                List.of("inputs", "invalid.path")
            )
        );
    }

    @Test
    void protectsAllCollectionsFromCallerMutation() {
        List<String> path = new ArrayList<>(
            List.of("outputs", "task", "value")
        );
        Operand reference = Operand.reference(path);
        path.add("changed");
        assertEquals(
            List.of("outputs", "task", "value"),
            reference.path()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> reference.path().add("changed")
        );

        List<Condition> children = new ArrayList<>(List.of(
            Condition.parser("{{ inputs.a }} == 1"),
            Condition.parser("{{ inputs.b }} == 2")
        ));
        Condition condition = Condition.combine(Logical.AND, children);
        children.clear();

        assertEquals(2, condition.conditions().size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> condition.conditions().clear()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> condition.references().clear()
        );
    }

    @Test
    void programmaticFactoriesEnforceTreeSafetyLimits() {
        Operand reference = Operand.reference(
            List.of("inputs", "a")
        );
        Comparison compactComparison = new Comparison() {
            @Override
            public String symbol() {
                return "x";
            }

            @Override
            public boolean supports(DataType type, Object constant) {
                return true;
            }

            @Override
            public boolean matches(Object actual, Object constant) {
                return false;
            }
        };
        List<Condition> tooMany = java.util.stream.IntStream.range(0, 256)
            .mapToObj(index -> Condition.compare(
                reference,
                compactComparison,
                Operand.constant(1)
            ))
            .toList();
        IllegalArgumentException nodeFailure = assertThrows(
            IllegalArgumentException.class,
            () -> Condition.combine(Logical.OR, tooMany)
        );
        assertTrue(nodeFailure.getMessage().contains("maximum nodes"));

        Condition nested = Condition.parser("{{ inputs.a }} == 1");
        for (int depth = 0; depth < 32; depth++) {
            Logical operator = depth % 2 == 0 ? Logical.AND : Logical.OR;
            nested = Condition.combine(operator, List.of(
                nested,
                Condition.parser("{{ inputs.b }} == 2")
            ));
        }
        Condition maximumDepth = nested;
        IllegalArgumentException depthFailure = assertThrows(
            IllegalArgumentException.class,
            () -> Condition.combine(Logical.AND, List.of(
                maximumDepth,
                Condition.parser("{{ inputs.c }} == 3")
            ))
        );
        assertTrue(depthFailure.getMessage().contains("maximum depth"));
    }

    @Test
    void hasValueSemanticsIndependentOfInputWhitespace() {
        Condition first = Condition.parser(
            "{{ inputs.a }} == 1 && {{ inputs.b }} == true"
        );
        Condition second = Condition.parser(
            " {{inputs.a}}==1&&{{inputs.b}}==true "
        );

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void reportsComparisonCompatibilityAgainstDeclaredDataTypes() {
        Operand amount = Operand.reference(
            List.of("inputs", "amount")
        );
        Condition numeric = Condition.parser("{{ inputs.amount }} >= 1");
        Condition character = Condition.parser("{{ inputs.grade }} == A");
        Condition invalidCharacter = Condition.parser(
            "{{ inputs.grade }} == AB"
        );

        assertTrue(numeric.supports(amount, DataType.INTEGER));
        assertFalse(numeric.supports(amount, DataType.STRING));
        assertTrue(character.supports(
            character.references().getFirst(),
            DataType.CHARACTER
        ));
        assertFalse(invalidCharacter.supports(
            invalidCharacter.references().getFirst(),
            DataType.CHARACTER
        ));
    }

    @Test
    void containsNoTechnicalIdentityRepositoryOrRuntimeState() {
        Set<String> fields = java.util.Arrays.stream(
                Condition.class.getDeclaredFields()
            )
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(java.lang.reflect.Field::getName)
            .collect(java.util.stream.Collectors.toSet());

        assertEquals(
            Set.of(
                "source",
                "operator",
                "left",
                "comparison",
                "right",
                "conditions"
            ),
            fields
        );
        Set<String> operandFields = java.util.Arrays.stream(
                Operand.class.getDeclaredFields()
            )
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(java.lang.reflect.Field::getName)
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("path", "value"), operandFields);
        assertTrue(java.util.Arrays.stream(Operand.class.getMethods())
            .noneMatch(method -> method.getName().equals("kind")));
        assertTrue(java.util.Arrays.stream(Condition.class.getMethods())
            .noneMatch(method -> Set.of(
                "id",
                "repository",
                "execution",
                "taskRun"
            ).contains(method.getName())));
        assertTrue(Modifier.isFinal(Condition.class.getModifiers()));
        assertFalse(Modifier.isFinal(Operand.class.getModifiers()));
        assertFalse(Modifier.isFinal(ConditionContext.class.getModifiers()));
        assertTrue(java.util.Arrays.stream(Condition.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .allMatch(field -> Modifier.isFinal(field.getModifiers())));
        for (Class<?> type : List.of(Operand.class, ConditionContext.class)) {
            assertTrue(java.util.Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .noneMatch(field -> Modifier.isFinal(field.getModifiers())));
        }
    }
}
