package org.cses.flow.scheduler;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.ScheduledExecutionsFilter;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.helper.CustomTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.core.utils.SessionUtil;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.extensions.triggers.ScheduleTrigger;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.infrastructure.repositories.triggers.entries.SchedulerEventEntry;
import org.cses.flow.infrastructure.repositories.triggers.entries.TriggerRunEntry;
import org.cses.flow.queues.Queue;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.paas.session.SessionFactory;
import org.x9.jooq.JOOQ;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.flow.gen.flow.Tables.SCHEDULER_EVENTS;
import static org.flow.gen.flow.Tables.TRIGGER_RUNS;

/** 持久化定时扫描、事件投递和 Flow 启动之间的应用边界。 */
@Context
@Requires(condition = FlowJooqCondition.class)
@Requires(property = "flow.scheduler.enabled", notEquals = "false", defaultValue = "true")
public class FlowScheduler {
    Logger logger = LoggerFactory.getLogger(FlowScheduler.class);
    DSLContext dsl;
    FlowRepository flows;
    Queue<SchedulerEvent> queue;
    ExecutionService executions;
    SessionFactory sessionFactory;
    Scheduler scheduler;
    CustomTask<ScheduleData> triggerTask;

    /**
     * 连接现有 Flow 数据源与消息队列，注册两个持久化任务。
     *
     * @param dataSource Flow 数据源
     *
     * @param jooq Flow 的统一映射配置
     *
     * @param flows Flow 版本读取
     *
     * @param queue 内部事件队列
     *
     * @param executions 现有启动入口
     *
     * @param sessionFactory 异步上下文恢复
     *
     * @param pollInterval 调度与 outbox 轮询周期
     */
    public FlowScheduler(@Named("flow") DataSource dataSource, @Named("flow") JOOQ jooq, FlowRepository flows,
            Queue<SchedulerEvent> queue, ExecutionService executions, SessionFactory sessionFactory,
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
        this.queue = queue;
        this.executions = executions;
        this.sessionFactory = sessionFactory;
        triggerTask = Tasks.custom("flow-schedule-trigger", ScheduleData.class)
            .onFailure(new FailureHandler.OnFailureRetryLater<>(pollInterval))
            .execute((instance, context) -> (completion, operations) -> {
                ScheduleData data = instance.getData();
                Flow flow = latest(dsl, data.companyId(), data.flowKey()).orElse(null);
                ScheduleTrigger trigger = findTrigger(flow, data.triggerKey());
                if (trigger == null || !trigger.id().equals(instance.getId())) {
                    operations.remove();
                    return;
                }
                if (trigger.cron().equals(data.cron()) && trigger.timezone().equals(data.timezone())) {
                    stage(flow, trigger, data);
                }
                long next = nextTime(trigger);
                operations.reschedule(completion, Instant.ofEpochMilli(next),
                    ScheduleData.next(data.companyId(), data.flowKey(), trigger, next));
            });
        var publisher = Tasks.recurring("flow-scheduler-outbox", FixedDelay.of(pollInterval))
            .onFailure(new FailureHandler.OnFailureRetryLater<>(pollInterval))
            .execute((instance, context) -> publishPending());
        scheduler = Scheduler.create(dataSource, triggerTask)
            .startTasks(publisher).serializer(new SchedulerSerializer()).pollingInterval(pollInterval)
            .threads(2).build();
    }

    /**
     * @param event 应用启动事件；队列 Bean 完成初始化后才开始扫描 */
    @EventListener
    public void start(StartupEvent event) { scheduler.start(); }

    /** 停止接单并等待本机正在执行的调度回调结束。 */
    @PreDestroy
    public void stop() { scheduler.stop(); }

