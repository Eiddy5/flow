package org.cses.flow.executor;

import java.util.Optional;

public interface ExecutorEventHandler<T> {

    Optional<ExecutorContext> handle(T event);
}
