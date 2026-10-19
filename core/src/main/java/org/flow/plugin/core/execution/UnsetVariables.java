package org.flow.plugin.core.execution;

import java.util.List;
import java.util.Map;

import org.flow.plugin.api.annotations.Example;
import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.ExecutionUpdatableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Remove execution variables.",
    description = """
        Renders a list of keys and deletes them from `vars`. Supports dotted paths for nested maps. If `ignoreMissing` is false (default), missing keys cause an error.

        Useful for cleaning sensitive or transient data mid-flow."""
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Set and later unset variables",
            code = """
                id: variables
                namespace: company.team

                variables:
                  name: World

                tasks:
                  - id: set_vars
                    type: org.flow.plugin.core.execution.SetVariables
                    variables:
                      message: Hello
                      name: Loïc
                  - id: hello
                    type: org.flow.plugin.core.log.Log
                    message: "{{ vars.message }} {{ vars.name }}"
                  - id: unset_variables
                    type: org.flow.plugin.core.execution.UnsetVariables
                    variables:
                      - message
                      - name"""
        )
    }
)
public class UnsetVariables extends Task implements ExecutionUpdatableTask {
    @Schema(title = "The variables")
    @NotNull
    private Property<List<String>> variables;

    @Schema(title = "Flag specifying whether to ignore missing variables")
    @NotNull
    @Builder.Default
    private Property<Boolean> ignoreMissing = Property.ofValue(false);

    @Override
    public Execution update(Execution execution, RunContext runContext) throws Exception {
        // the executor reuses the task instance of its cached flow for every execution of the flow,
        // so the rendering cache of the properties must be skipped to render with the current execution context
        List<String> renderedVariables = runContext.render(this.variables.skipCache()).asList(String.class);
        boolean renderedIgnoreMissing = runContext.render(this.ignoreMissing.skipCache()).as(Boolean.class).orElseThrow();
        Map<String, Object> variables = execution.getVariables();
        for (String key : renderedVariables) {
            removeVar(variables, key, renderedIgnoreMissing);
        }
        return execution.withVariables(variables);
    }

    @SuppressWarnings("unchecked")
    private void removeVar(Map<String, Object> vars, String key, boolean ignoreMissing) {
        if (key.indexOf('.') >= 0) {
            String prefix = key.substring(0, key.indexOf('.'));
            String suffix = key.substring(key.indexOf('.') + 1);

            Object child = vars.get(prefix);
            if (!(child instanceof Map)) {
                if (!ignoreMissing) {
                    throw new IllegalArgumentException("Variable '" + key + "' not found and `ignoreMissing` is false");
                }
                return;
            }
            removeVar((Map<String, Object>) vars.get(prefix), suffix, ignoreMissing);
        } else {
            if (!ignoreMissing && !vars.containsKey(key)) {
                throw new IllegalArgumentException("Variable '" + key + "' not found and `ignoreMissing` is false");
            }
            vars.remove(key);
        }
    }
}
