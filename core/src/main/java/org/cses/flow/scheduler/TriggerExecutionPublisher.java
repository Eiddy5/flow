package org.cses.flow.scheduler;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.core.utils.SessionUtil;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.infrastructure.repositories.triggers.entries.TriggerRunEntry;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.jooq.DSLContext;
import org.paas.session.SessionFactory;
import org.x9.jooq.JOOQ;

import java.util.Optional;

import static org.flow.gen.flow.Tables.TRIGGER_RUNS;

/** Core 中的触发启动入口，核验持久化快照后通过现有 Executor 命令链路启动。 */
@Singleton
@Requires(condition = FlowJooqCondition.class)
public class TriggerExecutionPublisher {
    private DSLContext dsl;
    private ExecutionService executions;
    private SessionFactory sessionFactory;

    /**
     * 使用 Flow 数据源、启动服务和会话工厂装配触发启动入口，不启动调度器。
     *
     * @param jooq 具名 Flow 数据库访问与映射配置，不能为 null
     * @param executions 现有 Executor 命令发布服务，不能为 null
     * @param sessionFactory 持久化操作者的上下文恢复工厂，不能为 null
     */
    public TriggerExecutionPublisher(@Named("flow") JOOQ jooq, ExecutionService executions,
            SessionFactory sessionFactory) {
        this.dsl = jooq.createDSLContext();
        this.executions = executions;
        this.sessionFactory = sessionFactory;
    }

    /**
     * 核验命中事件与持久化快照后发布 Create；已受理的命中直接返回。
     * 队列受理后才记录 acceptedAt，发布失败时异常传播，调用方可以使用原事件重试。
     * 受理与标记之间中断时仍沿用原 Execution ID，不重新选择 Flow 版本或输入。
     *
     * @param event 可信内部的 TRIGGER_FIRED 事件，不能为 null；不修改其内容
     * @throws IllegalArgumentException 当事件类型错误或与持久化命中不一致时抛出
     * @throws RuntimeException 当上下文恢复、命令发布或受理标记保存失败时抛出
     */
    @SuppressWarnings("unchecked")
    public void send(SchedulerEvent event) {
        if (!"TRIGGER_FIRED".equals(event.kind()))
            throw new IllegalArgumentException("Only trigger occurrences can start an Execution");
        TriggerRunEntry run = dsl.selectFrom(TRIGGER_RUNS)
            .where(TRIGGER_RUNS.COMPANY_ID.eq(event.companyId())).and(TRIGGER_RUNS.ID.eq(event.id()))
            .fetchOneInto(TriggerRunEntry.class);
        if (run == null || !run.to().equals(event))
            throw new IllegalArgumentException("Trigger event does not match its durable occurrence");
        if (run.acceptedAt != null) return;
        var session = SessionUtil.restore(sessionFactory, run.companyId, run.actorId);
        executions.create(session, run.executionId, run.flowKey, Optional.of(run.flowVersion), run.to().inputs());
        dsl.update(TRIGGER_RUNS).set(TRIGGER_RUNS.ACCEPTED_AT, TimeUtil.now())
            .where(TRIGGER_RUNS.COMPANY_ID.eq(run.companyId)).and(TRIGGER_RUNS.ID.eq(run.id)).execute();
    }
}
