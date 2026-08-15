package org.cses.flow.executor.commands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Requests creation and first drive of one exact Flow version.
 *
 * <p>The command deliberately has no Execution id. The consumer resolves the
 * Flow by {@code companyId + flowKey + flowVersion}, creates the Execution,
 * and publishes the first internal Executor event in the same transaction.</p>
 */
public record Create(
    String companyId,
    String flowKey,
    long flowVersion,
    Map<String, Object> inputs
) implements ExecutionCommand {

    public Create {
        companyId = requireText(companyId, "Company id");
        flowKey = requireText(flowKey, "Flow key");
        if (flowVersion < 1) {
            throw new IllegalArgumentException(
                "Flow version must be positive"
            );
        }
        inputs = immutableInputs(inputs);
    }

    public static Create of(
        String companyId,
        String flowKey,
        long flowVersion,
        Map<String, ?> inputs
    ) {
        return new Create(
            companyId,
            flowKey,
            flowVersion,
            immutableInputs(inputs)
        );
    }

    @Override
    public Type getType() {
        return Type.CREATE;
    }

    @Override
    public String key() {
        return companyId + ":" + flowKey + ":" + flowVersion;
    }

    @Override
    public void validate() {
        requireText(companyId, "Company id");
        requireText(flowKey, "Flow key");
        if (flowVersion < 1) {
            throw new IllegalArgumentException(
                "Flow version must be positive"
            );
        }
        immutableInputs(inputs);
    }

    /**
     * JavaBean aliases keep queue payload adapters compatible with the
     * existing command JSON contract while the record remains the source of
     * truth.
     */
    public String getCompanyId() {
        return companyId;
    }

    public String getFlowKey() {
        return flowKey;
    }

    public long getFlowVersion() {
        return flowVersion;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> immutableInputs(
        Map<String, ?> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
            requireText(key, "Flow input key"),
            Objects.requireNonNull(value, "Flow input value")
        ));
        return Map.copyOf(copied);
    }
}
