package org.flow.core.trace;

import org.flow.core.models.executions.Execution;
import org.flow.core.runners.RunContext;

import io.opentelemetry.api.common.Attributes;

class NoopTracer implements Tracer {
    @Override
    public <V> V inCurrentContext(RunContext runContext, String spanName, Callable<V> callable) {
        return callable.call();
    }

    @Override
    public <V> V inCurrentContext(RunContext runContext, String spanName, Attributes additionalAttributes, Callable<V> callable) {
        return callable.call();
    }

    @Override
    public <V> V inCurrentContext(Execution execution, String spanName, Callable<V> callable) {
        return callable.call();
    }

    @Override
    public <V> V inCurrentContext(Execution execution, String spanName, Attributes additionalAttributes, Callable<V> callable) {
        return callable.call();
    }

    @Override
    public <V> V inNewContext(Execution execution, String spanName, Callable<V> callable) {
        return callable.call();
    }

    @Override
    public <V> V inNewContext(Execution execution, String spanName, Attributes additionalAttributes, Callable<V> callable) {
        return callable.call();
    }
}
