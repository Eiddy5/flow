package org.cses.flow.core.domains.conditions;

import java.util.Arrays;

public enum OperandScope {
    VARIABLES("variables"),
    INPUTS("inputs"),
    OUTPUTS("outputs");

    private final String source;

    OperandScope(String source) {
        this.source = source;
    }

    public String source() {
        return source;
    }

    static OperandScope parse(String source) {
        return Arrays.stream(values())
            .filter(scope -> scope.source.equals(source))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unsupported Condition reference root: " + source
            ));
    }
}
