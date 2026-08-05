package org.cses.flow.core.domains.tasks;

/**
 * Flow-control capability implemented by a concrete Task definition.
 *
 * <p>An OrchestrationTask has no executable work and is never sent to a
 * Worker. Its methods only declare stable orchestration characteristics; the
 * Executor interprets them and owns every resulting Execution and TaskRun
 * transition.</p>
 */
public interface OrchestrationTask {

    /**
     * Whether entering this Task pauses its TaskRun for an explicit resume.
     */
    default boolean pausesTaskRun() {
        return false;
    }

    /**
     * Whether matching direct children may start as parallel branches.
     */
    default boolean startsChildrenInParallel() {
        return false;
    }

    /**
     * Whether this TaskRun remains RUNNING until its child scope settles.
     */
    default boolean holdsTaskRunUntilChildrenSettle() {
        return false;
    }
}
