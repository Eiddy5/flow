package org.cses.flow.worker;

import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Objects;

public final class WorkerContext<
    S extends Session<U>,
    U extends User
> {

    private final S session;
    private final DSLContext dsl;
    private final WorkerTask workerTask;

    public WorkerContext(
        S session,
        DSLContext dsl,
        WorkerTask workerTask
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.workerTask = Objects.requireNonNull(
            workerTask,
            "workerTask"
        );
    }

    public S session() {
        return session;
    }

    public DSLContext dsl() {
        return dsl;
    }

    public WorkerTask workerTask() {
        return workerTask;
    }
}
