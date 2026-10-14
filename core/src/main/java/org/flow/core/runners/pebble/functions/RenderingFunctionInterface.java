package org.flow.core.runners.pebble.functions;

import org.flow.core.runners.VariableRenderer;

import io.micronaut.context.ApplicationContext;

public interface RenderingFunctionInterface {
    String functionName();

    default VariableRenderer variableRenderer(ApplicationContext applicationContext) {
        return applicationContext.getBean(VariableRenderer.class);
    }
}
