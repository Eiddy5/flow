package org.cses.flow.core.domains.conditions;

public enum Logical {
    AND("&&"),
    OR("||");

    private final String symbol;

    Logical(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
