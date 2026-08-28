package org.cses.flow.core.domains.conditions;

import org.cses.flow.core.domains.flows.DataType;

import java.math.BigDecimal;
import java.util.Arrays;

public enum BasicComparison implements Comparison {
    EQUALS("==", false),
    NOT_EQUALS("!=", false),
    GREATER_THAN(">", true),
    GREATER_THAN_OR_EQUALS(">=", true),
    LESS_THAN("<", true),
    LESS_THAN_OR_EQUALS("<=", true);

    private final String symbol;
    private final boolean ordered;

    BasicComparison(String symbol, boolean ordered) {
        this.symbol = symbol;
        this.ordered = ordered;
    }

    @Override
    public String symbol() {
        return symbol;
    }

    @Override
    public boolean supports(DataType type, Object constant) {
        if (type == null || constant == null) {
            return false;
        }
        if (ordered) {
            return isNumeric(type) && constant instanceof BigDecimal;
        }
        return switch (type) {
            case STRING -> constant instanceof String;
            case CHARACTER -> constant instanceof String text
                && text.length() == 1;
            case BOOLEAN -> constant instanceof Boolean;
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE ->
                constant instanceof BigDecimal;
        };
    }

    @Override
    public boolean matches(Object actual, Object constant) {
        Integer compared = compare(actual, constant);
        if (compared == null) {
            return false;
        }
        return switch (this) {
            case EQUALS -> compared == 0;
            case NOT_EQUALS -> compared != 0;
            case GREATER_THAN -> compared > 0;
            case GREATER_THAN_OR_EQUALS -> compared >= 0;
            case LESS_THAN -> compared < 0;
            case LESS_THAN_OR_EQUALS -> compared <= 0;
        };
    }

    static BasicComparison parse(String source) {
        return Arrays.stream(values())
            .filter(comparison -> comparison.symbol.equals(source))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unsupported Condition comparison: " + source
            ));
    }

    private Integer compare(Object actual, Object constant) {
        if (actual instanceof Number actualNumber
            && constant instanceof BigDecimal constantNumber) {
            try {
                return new BigDecimal(actualNumber.toString())
                    .compareTo(constantNumber);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        if (ordered) {
            return null;
        }
        if (actual instanceof String actualText
            && constant instanceof String constantText) {
            return actualText.compareTo(constantText);
        }
        if (actual instanceof Character actualCharacter
            && constant instanceof String constantText
            && constantText.length() == 1) {
            return actualCharacter.toString().compareTo(constantText);
        }
        if (actual instanceof Boolean actualBoolean
            && constant instanceof Boolean constantBoolean) {
            return actualBoolean.compareTo(constantBoolean);
        }
        return null;
    }

    private static boolean isNumeric(DataType type) {
        return switch (type) {
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE -> true;
            case STRING, CHARACTER, BOOLEAN -> false;
        };
    }
}
