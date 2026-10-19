package org.flow.plugin.core.kv;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import org.flow.plugin.api.annotations.Example;
import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.DefaultRunContext;
import org.flow.core.runners.RunContext;
import org.flow.core.storages.kv.KVValue;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Read a key-value entry.",
    description = """
        Renders `key`/`namespace` (defaults to flow namespace) and fetches the value. In the same namespace it walks dot-delimited parents to support hierarchical lookup. Set `errorOnMissing` to true to fail when absent.

        Respects namespace ACLs when reading other namespaces."""
)
@Plugin(
    examples = {
        @Example(
            title = "Get value for `my_variable` key in `dev` namespace and fail if it's not present. Note that you can accomplish the same using the `kv()` Pebble function, e.g. `{{kv('my_variable')}}`.",
            full = true,
            code = """
                id: kv_store_get
                namespace: company.team

                tasks:
                  - id: kv_get
                    type: org.flow.plugin.core.kv.Get
                    key: my_variable
                    namespace: company # the current namespace is used by default
                    errorOnMissing: true
                """
        )
    }
)
public class Get extends Task implements RunnableTask<Get.Output> {
    @NotNull
    @Schema(
        title = "The key for which to get the value"
    )
    private Property<String> key;

    @NotNull
    @Schema(
        title = "The namespace from which to retrieve the KV pair"
    )
    @Builder.Default
    private Property<String> namespace = Property.ofExpression("{{ flow.namespace }}");

    @NotNull
    @Schema(
        title = "Flag specifying whether to fail if there is no value for the given key"
    )
    @Builder.Default
    private Property<Boolean> errorOnMissing = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String renderedNamespace = runContext.render(this.namespace).as(String.class).orElse(null);
        String flowNamespace = runContext.flowInfo().namespace();
        String renderedKey = runContext.render(this.key).as(String.class).orElse(null);

        Optional<KVValue> value;
        if (Objects.equals(renderedNamespace, flowNamespace)) {
            value = ((DefaultRunContext) runContext).services().kvStoreService().findValueWithInheritance(runContext.flowInfo().tenantId(), flowNamespace, renderedKey);
        } else {
            runContext.acl().allowNamespace(renderedNamespace).check();
            value = runContext.namespaceKv(renderedNamespace).getValue(renderedKey);
        }

        if (Boolean.TRUE.equals(runContext.render(this.errorOnMissing).as(Boolean.class).orElseThrow()) && value.isEmpty()) {
            throw new NoSuchElementException("No value found for key '" + renderedKey + "' in namespace '" + renderedNamespace + "' and `errorOnMissing` is set to true");
        }

        return Output.builder()
            .value(value.map(KVValue::value).orElse(null))
            .build();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        @Schema(
            title = "Value retrieved for the key",
            description = "This can be of any type and will stay the same as when it was set."
        )
        private final Object value;
    }
}
