package org.cses.flow.core.domains.conditions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Package-private recursive-descent parser for Condition definitions.
 */
class ConditionParser {

    private String source;
    private int position;
    private int nodes;

    private ConditionParser(String source) {
        this.source = source;
    }

    static Condition parser(String source) {
        if (source == null) {
            throw new IllegalArgumentException(
                "Condition source must not be blank"
            );
        }
        if (source.length() > Condition.MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException(
                "Condition source exceeds maximum length: "
                    + Condition.MAX_SOURCE_LENGTH
            );
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException(
                "Condition source must not be blank"
            );
        }
        return new ConditionParser(source.trim()).parseCondition();
    }

    private Condition parseCondition() {
        Condition condition = parseOr(0);
        skipWhitespace();
        if (!atEnd()) {
            throw error("Unexpected token");
        }
        return condition;
    }

    private Condition parseOr(int depth) {
        List<Condition> children = new ArrayList<>();
        children.add(parseAnd(depth));
        while (consume("||")) {
            children.add(parseAnd(depth));
        }
        return children.size() == 1
            ? children.getFirst()
            : logical(Logical.OR, children);
    }

    private Condition parseAnd(int depth) {
        List<Condition> children = new ArrayList<>();
        children.add(parsePrimary(depth));
        while (consume("&&")) {
            children.add(parsePrimary(depth));
        }
        return children.size() == 1
            ? children.getFirst()
            : logical(Logical.AND, children);
    }

    private Condition parsePrimary(int depth) {
        skipWhitespace();
        if (consume("(")) {
            if (depth >= Condition.MAX_DEPTH) {
                throw error(
                    "Condition nesting exceeds maximum depth: "
                        + Condition.MAX_DEPTH
                );
            }
            Condition nested = parseOr(depth + 1);
            if (!consume(")")) {
                throw error("Expected closing parenthesis");
            }
            return nested;
        }
        return comparison();
    }

    private Condition comparison() {
        Operand left = reference();
        skipWhitespace();
        Comparison comparison = comparisonOperator();
        skipWhitespace();
        Operand right = Operand.constant(constant());
        countNode();
        return Condition.compare(left, comparison, right);
    }

    private Condition logical(
        Logical operator,
        List<Condition> children
    ) {
        countNode();
        return Condition.combine(operator, children);
    }

    private Operand reference() {
        skipWhitespace();
        if (!consumeRaw("{{")) {
            throw error(
                "Condition reference must start with '{{'"
            );
        }
        skipWhitespace();
        List<String> path = new ArrayList<>();
        path.add(parseIdentifier("Condition reference path"));
        while (consumeRaw(".")) {
            path.add(parseIdentifier("Condition reference path"));
        }
        skipWhitespace();
        if (!consumeRaw("}}")) {
            throw error("Condition reference must end with '}}'");
        }
        return Operand.reference(path);
    }

    private Comparison comparisonOperator() {
        for (String candidate : List.of(">=", "<=", "==", "!=", ">", "<")) {
            if (consumeRaw(candidate)) {
                return BasicComparison.parse(candidate);
            }
        }
        throw error("Expected a supported comparison operator");
    }

    private Object constant() {
        if (atEnd()) {
            throw error("Expected a constant value");
        }
        if (current() == '"') {
            return stringConstant();
        }
        if (startsWith("{{")) {
            throw error(
                "Condition right operand must be a constant"
            );
        }
        int start = position;
        while (!atEnd() && !atConstantBoundary()) {
            position++;
        }
        String value = source.substring(start, position).trim();
        if (value.isEmpty()) {
            throw error("Expected a constant value");
        }
        if (value.equals("true")) {
            return Boolean.TRUE;
        }
        if (value.equals("false")) {
            return Boolean.FALSE;
        }
        if (Operand.isDecimalSource(value)) {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException exception) {
                throw error("Invalid Number constant");
            }
        }
        return value;
    }

    private String stringConstant() {
        position++;
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        while (!atEnd()) {
            char current = source.charAt(position++);
            if (!escaped && current == '"') {
                return value.toString();
            }
            if (!escaped && current == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                value.append(switch (current) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    default -> throw error(
                        "Unsupported string escape: \\" + current
                    );
                });
                escaped = false;
                continue;
            }
            if (current < 0x20) {
                throw error(
                    "String constant contains an unescaped control character"
                );
            }
            value.append(current);
        }
        throw error("Unterminated string constant");
    }

    private String parseIdentifier(String description) {
        if (atEnd() || !isAsciiLetter(current())) {
            throw error(description + " must start with an ASCII letter");
        }
        int start = position++;
        while (!atEnd() && isIdentifierPart(current())) {
            position++;
        }
        return source.substring(start, position);
    }

    private boolean consume(String expected) {
        skipWhitespace();
        return consumeRaw(expected);
    }

    private boolean consumeRaw(String expected) {
        if (!startsWith(expected)) {
            return false;
        }
        position += expected.length();
        return true;
    }

    private boolean startsWith(String expected) {
        return source.startsWith(expected, position);
    }

    private boolean atConstantBoundary() {
        return startsWith("&&")
            || startsWith("||")
            || current() == '('
            || current() == ')';
    }

    private void skipWhitespace() {
        while (!atEnd() && Character.isWhitespace(current())) {
            position++;
        }
    }

    private void countNode() {
        nodes++;
        if (nodes > Condition.MAX_NODES) {
            throw error(
                "Condition tree exceeds maximum nodes: "
                    + Condition.MAX_NODES
            );
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(
            "Invalid Condition at position " + position + ": " + message
        );
    }

    private boolean atEnd() {
        return position >= source.length();
    }

    private char current() {
        return source.charAt(position);
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'A' && value <= 'Z'
            || value >= 'a' && value <= 'z';
    }

    private static boolean isIdentifierPart(char value) {
        return isAsciiLetter(value)
            || value >= '0' && value <= '9'
            || value == '_'
            || value == '-';
    }

}
