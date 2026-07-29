package org.cses.flow.worker;

import org.cses.flow.core.domains.tasks.Task;
import org.paas.session.Session;
import org.paas.session.User;

public interface WorkerTaskHandler {

    boolean supports(Task task);

    <S extends Session<U>, U extends User> WorkerTaskResult execute(
        WorkerContext<S, U> context
    );

    default <S extends Session<U>, U extends User> void cancel(
        WorkerContext<S, U> context
    ) {
    }
}
