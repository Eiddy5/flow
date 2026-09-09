package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Queue-acceptance result for one validated rewind request.
 *
 * @param executionId preallocated ID of the new Execution
 * @param sourceExecution source snapshot observed at submission time
 * @param affectedTaskRunIds TaskRuns in business rollback order
 */
public record RewindResult(
        String executionId,
        Execution sourceExecution,
        List<String> affectedTaskRunIds
) {

    /**
     * Validates the new identity and copies the source snapshot and exact affected list.
     * @param executionId preallocated new Execution ID
     * @param sourceExecution source snapshot before asynchronous consumption
     * @param affectedTaskRunIds nonempty unique affected IDs in rollback order
     * @throws IllegalArgumentException when identity or affected IDs are invalid
     */
    public RewindResult {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("Replay Execution id must not be blank");
        }
        sourceExecution = Objects.requireNonNull(
                sourceExecution,
                "Rewind Execution"
        ).copy();
        affectedTaskRunIds = List.copyOf(Objects.requireNonNull(
                affectedTaskRunIds,
                "Affected TaskRun ids"
        ));
        if (affectedTaskRunIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Affected TaskRun ids must not be empty"
            );
        }
        if (affectedTaskRunIds.stream().anyMatch(id ->
                id == null || id.isBlank()
        )) {
            throw new IllegalArgumentException(
                    "Affected TaskRun id must not be blank"
            );
        }
        if (new HashSet<>(affectedTaskRunIds).size()
                != affectedTaskRunIds.size()) {
            throw new IllegalArgumentException(
                    "Affected TaskRun ids must be unique"
            );
        }
    }

    /**
     * Creates an immutable queue acceptance result.
     * @param executionId new Execution ID
     * @param sourceExecution source snapshot
     * @param affectedTaskRunIds exact affected path
     * @return independently copied acceptance result
     */
    public static RewindResult from(
            String executionId,
            Execution sourceExecution,
            List<String> affectedTaskRunIds
    ) {
        return new RewindResult(executionId, sourceExecution, affectedTaskRunIds);
    }

    /** @return an independent copy of the source snapshot at acceptance */
    @Override
    public Execution sourceExecution() {
        return sourceExecution.copy();
    }
}
