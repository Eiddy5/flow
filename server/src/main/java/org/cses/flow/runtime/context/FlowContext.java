package org.cses.flow.runtime.context;

import java.util.Objects;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.runtime.model.Process;

public final class FlowContext {

    private final Flow flow;
    private final Process process;
    private final ResumeTarget resumeTarget;

    public FlowContext(Flow flow, Process process) {
        this(flow, process, null);
    }

    public FlowContext(Flow flow, Process process, ResumeTarget resumeTarget) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.process = Objects.requireNonNull(process, "process");
        this.resumeTarget = resumeTarget;
    }

    public Flow flow() { return flow; }
    public Process process() { return process; }
    public ResumeTarget resumeTarget() { return resumeTarget; }
}
