package org.cses.flow.executor;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Objects;

/**
 * Rebuildable transaction-scoped context for the Execution state machine.
 */
public final class ExecutorContext<
    S extends Session<U>,
    U extends User
> {

    private final S session;
    private final DSLContext dsl;
    private final Flow flow;
    private final Execution execution;

    public ExecutorContext(
        S session,
        DSLContext dsl,
        Flow flow,
        Execution execution
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.execution = Objects.requireNonNull(execution, "execution");
        if (!flow.id().equals(execution.flowId())
            || !flow.companyId().equals(execution.companyId())
            || flow.reversion() != execution.flowReversion()) {
            throw new IllegalArgumentException(
                "Execution does not belong to the exact Flow reversion"
            );
        }
    }

    public S session() {
        return session;
    }

    public DSLContext dsl() {
        return dsl;
    }

    public Flow flow() {
        return flow;
    }

    public Execution execution() {
        return execution;
    }
}
