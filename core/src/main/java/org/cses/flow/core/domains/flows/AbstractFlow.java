package org.cses.flow.core.domains.flows;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.Audited;
import org.paas.session.RecordState;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared definition facts of an editable draft or deployed Flow version.
 */
public abstract class AbstractFlow extends Audited {

    String key;
    Long version;
    boolean draft = true;
    String description = "";
    Map<String, Object> variables = Map.of();
    List<Input<?>> inputs = List.of();
    List<Output> outputs = List.of();

    protected AbstractFlow() {
        super();
    }

    protected AbstractFlow(
        String id,
        String key,
        Long version,
        boolean draft,
        Session<? extends User> session,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs
    ) {
        super(id, session);
        initializeDefinition(
            key,
            version,
            draft,
            description,
            variables,
            inputs,
            outputs
        );
    }

    protected AbstractFlow(
        String id,
        String key,
        Long version,
        boolean draft,
        String companyId,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        ActorRef creator,
        long createdAt,
        RecordState status,
        ActorRef updater,
        long updatedAt,
        ActorRef deleter,
        Long deletedAt
    ) {
        super(
            id,
            companyId,
            creator,
            createdAt,
            status,
            updater,
            updatedAt,
            deleter,
            deletedAt
        );
        initializeDefinition(
            key,
            version,
            draft,
            description,
            variables,
            inputs,
            outputs
        );
    }

    public String key() {
        return key;
    }

    public long version() {
        return requireVersion();
    }

    public long reversion() {
        return version();
    }

    public Long versionOrNull() {
        return version;
    }

    public boolean draft() {
        return draft;
    }

    public boolean deployed() {
        return !draft;
    }

    public String description() {
        return description;
    }

    public Map<String, Object> variables() {
        return variables;
    }

    public List<Input<?>> inputs() {
        return inputs;
    }

    public List<Output> outputs() {
        return outputs;
    }

    protected void reviseDraftDefinition(
        Session<? extends User> session,
        long revisedAt,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs
    ) {
        requireDraft();
        String checkedDescription = normalizeDescription(description);
        Map<String, Object> checkedVariables = immutableVariables(variables);
        List<Input<?>> checkedInputs = immutableData(inputs, "Flow inputs");
        List<Output> checkedOutputs = immutableData(outputs, "Flow outputs");

        update(session, revisedAt);
        this.description = checkedDescription;
        this.variables = checkedVariables;
        this.inputs = checkedInputs;
        this.outputs = checkedOutputs;
    }

    protected void requireDraft() {
        if (!draft) {
            throw new IllegalStateException(
                "Deployed Flow cannot be changed as a draft: " + key
            );
        }
    }

    protected void requireDeployed() {
        if (draft) {
            throw new IllegalStateException(
                "Draft Flow cannot participate in execution: " + key
            );
        }
    }

    protected long requireVersion() {
        requireDeployed();
        if (version == null) {
            throw new IllegalStateException(
                "Deployed Flow has no version: " + key
            );
        }
        return version;
    }

    protected void initializeDefinition(
        String key,
        Long version,
        boolean draft,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs
    ) {
        this.key = requireKey(key);
        this.version = requireVersion(draft, version);
        this.draft = draft;
        this.description = normalizeDescription(description);
        this.variables = immutableVariables(variables);
        this.inputs = immutableData(inputs, "Flow inputs");
        this.outputs = immutableData(outputs, "Flow outputs");
    }

    private static <T extends Data> List<T> immutableData(
        List<? extends T> source,
        String field
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<T> result = new ArrayList<>(source.size());
        Set<String> keys = new HashSet<>();
        for (T data : source) {
            if (data == null) {
                throw new IllegalArgumentException(
                    field + " must not contain null values"
                );
            }
            if (!keys.add(data.getKey())) {
                throw new IllegalArgumentException(
                    field + " contains duplicate key: " + data.getKey()
                );
            }
            result.add(data);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> immutableVariables(
        Map<String, ?> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(
            Objects.requireNonNull(key, "Flow variable key"),
            value
        ));
        return Collections.unmodifiableMap(result);
    }

    private static String normalizeDescription(String value) {
        return value == null ? "" : value;
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Flow key must not be blank");
        }
        return value.trim();
    }

    private static Long requireVersion(boolean draft, Long value) {
        if (draft) {
            if (value != null) {
                throw new IllegalArgumentException(
                    "Draft Flow must not have a version"
                );
            }
            return null;
        }
        if (value == null || value < 1) {
            throw new IllegalArgumentException(
                "Deployed Flow version must be positive"
            );
        }
        return value;
    }
}
