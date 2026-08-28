package org.cses.flow.core.domains.conditions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.cses.flow.core.domains.flows.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Condition {

    static final int MAX_SOURCE_LENGTH = 4096;
    static final int MAX_DEPTH = 32;
    static final int MAX_NODES = 256;

    private final String source;
    private final Logical operator;
    private final Operand left;
    private final Comparison comparison;
    private final Operand right;
    private final List<Condition> conditions;

    private Condition(
        String source,
        Logical operator,
        Operand left,
        Comparison comparison,
        Operand right,
        List<Condition> conditions
    ) {
        this.source = source;
        this.operator = operator;
        this.left = left;
        this.comparison = comparison;
        this.right = right;
        this.conditions = conditions == null
            ? List.of()
            : List.copyOf(conditions);
        verifyShape();
        verifyLimits();
    }

    @JsonCreator
    public static Condition parser(String source) {
        return ConditionParser.parser(source);
    }

    public static Condition compare(
        Operand left,
        Comparison comparison,
        Operand right
    ) {
        String source = left == null || comparison == null || right == null
            ? ""
            : left.source() + " " + comparison.symbol() + " "
                + right.source();
        return new Condition(
            source,
            null,
            left,
            comparison,
            right,
            List.of()
        );
    }

    public static Condition combine(
        Logical operator,
        List<Condition> conditions
    ) {
        List<Condition> normalized = normalizeChildren(operator, conditions);
        String source = String.join(
                " " + operator.symbol() + " ",
            normalized.stream().map(Condition::groupedSource).toList()
        );
        return new Condition(
            source,
            operator,
            null,
            null,
            null,
            normalized
        );
    }

    @JsonValue
    public String source() {
        return source;
    }

    public Optional<Logical> operator() {
        return Optional.ofNullable(operator);
    }

    public Optional<Operand> left() {
        return Optional.ofNullable(left);
    }

    public Optional<Comparison> comparison() {
        return Optional.ofNullable(comparison);
    }

    public Optional<Operand> right() {
        return Optional.ofNullable(right);
    }

    public List<Condition> conditions() {
        return List.copyOf(conditions);
    }

    public List<Operand> references() {
        if (operator == null) {
            return List.of(left);
        }
        return conditions.stream()
            .flatMap(condition -> condition.references().stream())
            .toList();
    }

    public boolean supports(Operand reference, DataType type) {
        Objects.requireNonNull(reference, "Condition reference");
        Objects.requireNonNull(type, "Condition reference type");
        if (operator == null) {
            return !left.equals(reference)
                || comparison.supports(type, right.value());
        }
        return conditions.stream()
            .allMatch(condition -> condition.supports(reference, type));
    }

    public boolean matches(ConditionContext context) {
        Objects.requireNonNull(context, "Condition context");
        if (operator == Logical.AND) {
            for (Condition condition : conditions) {
                if (!condition.matches(context)) {
                    return false;
                }
            }
            return true;
        }
        if (operator == Logical.OR) {
            for (Condition condition : conditions) {
                if (condition.matches(context)) {
                    return true;
                }
            }
            return false;
        }
        return context.resolve(left)
            .map(actual -> comparison.matches(actual, right.value()))
            .orElse(false);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof Condition other)) {
            return false;
        }
        return operator == other.operator
            && Objects.equals(left, other.left)
            && Objects.equals(comparison, other.comparison)
            && Objects.equals(right, other.right)
            && Objects.equals(conditions, other.conditions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operator, left, comparison, right, conditions);
    }

    @Override
    public String toString() {
        return source;
    }

    private void verifyShape() {
        boolean comparisonNode = operator == null;
        if (comparisonNode) {
            if (left == null || comparison == null || right == null) {
                throw new IllegalArgumentException(
                    "Condition comparison requires left, comparison, and right"
                );
            }
            if (!left.isReference()) {
                throw new IllegalArgumentException(
                    "Condition left operand must be a reference"
                );
            }
            if (right.isReference()) {
                throw new IllegalArgumentException(
                    "Condition right operand must be a constant"
                );
            }
            if (!conditions.isEmpty()) {
                throw new IllegalArgumentException(
                    "Condition comparison must not contain child conditions"
                );
            }
            if (comparison.symbol() == null
                || comparison.symbol().isBlank()) {
                throw new IllegalArgumentException(
                    "Condition comparison symbol must not be blank"
                );
            }
            return;
        }
        if (left != null || comparison != null || right != null) {
            throw new IllegalArgumentException(
                "Condition logical node must not contain comparison operands"
            );
        }
        if (conditions.size() < 2) {
            throw new IllegalArgumentException(
                "Condition logical node requires at least two conditions"
            );
        }
        if (conditions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "Condition children must not contain null values"
            );
        }
    }

    private void verifyLimits() {
        if (nodeCount() > MAX_NODES) {
            throw new IllegalArgumentException(
                "Condition tree exceeds maximum nodes: " + MAX_NODES
            );
        }
        if (treeDepth() > MAX_DEPTH) {
            throw new IllegalArgumentException(
                "Condition nesting exceeds maximum depth: " + MAX_DEPTH
            );
        }
        if (source.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException(
                "Condition source exceeds maximum length: "
                    + MAX_SOURCE_LENGTH
            );
        }
    }

    private int nodeCount() {
        return 1 + conditions.stream()
            .mapToInt(Condition::nodeCount)
            .sum();
    }

    private int treeDepth() {
        return operator == null
            ? 0
            : 1 + conditions.stream()
                .mapToInt(Condition::treeDepth)
                .max()
                .orElse(0);
    }

    private String groupedSource() {
        return operator == null ? source : "(" + source + ")";
    }

    private static List<Condition> normalizeChildren(
        Logical operator,
        List<Condition> source
    ) {
        if (operator == null) {
            throw new IllegalArgumentException(
                "Condition logical operator must be provided"
            );
        }
        if (source == null) {
            throw new IllegalArgumentException(
                "Condition children must be provided"
            );
        }
        List<Condition> normalized = new ArrayList<>();
        for (Condition condition : source) {
            if (condition == null) {
                throw new IllegalArgumentException(
                    "Condition children must not contain null values"
                );
            }
            if (condition.operator == operator) {
                normalized.addAll(condition.conditions);
            } else {
                normalized.add(condition);
            }
        }
        return List.copyOf(normalized);
    }
}
