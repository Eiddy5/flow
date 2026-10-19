package org.flow.plugin.core.kv;

import java.time.Duration;
import java.time.Instant;

import org.hibernate.validator.constraints.time.DurationMin;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import org.flow.plugin.api.annotations.Example;
import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.models.kv.KVType;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.tasks.VoidOutput;
import org.flow.core.runners.RunContext;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.storages.kv.KVMetadata;
import org.flow.core.storages.kv.KVStore;
import org.flow.core.storages.kv.KVValueAndMetadata;
import org.flow.core.utils.TypeConverter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Create or update a key-value entry.",
    description = """
        Renders `key`, `value`, and `namespace` (defaults to flow namespace) and writes to the KV store. Supports TTL, description, type coercion (`kvType`), and overwrite control.

        If `kvType` is set, the string value is parsed/validated accordingly (number, boolean, datetime, duration, JSON, etc.)."""
)
@Plugin(
    examples = {
        @Example(
            title = "Set the task's `uri` output as a value for `orders_file` key.",
            full = true,
            code = """
                id: kv_store_set
                namespace: company.team

                tasks:
                  - id: http_download
                    type: org.flow.plugin.core.http.Download
                    uri: https://example.com/orders.csv

                  - id: kv_set
                    type: org.flow.plugin.core.kv.Set
                    key: orders_file
                    value: "{{ outputs.http_download.uri }}"
                    kvType: STRING
                """
        )
    }
)
public class Set extends Task implements RunnableTask<VoidOutput> {
    @NotNull
    @Schema(
        title = "The key to set the value for"
    )
    private Property<String> key;

    @Schema(
        title = "The description of the KV pair"
    )
    private Property<String> kvDescription;

    @NotNull
    @Schema(
        title = "The value to map to the key"
    )
    private Property<String> value;

    @NotNull
    @Schema(
        title = "The namespace in which the KV pair will be stored – by default, Flow will use the namespace of the flow."
    )
    @Builder.Default
    private Property<String> namespace = Property.ofExpression("{{ flow.namespace }}");

    @NotNull
    @Schema(
        title = "Flag specifying whether to overwrite or fail if a value for the given key already exists."
    )
    @Builder.Default
    private Property<Boolean> overwrite = Property.ofValue(true);

    @Schema(
        title = "Optional Time-To-Live (TTL) duration for the key-value pair. If not set, the KV pair will never be deleted from internal storage."
    )
    private Property<@DurationMin(millis = 1, message = "must be a positive duration") Duration> ttl;

    @Schema(
        title = "Enum representing the data type of the KV pair. If not set, the value will be stored as a string."
    )
    private Property<KVType> kvType;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        String renderedNamespace = runContext.render(this.namespace).as(String.class).orElse(null);

        String renderedKey = runContext.render(this.key).as(String.class).orElse(null);

        Object renderedValue = runContext.renderTyped(this.value.toString());

        KVStore kvStore = runContext.namespaceKv(renderedNamespace);

        if (kvType != null) {
            KVType renderedKvType = runContext.render(kvType).as(KVType.class).orElseThrow();
            if (renderedValue instanceof String renderedValueStr) {
                renderedValue = switch (renderedKvType) {
                    case NUMBER -> JacksonMapper.ofJson().readValue(renderedValueStr, Number.class);
                    case BOOLEAN -> parseBoolean(renderedValueStr);
                    case DATETIME -> TypeConverter.toInstant(renderedValueStr);
                    case DATE -> parseDate(renderedValueStr);
                    // We parse duration to make sure it's valid but we store it as a raw duration string
                    case DURATION -> {
                        TypeConverter.toDuration(renderedValueStr);
                        yield renderedValueStr;
                    }
                    case JSON -> JacksonMapper.toObject(renderedValueStr);
                    default -> renderedValue;
                };
            } else if (renderedValue instanceof Number valueNumber && renderedKvType == KVType.STRING) {
                renderedValue = valueNumber.toString();
            }
        }

        kvStore.put(
            renderedKey, new KVValueAndMetadata(
                new KVMetadata(
                    runContext.render(kvDescription).as(String.class).orElse(null),
                    runContext.render(ttl).as(Duration.class).orElse(null)
                ), renderedValue
            ),
            runContext.render(this.overwrite).as(Boolean.class).orElseThrow()
        );

        return null;
    }

    /**
     * Parses a {@code DATE}-typed KV value into a {@link LocalDate}.
     * <p>
     * A date-only value (e.g. {@code 2023-05-02}) is the expected form. A full ISO-8601
     * instant (e.g. {@code 2023-05-02T01:02:03Z}) is also accepted — previously {@code DATE}
     * shared {@code DATETIME}'s {@code Instant.parse} branch — and is truncated to its UTC date.
     */
    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return LocalDate.ofInstant(Instant.parse(value), ZoneOffset.UTC);
        }
    }

    /**
     * Parses a {@code BOOLEAN}-typed KV value, failing the task on anything but {@code true}/{@code false}
     * (case-insensitive) so a typo is not silently coerced to {@code false}, matching how the other typed values reject
     * invalid input.
     */
    private static Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Cannot parse '%s' as a BOOLEAN value: expected 'true' or 'false'.".formatted(value));
    }
}
