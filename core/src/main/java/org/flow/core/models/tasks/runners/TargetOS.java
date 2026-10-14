package org.flow.core.models.tasks.runners;

public enum TargetOS {
    LINUX("\n"),
    WINDOWS("\r\n"),
    AUTO(System.lineSeparator());

    public final String lineSeparator;

    TargetOS(String lineSeparator) {
        this.lineSeparator = lineSeparator;
    }
}
