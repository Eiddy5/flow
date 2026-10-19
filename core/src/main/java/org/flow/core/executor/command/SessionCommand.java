package org.flow.core.executor.command;

import java.time.Instant;
import java.util.Objects;

import org.flow.core.events.EventId;
import org.flow.core.utils.SessionUtil;
import org.paas.session.Session;
import org.paas.session.SessionUser;

/** 对外命令的身份信封；只持久化租户和操作者标识，不保存 Session 或凭证。 */
public record SessionCommand(ExecutionCommand command, String actorId) implements ExecutionCommand {
    /** 校验信封结构，拒绝嵌套信封及缺失身份。 */
    public SessionCommand {
        Objects.requireNonNull(command, "command 不能为空");
        if (command instanceof SessionCommand) {
            throw new IllegalArgumentException("不允许嵌套 SessionCommand");
        }
        SessionUtil.required(command.tenantId(), "tenantId");
        actorId = SessionUtil.required(actorId, "actorId");
    }

    /** 从已验证的宿主会话提取身份，并确认命令属于同一租户。 */
    public static <S extends Session<U>, U extends SessionUser> SessionCommand from(S session, ExecutionCommand command) {
        var actor = SessionUtil.user(session);
        if (!SessionUtil.company(session).equals(command.tenantId())) {
            throw new IllegalArgumentException("命令租户与 Session 不一致");
        }
        return new SessionCommand(command, actor.id());
    }

    /** 返回内部命令的租户，保证队列分区规则不变。 */
    @Override
    public String tenantId() { return command.tenantId(); }

    /** 返回内部命令的命名空间。 */
    @Override
    public String namespace() { return command.namespace(); }

    /** 返回内部命令的流程标识。 */
    @Override
    public String flowId() { return command.flowId(); }

    /** 返回内部命令的执行标识。 */
    @Override
    public String executionId() { return command.executionId(); }

    /** 沿用原生命令时间协议。 */
    @Override
    public Instant timestamp() { return command.timestamp(); }

    /** 保留原事件标识以便追踪和去重。 */
    @Override
    public EventId eventId() { return command.eventId(); }

    /** 保留异步操作标识。 */
    @Override
    public String operationId() { return command.operationId(); }
}
