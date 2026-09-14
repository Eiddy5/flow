package org.cses.flow.executor.commands;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.utils.SessionUtil;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Objects;

/**
 * Requests a new Execution derived from a paused source and historical target.
 * The allocated replay identity is part of the durable command.
 */
public record Rewind(
        String executionId,
        String replayExecutionId,
        String companyId,
        String actorId,
        String sourceTaskRunId,
        String targetTaskRunId,
        String reason
) implements ExecutionCommand {

    /**
     * Validates and normalizes the durable replay request.
     * @param executionId source Execution
     * @param replayExecutionId distinct new Execution ID
     * @param companyId owning tenant
     * @param actorId initiating actor
     * @param sourceTaskRunId paused source occurrence
     * @param targetTaskRunId completed predecessor to replay
     * @param reason nonblank replay reason
     * @throws IllegalArgumentException when a required value or identity is invalid
     */
    public Rewind {
        executionId = requireText(executionId, "Execution id");
        replayExecutionId = requireText(replayExecutionId, "Replay Execution id");
        if (executionId.equals(replayExecutionId)) {
            throw new IllegalArgumentException("Replay requires a new Execution identity");
        }
        companyId = requireText(companyId, "Company id");
        actorId = requireText(actorId, "Actor id");
        sourceTaskRunId = requireText(
                sourceTaskRunId,
                "Rewind source TaskRun id"
        );
        targetTaskRunId = requireText(
                targetTaskRunId,
                "Rewind target TaskRun id"
        );
        reason = requireText(reason, "Rewind reason");
    }

    /**
     * Allocates the new Execution identity once when constructing a durable command.
     * @param session trusted tenant and actor
     * @param executionId source Execution ID
     * @param sourceTaskRunId paused source occurrence
     * @param targetTaskRunId completed predecessor
     * @param reason nonblank reason
     * @return command retaining the same new identity through redelivery
     */
    public static Rewind from(
            Session<? extends User> session,
            String executionId,
            String sourceTaskRunId,
            String targetTaskRunId,
            String reason
    ) {
        return from(session, executionId, StringUtil.newId(), sourceTaskRunId, targetTaskRunId, reason);
    }

    /**
     * 使用宿主预分配 ID 构建可重复投递的回退命令。
     * @param session 可信租户和操作者
     * @param executionId 来源实例 ID
     * @param replayExecutionId 宿主已保存的新实例 ID
     * @param sourceTaskRunId 暂停的源 TaskRun ID
     * @param targetTaskRunId 已完成的目标 TaskRun ID
     * @param reason 非空回退原因
     * @return 保留指定新实例 ID 的命令
     */
    public static Rewind from(
            Session<? extends User> session,
            String executionId,
            String replayExecutionId,
            String sourceTaskRunId,
            String targetTaskRunId,
            String reason
    ) {
        Objects.requireNonNull(session, "Session must not be null");
        ActorRef actor = SessionUtil.user(session);
        return new Rewind(
                executionId,
                replayExecutionId,
                session.getCompanyId(),
                actor.id(),
                sourceTaskRunId,
                targetTaskRunId,
                reason
        );
    }

    @Override
    public Type getType() {
        return Type.REWIND;
    }

    @Override
    public String key() {
        return executionId;
    }

    /** Validates required durable command fields before dispatch. */
    @Override
    public void validate() {
        requireText(executionId, "Execution id");
        requireText(replayExecutionId, "Replay Execution id");
        requireText(companyId, "Company id");
        requireText(actorId, "Actor id");
        requireText(sourceTaskRunId, "Rewind source TaskRun id");
        requireText(targetTaskRunId, "Rewind target TaskRun id");
        requireText(reason, "Rewind reason");
    }

    public String getExecutionId() {
        return executionId;
    }

    /** @return preallocated Execution identity retained across queue redelivery */
    public String getReplayExecutionId() {
        return replayExecutionId;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getActorId() {
        return actorId;
    }

    public String getSourceTaskRunId() {
        return sourceTaskRunId;
    }

    public String getTargetTaskRunId() {
        return targetTaskRunId;
    }

    public String getReason() {
        return reason;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
