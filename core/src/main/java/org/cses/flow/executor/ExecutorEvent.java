package org.cses.flow.executor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.executor.commands.Cancel;
import org.cses.flow.executor.commands.Create;
import org.cses.flow.executor.commands.Resume;
import org.cses.flow.queues.event.DispatchEvent;
import org.jooq.DSLContext;
import org.paas.json.SerializableObject;
import org.paas.session.Device;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Durable hand-off for one Executor state transition.
 *
 * <p>The event contains only the identity and the command-specific facts
 * needed to restore the runtime boundary. The Flow and Execution are always
 * reloaded by the internal handler; an {@link ExecutorContext} never crosses
 * this queue.</p>
 */
public final class ExecutorEvent extends SerializableObject
    implements DispatchEvent {

    public static final String QUEUE_NAME = "flow-executor-event";

    private Type type;
    private String executionId;
    private String companyId;
    private String actorId;
    private String actorName;
    private String sessionId;
    private String ip;
    private Device device;
    private String deviceId;
    private String appVersion;
    private String osVersion;
    private String taskRunId;
    private Map<String, Object> outputs = Map.of();

    @JsonIgnore
    private transient DSLContext dsl;

    public ExecutorEvent() {
    }

    public static ExecutorEvent from(
        Create command,
        Execution execution,
        Flow flow
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(flow, "flow");
        if (!flow.companyId().equals(command.getCompanyId())
            || !flow.key().equals(command.getFlowKey())
            || flow.reversion() != command.getFlowVersion()
            || !execution.companyId().equals(flow.companyId())
            || !execution.flowId().equals(flow.id())
            || execution.flowReversion() != flow.reversion()) {
            throw new IllegalArgumentException(
                "Create event does not belong to the resolved Flow"
            );
        }
        ActorRef actor = flow.creator();
        ExecutorEvent event = new ExecutorEvent();
        event.type = Type.PROCESS;
        event.executionId = execution.id();
        event.companyId = flow.companyId();
        event.actorId = actor.id();
        event.actorName = actor.name().orElse(null);
        event.validate();
        return event;
    }

    /**
     * Creates an internal start event for an already persisted pending
     * Execution. This path is separate from the public Create command because
     * Create is reserved for materializing a new Execution in its consumer.
     */
    public static ExecutorEvent from(
        Session<? extends User> session,
        Execution execution
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(execution, "execution");
        if (!execution.companyId().equals(session.getCompanyId())) {
            throw new IllegalArgumentException(
                "Session company must match Execution company"
            );
        }
        ActorRef actor = ActorRef.from(session);
        ExecutorEvent event = new ExecutorEvent();
        event.type = Type.PROCESS;
        event.executionId = execution.id();
        event.companyId = execution.companyId();
        event.actorId = actor.id();
        event.actorName = actor.name().orElse(null);
        event.sessionId = session.getId();
        event.ip = session.getIp();
        event.device = session.getDevice();
        event.deviceId = session.getDeviceId();
        event.appVersion = session.getAppVersion();
        event.osVersion = session.getOsVersion();
        event.validate();
        return event;
    }

    public static ExecutorEvent from(
        Resume command,
        Map<String, ?> normalizedOutputs
    ) {
        Objects.requireNonNull(command, "command");
        ExecutorEvent event = new ExecutorEvent();
        event.type = Type.RESUME;
        event.executionId = command.getExecutionId();
        event.companyId = command.getCompanyId();
        event.actorId = command.getActorId();
        event.taskRunId = command.getTaskRunId();
        event.outputs = immutableOutputs(normalizedOutputs);
        event.validate();
        return event;
    }

    public static ExecutorEvent from(Cancel command) {
        Objects.requireNonNull(command, "command");
        ExecutorEvent event = new ExecutorEvent();
        event.type = Type.CANCEL;
        event.executionId = command.getExecutionId();
        event.companyId = command.getCompanyId();
        event.actorId = command.getActorId();
        event.validate();
        return event;
    }

    /**
     * Creates the next internal scheduling event while preserving the
     * initiating identity used by Worker execution and audit writes.
     */
    public ExecutorEvent nextProcess() {
        validate();
        ExecutorEvent next = new ExecutorEvent();
        next.type = Type.PROCESS;
        next.executionId = executionId;
        next.companyId = companyId;
        next.actorId = actorId;
        next.actorName = actorName;
        next.sessionId = sessionId;
        next.ip = ip;
        next.device = device;
        next.deviceId = deviceId;
        next.appVersion = appVersion;
        next.osVersion = osVersion;
        next.validate();
        return next;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    @Override
    public String key() {
        return executionId;
    }

    @Override
    @JsonIgnore
    public DSLContext dsl() {
        return dsl;
    }

    public ExecutorEvent inTransaction(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        return this;
    }

    public void validate() {
        type = Objects.requireNonNull(type, "Executor event type");
        executionId = requireText(executionId, "Execution id");
        companyId = requireText(companyId, "Company id");
        actorId = requireText(actorId, "Actor id");
        actorName = normalizeText(actorName);
        sessionId = normalizeText(sessionId);
        ip = normalizeText(ip);
        deviceId = normalizeText(deviceId);
        appVersion = normalizeText(appVersion);
        osVersion = normalizeText(osVersion);
        if (type == Type.RESUME) {
            taskRunId = requireText(taskRunId, "TaskRun id");
            outputs = immutableOutputs(outputs);
        } else {
            taskRunId = normalizeText(taskRunId);
            outputs = Map.of();
        }
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public String getTaskRunId() {
        return taskRunId;
    }

    public void setTaskRunId(String taskRunId) {
        this.taskRunId = taskRunId;
    }

    public Map<String, Object> getOutputs() {
        return outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    public void setOutputs(Map<String, Object> outputs) {
        this.outputs = immutableOutputs(outputs);
    }

    public enum Type {
        PROCESS,
        RESUME,
        CANCEL
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> immutableOutputs(
        Map<String, ?> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
            requireText(key, "Resume output key"),
            Objects.requireNonNull(value, "Resume output value")
        ));
        return Map.copyOf(copied);
    }
}
