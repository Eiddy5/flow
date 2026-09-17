package org.cses.flow.scheduler;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.ScheduledExecutionsFilter;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.core.domains.triggers.ScheduledTrigger;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.x9.jooq.JOOQ;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;


/** 管理定时计划与到点评估，命中投递和执行启动交给 Core。 */
@Context
@Requires(condition = FlowJooqCondition.class)
@Requires(property = "flow.scheduler.enabled", notEquals = "false", defaultValue = "true")
public class FlowScheduler {
    DSLContext dsl;
    FlowRepository flows;
    TriggerRuntime runtime;
    Scheduler scheduler;
    CustomTask<ScheduleData> triggerTask;

    /**
     * 连接现有 Flow 数据源与消息队列，注册持久化定时任务。
     *
     * @param dataSource Flow 数据源
     *
     * @param jooq Flow 的统一映射配置
     *
     * @param flows Flow 版本读取
     *
     * @param runtime Core 通用命中持久化入口
     *
     * @param pollInterval 定时计划轮询周期
     */
    public FlowScheduler(@Named("flow") DataSource dataSource, @Named("flow") JOOQ jooq, FlowRepository flows,
            TriggerRuntime runtime,
            @Value("${flow.scheduler.poll-interval:1s}") Duration pollInterval) {
        if (pollInterval.isZero() || pollInterval.isNegative())
            throw new IllegalArgumentException("Scheduler poll interval must be positive");
        try {
            dataSource = dataSource.unwrap(io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource.class);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Cannot obtain Flow scheduler datasource", failure);
        }
        this.dsl = jooq.createDSLContext();
        this.flows = flows;
        this.runtime = runtime;
        triggerTask = Tasks.custom("flow-schedule-trigger", ScheduleData.class)
            .onFailure(new FailureHandler.OnFailureRetryLater<>(pollInterval))
            .execute((instance, context) -> (completion, operations) -> {
                ScheduleData data = instance.getData();
                Flow flow = latest(dsl, data.companyId(), data.flowKey()).orElse(null);
                ScheduledTrigger trigger = findTrigger(flow, data.triggerKey());
                if (trigger == null || !trigger.id().equals(instance.getId())) {
                    operations.remove();
                    return;
                }
                if (trigger.scheduleKey().equals(data.schedule())) {
                    stage(flow, trigger, data);
                }
                Long next = nextTime(trigger);
                if (next == null) { operations.remove(); return; }
                operations.reschedule(completion, Instant.ofEpochMilli(next),
                    ScheduleData.next(data.companyId(), data.flowKey(), trigger, next));
            });
        scheduler = Scheduler.create(dataSource, triggerTask)
            .serializer(new SchedulerSerializer()).pollingInterval(pollInterval)
            .threads(2).build();
    }

    /**
     * @param event 应用启动事件；队列 Bean 完成初始化后才开始扫描 */
    @EventListener
    public void start(StartupEvent event) { scheduler.start(); }

    /**
     * 在容器销毁队列和数据库资源之前停止调度回调。
     *
     * @param event 应用关闭事件，不读取或修改其内容
     */
    @EventListener
    public void shutdown(ShutdownEvent event) { stop(); }

    /** 停止接单并等待本机回调结束；重复调用由调度器忽略。 */
    @PreDestroy
    public void stop() { scheduler.stop(); }

    /**
     * 处理持久化配置变更，其他事件由独立订阅处理；失败由 Queue 重新投递。
     *
     * @param event 可信内部事件
     */
    @FlowQueueListener(subscription = "flow-schedule-config")
    public void onEvent(SchedulerEvent event) {
        if ("FLOW_CHANGED".equals(event.kind())) {
            synchronize(event);
        }
    }

