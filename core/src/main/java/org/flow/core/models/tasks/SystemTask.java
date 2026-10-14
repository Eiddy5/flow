package org.flow.core.models.tasks;

/**
 * Marker interface for tasks that require direct access to Flow
 * repositories (e.g. executions, logs, audit logs).
 *
 * <p>
 * Tasks implementing this interface are routed to the reserved
 * worker group {@code "system"} and executed by the SystemWorker,
 * which runs inside a trusted process (webserver / standalone) and
 * has direct database access.
 * </p>
 *
 * <p>
 * Intended for core and Enterprise Edition tasks. Plugin authors
 * should not implement this interface: SystemTasks execute with raw
 * repository access and belong to the engine's reserved worker group.
 * </p>
 */
public interface SystemTask {
}