    /**
     * 处理持久化配置变更或命中事件，失败由现有 Queue 重新投递。
     *
     * @param event 可信内部事件
     */
    @FlowQueueListener(subscription = "flow-scheduler-event")
    public void onEvent(SchedulerEvent event) {
        if ("FLOW_CHANGED".equals(event.kind())) {
            synchronize(event);
        } else {
            accept(event);
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
                    if (!(definition instanceof ScheduleTrigger trigger) || trigger.disabled()) continue;
                    active.add(trigger.id());
                    var instance = triggerTask.instance(trigger.id());
                    var stored = scheduler.getScheduledExecution(instance);
                    if (stored.isEmpty()) {
                        long next = nextTime(trigger);
                        scheduler.scheduleIfNotExists(triggerTask.instance(trigger.id(),
                            ScheduleData.next(flow.companyId(), flow.key(), trigger, next)), Instant.ofEpochMilli(next));
                    } else {
                        ScheduleData data = (ScheduleData) stored.get().getData();
                        if (!data.cron().equals(trigger.cron()) || !data.timezone().equals(trigger.timezone())) {
                            long next = nextTime(trigger);
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
    void stage(Flow flow, ScheduleTrigger trigger, ScheduleData data) {
        SchedulerEvent event = SchedulerEvent.from("TRIGGER_FIRED", flow.companyId(), data.eventId(),
            flow.key(), flow.version(), trigger.key(), data.scheduledAt(), data.executionId(),
            flow.updater().id(), flow.bindInputs(trigger.inputs()));
        var run = TriggerRunEntry.from(event);
        var outbox = SchedulerEventEntry.from(event, TimeUtil.now());
        var inserted = DSL.name("inserted_run").as(dsl.insertInto(TRIGGER_RUNS).set(run.toMap())
            .onConflictDoNothing().returning(TRIGGER_RUNS.ID));
        dsl.with(inserted).insertInto(SCHEDULER_EVENTS)
            .columns(SCHEDULER_EVENTS.COMPANY_ID, SCHEDULER_EVENTS.ID, SCHEDULER_EVENTS.KIND,
                SCHEDULER_EVENTS.PAYLOAD, SCHEDULER_EVENTS.CREATED_AT, SCHEDULER_EVENTS.RETRY_AT,
                SCHEDULER_EVENTS.ATTEMPTS)
            .select(dsl.select(DSL.val(outbox.companyId), DSL.val(outbox.id), DSL.val(outbox.kind),
                DSL.val(outbox.payload), DSL.val(outbox.createdAt), DSL.val(outbox.retryAt), DSL.val(0))
                .from(inserted)).execute();
    }

    /** 发布有限批次；仅在 Broker 受理后标记完成，失败保留原事件重试。 */
    void publishPending() {
        long now = TimeUtil.now();
        var pending = dsl.selectFrom(SCHEDULER_EVENTS)
            .where(SCHEDULER_EVENTS.PUBLISHED_AT.isNull()).and(SCHEDULER_EVENTS.RETRY_AT.le(now))
            .orderBy(SCHEDULER_EVENTS.CREATED_AT, SCHEDULER_EVENTS.ID).limit(100)
            .fetchInto(SchedulerEventEntry.class);
        for (var entry : pending) {
            try {
                queue.emit(entry.to());
                dsl.update(SCHEDULER_EVENTS).set(SCHEDULER_EVENTS.PUBLISHED_AT, TimeUtil.now())
                    .set(SCHEDULER_EVENTS.LAST_ERROR, (String) null)
                    .where(SCHEDULER_EVENTS.COMPANY_ID.eq(entry.companyId)).and(SCHEDULER_EVENTS.ID.eq(entry.id)).execute();
            } catch (RuntimeException failure) {
                logger.warn("Scheduler event publication failed: {}", entry.id, failure);
                dsl.update(SCHEDULER_EVENTS).set(SCHEDULER_EVENTS.ATTEMPTS, entry.attempts + 1)
                    .set(SCHEDULER_EVENTS.RETRY_AT, TimeUtil.now() + 1000)
                    .set(SCHEDULER_EVENTS.LAST_ERROR, failure.getClass().getSimpleName())
                    .where(SCHEDULER_EVENTS.COMPANY_ID.eq(entry.companyId)).and(SCHEDULER_EVENTS.ID.eq(entry.id)).execute();
            }
        }
    }

    /**
     * 只使用持久化命中快照启动；ACK 丢失也沿用同一 Execution ID。
     *
     * @param event 队列命中事件
     */
    @SuppressWarnings("unchecked")
    void accept(SchedulerEvent event) {
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
    ScheduleTrigger findTrigger(Flow flow, String key) {
        if (flow == null || flow.deleted()) return null;
        return flow.triggers().stream().filter(trigger -> trigger.key().equals(key) && !trigger.disabled())
            .filter(ScheduleTrigger.class::isInstance).map(ScheduleTrigger.class::cast).findFirst().orElse(null);
    }

    /**
     * @param trigger 已校验定义
     * @return 当前时刻之后的首次计划时间，不追补全部历史周期 */
    long nextTime(ScheduleTrigger trigger) {
        return new CronSchedule(trigger.cron(), ZoneId.of(trigger.timezone()))
            .getNextExecutionTime(ExecutionComplete.simulatedSuccess(Instant.ofEpochMilli(TimeUtil.now()))).toEpochMilli();
    }
}
