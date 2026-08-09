package org.cses.flow.core.domains.tasks;

import java.util.Map;

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
     * Whether this Task repeats its direct child scope in serial iterations.
     */
    default boolean iteratesChildren() {
        return false;
    }

    /**
     * Hard upper bound for an iterative child scope.
     */
    default int maxIterations() {
        return 1;
    }

    /**
     * Decides what happens after one complete child iteration has settled.
     */
    default IterationDecision decideAfterIteration(
        int completedIterations,
        Map<String, Map<String, Object>> iterationOutputs
    ) {
        return IterationDecision.SUCCESS;
    }

    /**
     * Stable failure message for an iterative scope that cannot complete.
     */
    default String iterationFailureMessage(int completedIterations) {
        return "Orchestration did not complete after " + completedIterations
            + " iterations";
    }

    /**
     * Whether this TaskRun remains RUNNING until its child scope settles.
     */
    default boolean holdsTaskRunUntilChildrenSettle() {
        return false;
    }

    enum IterationDecision {
        CONTINUE,
        SUCCESS,
        FAILURE
    }
}
