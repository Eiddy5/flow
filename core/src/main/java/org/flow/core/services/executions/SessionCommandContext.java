package org.flow.core.services.executions;

import org.flow.core.executor.command.SessionCommand;
import org.flow.core.models.session.ActorRef;
import org.flow.core.utils.SessionUtil;
import org.paas.session.SessionFactory;

import io.micronaut.context.BeanProvider;
import jakarta.inject.Singleton;

/** 后台按宿主的具体 SessionFactory 恢复最小身份，不保存线程级 Session。 */
@Singleton
public class SessionCommandContext {
    private BeanProvider<SessionFactory<?, ?>> factories;

    /** 延迟获取宿主工厂，未使用 Session 命令的原生任务不要求工厂。 */
    public SessionCommandContext(BeanProvider<SessionFactory<?, ?>> factories) {
        this.factories = factories;
    }

    /** 恢复并校验身份；缺失或不唯一的宿主工厂会使该命令明确失败。 */
    public ActorRef restore(SessionCommand command) {
        var session = SessionUtil.restore(factories.get(), command.tenantId(), command.actorId());
        return SessionUtil.user(session);
    }
}
