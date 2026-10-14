package org.flow.core.trace.propagation;

import org.flow.core.models.executions.Execution;

import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.annotation.Nullable;

public class ExecutionTextMapSetter implements TextMapSetter<Execution> {
    public static final ExecutionTextMapSetter INSTANCE = new ExecutionTextMapSetter();

    @Override
    public void set(@Nullable Execution carrier, String key, String value) {
        if (carrier != null) {
            switch (key) {
                case "traceparent" -> carrier.setTraceParent(value);
                default -> {
                }
            }
        }
    }
}
