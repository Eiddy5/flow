package org.cses.flow.core.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.paas.common.util.StringUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-reader state for a single plugin definition materialization.
 *
 * <p>The state is supplied as a Jackson reader attribute, so concurrent
 * requests do not share mutable binding state. It is intentionally limited
 * to deployment concerns; persistence restoration omits the attribute and
 * therefore binds the stored identity as-is.</p>
 */
public final class PluginDeserializationContext {

    /** Jackson reader attribute key used by the plugin deserializer. */
    public static final String ATTRIBUTE =
        PluginDeserializationContext.class.getName();

    private final Map<String, String> taskIdsByKey;

    private PluginDeserializationContext(Map<String, String> taskIdsByKey) {
        this.taskIdsByKey = new LinkedHashMap<>(taskIdsByKey);
    }

    /**
     * Creates deployment state, optionally seeded with the latest reversion's
     * Task identities.
     */
    public static PluginDeserializationContext forDeployment(
        Map<String, String> existingTaskIdsByKey
    ) {
        if (existingTaskIdsByKey == null) {
            throw new IllegalArgumentException(
                "Existing Task identities must not be null"
            );
        }
        return new PluginDeserializationContext(existingTaskIdsByKey);
    }

    /**
     * Prepares one user-authored Task definition. System identity fields are
     * rejected before the concrete plugin class is bound.
     */
    public void prepareTask(ObjectNode definition) {
        rejectSystemField(definition, "id");
        rejectSystemField(definition, "parentId");
        rejectSystemField(definition, "taskId");

        JsonNode keyNode = definition.get("key");
        String key;
        if (keyNode == null || keyNode.isNull()) {
            key = StringUtil.newId();
        } else if (!keyNode.isTextual() || keyNode.textValue().isBlank()) {
            throw new IllegalArgumentException(
                "Task key must be non-blank text"
            );
        } else {
            key = keyNode.textValue().trim();
        }

        definition.put("key", key);
        definition.put(
            "id",
            taskIdsByKey.computeIfAbsent(
                key,
                ignored -> StringUtil.newId()
            )
        );
    }

    private static void rejectSystemField(
        ObjectNode definition,
        String field
    ) {
        if (definition.has(field)) {
            throw new IllegalArgumentException(
                "Task must not declare system field " + field
            );
        }
    }
}
