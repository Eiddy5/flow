package org.cses.flow.core.domains.tasks;

import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable invocation context for one {@link RunnableTask}.
 *
 * <p>Execution, TaskRun and WorkerTask deliberately do not cross this
 * boundary. Their lifecycle remains owned by the Executor.</p>
 */
public final class RunContext {

    private final Session<? extends User> session;
    private final DSLContext dsl;
    private final Map<String, Object> inputs;

    private RunContext(
        Session<? extends User> session,
        DSLContext dsl,
        Map<String, ?> inputs
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public static RunContext create(
        Session<? extends User> session,
        DSLContext dsl,
        Map<String, ?> inputs
    ) {
        return new RunContext(session, dsl, inputs);
    }

    public Session<? extends User> session() {
        return session;
    }

    public DSLContext dsl() {
        return dsl;
    }

    public Map<String, Object> inputs() {
        return inputs;
    }
}
