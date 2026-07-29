package org.cses.flow.core.repositories.externaltasks.memory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Requires;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.core.repositories.externaltasks.ExternalTaskRepository;
import org.jooq.DSLContext;
import org.cses.flow.infrastructure.memory.InMemoryTransactionManager;
import org.cses.flow.infrastructure.memory.InMemoryTransactionalResource;

import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only process-local ExternalTask repository. */
@Singleton
@Requires(property = "flow.memory.enabled", value = "true")
public final class InMemoryExternalTaskRepository
    implements ExternalTaskRepository, InMemoryTransactionalResource {

    private final Map<ExternalTaskIdentity, ExternalTask> externalTasks =
        new ConcurrentHashMap<>();

    public InMemoryExternalTaskRepository() {
    }

    @Inject
    public InMemoryExternalTaskRepository(
        InMemoryTransactionManager transactionManager
    ) {
        transactionManager.register(this);
    }

    @Override
    public Optional<ExternalTask> findById(
        DSLContext dsl,
        String companyId,
        String externalTaskId
    ) {
        ExternalTask task = externalTasks.get(
            new ExternalTaskIdentity(companyId, externalTaskId)
        );
        return task == null
            ? Optional.empty()
            : Optional.of(task.copy());
    }

    @Override
    public Optional<ExternalTask> findWaitingByTaskRunId(
        DSLContext dsl,
        String companyId,
        String taskRunId
    ) {
        return externalTasks.values().stream()
            .filter(task -> task.companyId().equals(companyId))
            .filter(task -> task.taskRunId().equals(taskRunId))
            .filter(task -> task.status() == ExternalTaskStatus.WAITING)
            .findFirst()
            .map(ExternalTask::copy);
    }

    @Override
    public List<ExternalTask> findWaiting(
        DSLContext dsl,
        String companyId
    ) {
        return externalTasks.values().stream()
            .filter(task -> task.companyId().equals(companyId))
            .filter(task -> task.status() == ExternalTaskStatus.WAITING)
            .map(ExternalTask::copy)
            .sorted(java.util.Comparator.comparing(ExternalTask::id))
            .toList();
    }

    @Override
    public void save(DSLContext dsl, ExternalTask externalTask) {
        externalTasks.put(
            new ExternalTaskIdentity(
                externalTask.companyId(),
                externalTask.id()
            ),
            externalTask.copy()
        );
    }

    @Override
    public Object snapshot() {
        Map<ExternalTaskIdentity, ExternalTask> snapshot =
            new java.util.HashMap<>();
        externalTasks.forEach((identity, externalTask) ->
            snapshot.put(identity, externalTask.copy())
        );
        return snapshot;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restore(Object snapshot) {
        externalTasks.clear();
        ((Map<ExternalTaskIdentity, ExternalTask>) snapshot)
            .forEach((identity, externalTask) ->
                externalTasks.put(identity, externalTask.copy())
            );
    }

    private static final class ExternalTaskIdentity {

        private final String companyId;
        private final String externalTaskId;

        private ExternalTaskIdentity(
            String companyId,
            String externalTaskId
        ) {
            this.companyId = companyId;
            this.externalTaskId = externalTaskId;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof ExternalTaskIdentity other)) {
                return false;
            }
            return Objects.equals(companyId, other.companyId)
                && Objects.equals(externalTaskId, other.externalTaskId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(companyId, externalTaskId);
        }
    }
}