    /**
     * 在租户与 Flow 范围内串行刷新，重复及乱序事件均读取最新正式版本。
     *
     * @param event 配置变更通知
     */
    void synchronize(SchedulerEvent event) {
        dsl.transaction(configuration -> {
            DSLContext transaction = DSL.using(configuration);
            transaction.select(DSL.field("pg_advisory_xact_lock(hashtextextended({0}, 0))",
                Object.class, DSL.val(event.companyId() + ":" + event.flowKey()))).fetch();
            Flow flow = latest(transaction, event.companyId(), event.flowKey()).orElse(null);
            Set<String> active = new HashSet<>();
            if (flow != null && !flow.deleted()) {
                for (var definition : flow.triggers()) {
                    if (!(definition instanceof ScheduledTrigger trigger) || trigger.disabled()) continue;
                    active.add(trigger.id());
                    var instance = triggerTask.instance(trigger.id());
                    var stored = scheduler.getScheduledExecution(instance);
                    if (stored.isEmpty()) {
                        Long next = nextTime(trigger);
                        if (next == null) continue;
                        scheduler.scheduleIfNotExists(triggerTask.instance(trigger.id(),
                            ScheduleData.next(flow.companyId(), flow.key(), trigger, next)), Instant.ofEpochMilli(next));
                    } else {
                        ScheduleData data = (ScheduleData) stored.get().getData();
                        if (!data.schedule().equals(trigger.scheduleKey())) {
                            Long next = nextTime(trigger);
                            if (next == null) { scheduler.cancel(instance); continue; }
                            if (!scheduler.reschedule(instance, Instant.ofEpochMilli(next),
                                ScheduleData.next(flow.companyId(), flow.key(), trigger, next)))
                                throw new IllegalStateException("Schedule changed concurrently");
                        }
                    }
                }
            }
            // ponytail: 初期按任务类型扫描，触发器规模增大后为租户和 Flow 增加索引投影。
            var obsolete = new java.util.ArrayList<com.github.kagkarlsson.scheduler.task.TaskInstanceId>();
            scheduler.fetchScheduledExecutionsForTask("flow-schedule-trigger", ScheduleData.class,
                ScheduledExecutionsFilter.all(), stored -> {
                    ScheduleData data = stored.getData();
                    if (data.companyId().equals(event.companyId()) && data.flowKey().equals(event.flowKey())
                        && !active.contains(stored.getTaskInstance().getId())) {
                        obsolete.add(stored.getTaskInstance());
                    }
                });
            obsolete.forEach(scheduler::cancel);
        });
    }

    /**
     * 原子保存命中事实与 outbox；回调重试通过稳定身份复用原始事实。
     *
     * @param flow 命中时选定的正式版本
     *
     * @param trigger 当前生效定义
     *
     * @param data 这次计划的稳定坐标
     */
    void stage(Flow flow, ScheduledTrigger trigger, ScheduleData data) {
        SchedulerEvent event = SchedulerEvent.from("TRIGGER_FIRED", flow.companyId(), data.eventId(),
            flow.key(), flow.version(), trigger.key(), data.scheduledAt(), data.executionId(),
            flow.updater().id(), flow.bindInputs(trigger.inputs()));
        runtime.stage(dsl, event);
    }

    /**
     * @param context 数据库上下文
     * @param company 租户
     * @param key Flow 名称
     * @return 最新正式定义 */
    Optional<Flow> latest(DSLContext context, String company, String key) {
        return flows.findLatestByFlowId(context, FlowId.from(company, key));
    }

    /**
     * @param flow 可空正式定义
     * @param key 触发器名称
     * @return 启用的定时定义，不存在则为空 */
    ScheduledTrigger findTrigger(Flow flow, String key) {
        if (flow == null || flow.deleted()) return null;
        return flow.triggers().stream().filter(trigger -> trigger.key().equals(key) && !trigger.disabled())
            .filter(ScheduledTrigger.class::isInstance).map(ScheduledTrigger.class::cast).findFirst().orElse(null);
    }

    /**
     * @param trigger 已校验定义
     * @return 当前时刻之后的首次计划时间，耗尽时为 null；不追补全部历史周期 */
    Long nextTime(ScheduledTrigger trigger) {
        return trigger.nextAt(TimeUtil.now());
    }
}
