package org.flow.worker;

import org.flow.core.models.flows.State;
import org.flow.worker.processors.internals.AbstractWorkerCallable;

import jakarta.inject.Singleton;

@Singleton
public class WorkerSecurityService {

    public State.Type callInSecurityContext(AbstractWorkerCallable callable) {
        return callable.call();
    }
}
