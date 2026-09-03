package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Read-only validation result for one prospective rewind.
 *
 * <p>The plan is calculated from a single pre-rewind Execution snapshot. It
 * does not enqueue a Rewind command and therefore allows a host application
 * to commit its own rollback bookkeeping before submitting the Flow command.</p>
 *
 * @param execution pre-rewind Execution snapshot used for validation
 * @param targetTaskKey business key of the selected target Flow task
 * @param affectedTaskRunIds TaskRuns in business rollback order
 */
public record RewindPlan(
        Execution execution,
        String targetTaskKey,
        List<String> affectedTaskRunIds
) {

    /**
     * Copies and validates the immutable rewind planning result.
     *
     * @param execution pre-rewind Execution snapshot
     * @param targetTaskKey non-blank business key of the target task
     * @param affectedTaskRunIds non-empty, unique affected TaskRun IDs
     * @return defensive rewind plan
     * @throws NullPointerException when the snapshot or affected IDs are null
     * @throws IllegalArgumentException when a key or affected ID is invalid
     */
    public static RewindPlan from(
            Execution execution,
            String targetTaskKey,
            List<String> affectedTaskRunIds
    ) {
        return new RewindPlan(
                execution,
                targetTaskKey,
                affectedTaskRunIds
        );
    }

    /**
     * Establishes defensive copies before this plan crosses service boundaries.
     *
     * @throws NullPointerException when the snapshot or affected IDs are null
     * @throws IllegalArgumentException when a key or affected ID is invalid
     */
    public RewindPlan {
        execution = Objects.requireNonNull(
                Objects.requireNonNull(
                        execution,
                        "Rewind Execution"
                ).copy(),
                "Copied Rewind Execution"
        );
        if (targetTaskKey == null || targetTaskKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Rewind target task key must not be blank"
            );
        }
        targetTaskKey = targetTaskKey.trim();
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
     * Returns a defensive copy of the snapshot used to create this plan.
     *
     * @return copied pre-rewind Execution
     */
    @Override
    public Execution execution() {
        return execution.copy();
    }
}
