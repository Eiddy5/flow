package org.cses.flow.worker;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.tasks.Task;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Collection;
import java.util.List;

@Singleton
public final class WorkerDispatcher {

    private final List<WorkerTaskHandler> handlers;

    @Inject
    public WorkerDispatcher(Collection<WorkerTaskHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public <S extends Session<U>, U extends User> WorkerTaskResult dispatch(
        WorkerContext<S, U> context
    ) {
        return requireHandler(context.workerTask().task())
            .execute(context);
    }

    public <S extends Session<U>, U extends User> void cancel(
        WorkerContext<S, U> context
    ) {
        requireHandler(context.workerTask().task()).cancel(context);
    }

    private WorkerTaskHandler requireHandler(Task task) {
        List<WorkerTaskHandler> supported = handlers.stream()
            .filter(handler -> handler.supports(task))
            .toList();
        if (supported.isEmpty()) {
            throw new IllegalStateException(
                "No WorkerTaskHandler registered for Task type " + task.type()
            );
        }
        if (supported.size() > 1) {
            throw new IllegalStateException(
                "Multiple WorkerTaskHandlers registered for Task type "
                    + task.type()
            );
        }
        return supported.getFirst();
    }
}
