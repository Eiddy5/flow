package org.cses.flow.executor.commands;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.executions.Execution;
import org.paas.json.SerializableObject;
import org.paas.session.Device;
import org.paas.session.Session;
import org.paas.session.User;
import org.jooq.DSLContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Requests creation and first drive of one exact Flow Execution.
 */
public final class Create extends SerializableObject
    implements ExecutorCommand {

    private String executionId;
    private String companyId;
    private String flowId;
    private long flowReversion;
    private String sessionId;
    private String actorId;
    private String actorName;
    private String ip;
    private Device device;
    private String deviceId;
    private String appVersion;
    private String osVersion;
    private Map<String, Object> inputs = Map.of();

    @JsonIgnore
    private transient DSLContext dsl;

    public Create() {
    }

    public static Create from(
        Session<? extends User> session,
        Execution execution
    ) {
        return from(session, execution, Map.of());
    }

    public static Create from(
        Session<? extends User> session,
        Execution execution,
        Map<String, ?> inputs
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(execution, "execution");
        if (!execution.companyId().equals(session.getCompanyId())) {
            throw new IllegalArgumentException(
                "Session company must match Execution company"
            );
        }
        ActorRef actor = ActorRef.from(session);

        Create command = new Create();
        command.executionId = execution.id();
        command.companyId = execution.companyId();
        command.flowId = execution.flowId();
        command.flowReversion = execution.flowReversion();
        command.sessionId = session.getId();
        command.actorId = actor.id();
        command.actorName = actor.name().orElse(null);
        command.ip = session.getIp();
        command.device = session.getDevice();
        command.deviceId = session.getDeviceId();
        command.appVersion = session.getAppVersion();
        command.osVersion = session.getOsVersion();
        command.inputs = immutableInputs(inputs);
        command.validate();
        return command;
    }

    @Override
    public Type getType() {
        return Type.CREATE;
    }

    @Override
    public void validate() {
        executionId = requireText(executionId, "Execution id");
        companyId = requireText(companyId, "Company id");
        flowId = requireText(flowId, "Flow id");
        actorId = requireText(actorId, "Actor id");
        if (flowReversion < 1) {
            throw new IllegalArgumentException(
                "Flow reversion must be positive"
            );
        }
        sessionId = normalizeText(sessionId);
        actorName = normalizeText(actorName);
        ip = normalizeText(ip);
        deviceId = normalizeText(deviceId);
        appVersion = normalizeText(appVersion);
        osVersion = normalizeText(osVersion);
        inputs = immutableInputs(inputs);
    }

    public Create inTransaction(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        return this;
    }

    @Override
    @JsonIgnore
    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    @Override
    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String getFlowId() {
        return flowId;
    }

    public void setFlowId(String flowId) {
        this.flowId = flowId;
    }

    public long getFlowReversion() {
        return flowReversion;
    }

    public void setFlowReversion(long flowReversion) {
        this.flowReversion = flowReversion;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    @Override
    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    @Override
    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    @Override
    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
    }

    @Override
    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    @Override
    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    @Override
    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public Map<String, Object> getInputs() {
        return inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public void setInputs(Map<String, Object> inputs) {
        this.inputs = immutableInputs(inputs);
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

    private static Map<String, Object> immutableInputs(
        Map<String, ?> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
            requireText(key, "Flow input key"),
            Objects.requireNonNull(value, "Flow input value")
        ));
        return Map.copyOf(copied);
    }
}
