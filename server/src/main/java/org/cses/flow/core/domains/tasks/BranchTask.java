package org.cses.flow.core.domains.tasks;

/**
 * Flow-control capability implemented by a concrete Task definition.
 *
 * <p>A BranchTask has no executable work and is never sent to a Worker.
 * Its methods only declare stable orchestration characteristics; the
 * Executor interprets them and owns every resulting Execution and TaskRun
 * transition.</p>
 */
public interface BranchTask {

    /**
     * Whether the TaskRun remains waiting for an explicit resume result.
     */
    default boolean waitsForResume() {
        return false;
    }

    /**
     * Whether matching direct children are started as one parallel batch.
     */
    default boolean startsChildrenInParallel() {
        return false;
    }
}
