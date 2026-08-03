package org.cses.flow.core.domains.externaltasks;

import org.cses.flow.core.exceptions.WorkflowException;
import org.paas.common.util.StringUtil;

import java.util.Map;
import java.util.Objects;

/**
 * Core-owned durable wait state for one PAUSE TaskRun.
 */
public final class ExternalTask {

    private final String id;
    private final String companyId;
    private final String executionId;
    private final String taskRunId;
    private ExternalTaskStatus status;
    private Map<String, Object> outputs;
    private long lockVersion;

    private ExternalTask(
        String id,
        String companyId,
        String executionId,
        String taskRunId
    ) {
        this.id = requireText(id, "ExternalTask id");
        if (companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException("Company id must not be blank");
        }
        this.companyId = companyId.trim();
        this.executionId = requireText(executionId, "Execution id");
        this.taskRunId = requireText(taskRunId, "TaskRun id");
        this.status = ExternalTaskStatus.WAITING;
        this.outputs = Map.of();
    }

    private ExternalTask(ExternalTask source) {
        this.id = source.id;
        this.companyId = source.companyId;
        this.executionId = source.executionId;
        this.taskRunId = source.taskRunId;
        this.status = source.status;
        this.outputs = source.outputs;
        this.lockVersion = source.lockVersion;
    }

    public static ExternalTask create(
        String companyId,
        String executionId,
        String taskRunId
    ) {
        return new ExternalTask(
            StringUtil.newId(),
            companyId,
            executionId,
            taskRunId
        );
    }

    /**
     * Rehydrates an ExternalTask from a trusted persistence adapter.
     */
    public static ExternalTask rehydrate(
        String id,
        String companyId,
        String executionId,
        String taskRunId,
        ExternalTaskStatus status,
        Map<String, ?> outputs,
        long lockVersion
    ) {
        if (lockVersion < 0) {
            throw new IllegalArgumentException(
                "ExternalTask lock version must not be negative"
            );
        }
        ExternalTask externalTask = new ExternalTask(
            id,
            companyId,
            executionId,
            taskRunId
        );
        ExternalTaskStatus restoredStatus = Objects.requireNonNull(
            status,
            "ExternalTask status"
        );
        Map<String, Object> restoredOutputs = outputs == null
            ? Map.of()
            : Map.copyOf(outputs);
        if (restoredStatus != ExternalTaskStatus.COMPLETED
            && !restoredOutputs.isEmpty()) {
            throw new IllegalArgumentException(
                "Only a COMPLETED ExternalTask can contain outputs"
            );
        }
        externalTask.status = restoredStatus;
        externalTask.outputs = restoredOutputs;
        externalTask.lockVersion = lockVersion;
        return externalTask;
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String executionId() {
        return executionId;
    }

    public String taskRunId() {
        return taskRunId;
    }

    public ExternalTaskStatus status() {
        return status;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    public long lockVersion() {
        return lockVersion;
    }

    public void complete(Map<String, ?> validatedOutputs) {
        requireWaiting();
        outputs = Map.copyOf(Objects.requireNonNull(
            validatedOutputs,
            "Validated outputs"
        ));
        status = ExternalTaskStatus.COMPLETED;
        lockVersion++;
    }

    public void cancel() {
        requireWaiting();
        status = ExternalTaskStatus.CANCELED;
        lockVersion++;
    }

    public ExternalTask copy() {
        return new ExternalTask(this);
    }

    private void requireWaiting() {
        if (status != ExternalTaskStatus.WAITING) {
            throw new WorkflowException(
                "ExternalTask must be WAITING but was " + status + ": " + id
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank"
            );
        }
        return value.trim();
    }
}
