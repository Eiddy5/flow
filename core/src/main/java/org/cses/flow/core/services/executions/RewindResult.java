package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Queue-acceptance result for one validated rewind request.
 *
 * @param execution pre-rewind Execution snapshot observed at submission time
 * @param affectedTaskRunIds TaskRuns in business rollback order
 */
public record RewindResult(
        Execution execution,
        List<String> affectedTaskRunIds
) {

    public RewindResult {
        execution = Objects.requireNonNull(
                execution,
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

    public static RewindResult from(
            Execution execution,
            List<String> affectedTaskRunIds
    ) {
        return new RewindResult(execution, affectedTaskRunIds);
    }

    @Override
    public Execution execution() {
        return execution.copy();
    }
}
