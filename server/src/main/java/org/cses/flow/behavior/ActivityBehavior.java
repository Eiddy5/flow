package org.cses.flow.behavior;

public interface ActivityBehavior {

    ActivityExecuteResult execute(ActivityExecuteContext context);

    default ActivitySignalResult handleSignal(ActivitySignalContext context) {
        throw new IllegalStateException(
                "Node does not accept external Signal: " + context.node().id());
    }
}
