package org.cses.flow.core.serializers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.PluginDeserializationContext;
import org.cses.flow.core.validations.ModelValidator;
import org.paas.common.util.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Materializes one deployed Flow from strict YAML.
 *
 * <p>A top-level {@code key} supplied by the caller is the deployed Flow
 * identity. When it is absent, the caller-provided stable fallback is used;
 * direct serializer callers without a fallback receive a generated key.</p>
 *
 * <p>Task polymorphism is owned by Jackson's registered
 * {@code PluginDeserializer}. This class only owns Flow fields, deployment
 * identity state, validation, and the conversion from the parsed tree to
 * Flow.</p>
 */
@Singleton
public final class FlowDefinitionDeserializer {

    private static final Set<String> FLOW_FIELDS = Set.of(
        "key",
        "description",
        "variables",
        "inputs",
        "outputs",
        "tasks"
    );

    private final YamlParser yamlParser;
    private final JacksonMapper jacksonMapper;
    private final ModelValidator modelValidator;

    public FlowDefinitionDeserializer(
        YamlParser yamlParser,
        JacksonMapper jacksonMapper,
        ModelValidator modelValidator
    ) {
        this.yamlParser = yamlParser;
        this.jacksonMapper = jacksonMapper;
        this.modelValidator = modelValidator;
    }

    /**
     * Reads the optional business key without materializing the Flow.
     * Deployment uses this before loading the latest revision so a draft's
     * generated fallback key cannot hide an externally declared key.
     */
    public Optional<String> declaredFlowKey(String source) {
        return Optional.ofNullable(
            declaredFlowKey(parseDefinition(source))
        );
    }

    public Flow deserialize(
        String source,
        String companyId,
        String flowKey,
        Flow latest,
        ActorRef actor,
        long deployedAt
    ) {
        ObjectNode definition = parseDefinition(source);

        String externalFlowKey = declaredFlowKey(definition);
        String effectiveFlowKey = externalFlowKey == null
            ? fallbackFlowKey(flowKey, latest)
            : externalFlowKey;

        Map<String, String> taskIdsByKey = new LinkedHashMap<>();
        if (latest != null) {
            latest.allTasks().forEach(task ->
                taskIdsByKey.put(task.key(), task.id())
            );
        }

        return Flow.deploy(
            companyId,
            effectiveFlowKey,
            optionalText(definition, "description", "", "Flow"),
            variables(definition.get("variables"), "Flow.variables"),
            inputs(definition.get("inputs"), "Flow.inputs"),
            outputs(definition.get("outputs"), "Flow.outputs"),
            tasks(
                definition.get("tasks"),
                "Flow.tasks",
                taskIdsByKey
            ),
            latest,
            actor,
            deployedAt
        );
    }

    private ObjectNode parseDefinition(String source) {
        ObjectNode definition = yamlParser.parseTree(source);
        rejectUnknownFields(definition, FLOW_FIELDS, "Flow");
        return definition;
    }

    private static String declaredFlowKey(ObjectNode definition) {
        return optionalText(
            definition,
            "key",
            null,
            "Flow"
        );
    }

    private static String fallbackFlowKey(String supplied, Flow latest) {
        if (latest != null) {
            return latest.key();
        }
        if (supplied != null && !supplied.isBlank()) {
            return supplied.trim();
        }
        return StringUtil.newId();
    }

    private Map<String, Object> variables(JsonNode value, String path) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof ObjectNode)) {
            throw new IllegalArgumentException(path + " must be a map");
        }
        try {
            return jacksonMapper.toMap(value);
        } catch (RuntimeException exception) {
            throw materializationFailure(path, exception);
        }
    }

    private List<Task> tasks(
        JsonNode value,
        String path,
        Map<String, String> idsByKey
    ) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof ArrayNode definitions)) {
            throw new IllegalArgumentException(path + " must be a list");
        }

        PluginDeserializationContext binding =
            PluginDeserializationContext.forDeployment(idsByKey);
        List<Task> tasks = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            String itemPath = path + "[" + index + "]";
            JsonNode item = definitions.get(index);
            if (!(item instanceof ObjectNode object)) {
                throw new IllegalArgumentException(itemPath + " must be a map");
            }
            try {
                Task task = jacksonMapper.readTree(
                    object,
                    Task.class,
                    Map.of(
                        PluginDeserializationContext.ATTRIBUTE,
                        binding
                    )
                );
                tasks.add(modelValidator.validate(task));
            } catch (RuntimeException exception) {
                throw materializationFailure(itemPath, exception);
            }
        }
        return List.copyOf(tasks);
    }

    private List<Input<?>> inputs(JsonNode value, String path) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof ArrayNode definitions)) {
            throw new IllegalArgumentException(path + " must be a list");
        }

        List<Input<?>> inputs = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            String itemPath = path + "[" + index + "]";
            try {
                Input<?> input = jacksonMapper.readTree(
                    definitions.get(index),
                    Input.class
                );
                input.validateDefinition();
                inputs.add(input);
            } catch (RuntimeException exception) {
                throw materializationFailure(itemPath, exception);
            }
        }
        return List.copyOf(inputs);
    }

    private List<Output> outputs(JsonNode value, String path) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof ArrayNode definitions)) {
            throw new IllegalArgumentException(path + " must be a list");
        }

        List<Output> outputs = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            String itemPath = path + "[" + index + "]";
            try {
                outputs.add(jacksonMapper.readTree(
                    definitions.get(index),
                    Output.class
                ));
            } catch (RuntimeException exception) {
                throw materializationFailure(itemPath, exception);
            }
        }
        return List.copyOf(outputs);
    }

    private static void rejectUnknownFields(
        ObjectNode definition,
        Set<String> allowed,
        String path
    ) {
        Set<String> unknown = new LinkedHashSet<>();
        definition.fieldNames().forEachRemaining(unknown::add);
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                path + " contains unsupported fields: " + unknown
            );
        }
    }

    private static String optionalText(
        ObjectNode definition,
        String field,
        String defaultValue,
        String path
    ) {
        JsonNode value = definition.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(
                path + "." + field + " must be text"
            );
        }
        return value.textValue().trim();
    }

    private static IllegalArgumentException materializationFailure(
        String path,
        Throwable exception
    ) {
        String detail = exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
        return new IllegalArgumentException(
            path + " could not be materialized: " + detail,
            exception
        );
    }
}
