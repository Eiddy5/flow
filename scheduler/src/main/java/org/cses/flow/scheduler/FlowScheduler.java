package org.cses.flow.scheduler;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.triggers.ScheduledTrigger;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.cses.flow.scheduler.repositories.ScheduleRepository;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.x9.jooq.JOOQ;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** 管理持久化计划；每次领取、命中与推进使用同一 PostgreSQL 短事务。 */
@Context
@Requires(condition = FlowJooqCondition.class)
@Requires(property = "flow.scheduler.enabled", notEquals = "false", defaultValue = "true")
public class FlowScheduler {
    private static Logger log = LoggerFactory.getLogger(FlowScheduler.class);
    private DSLContext dsl;
    private FlowRepository flows;
    private TriggerRuntime runtime;
    private ScheduleRepository schedules;
    private long retryDelayMillis;
    private boolean running;

    /**
     * 连接 Flow 计划仓储与命中持久化入口；不启动通用任务线程池。
     * @param jooq 具名 Flow 数据库
     * @param flows 正式定义查询
     * @param runtime Core 命中和 outbox 持久化入口
     * @param schedules 调度计划仓储
     * @param pollInterval 正数轮询间隔，也用作失败后的重试间隔
     * @throws IllegalArgumentException 间隔不足一毫秒时抛出
     */
    public FlowScheduler(@Named("flow") JOOQ jooq, FlowRepository flows, TriggerRuntime runtime,
            ScheduleRepository schedules,
            @Value("${flow.scheduler.poll-interval:1s}") Duration pollInterval) {
        if (pollInterval.toMillis() <= 0) throw new IllegalArgumentException("Scheduler poll interval must be positive");
        this.dsl = jooq.createDSLContext();
        this.flows = flows;
        this.runtime = runtime;
        this.schedules = schedules;
        this.retryDelayMillis = pollInterval.toMillis();
    }

    /** @param event 容器已完成启动通知；允许定时扫描领取计划 */
    @EventListener
    public synchronized void start(StartupEvent event) { running = true; }

    /** @param event 容器关闭通知；等待正在执行的扫描退出后停止领取 */
    @EventListener
    public void shutdown(ShutdownEvent event) { stop(); }

    /** 等待本机当前扫描退出并停止领取；重复调用无副作用。 */
    @PreDestroy
    public synchronized void stop() { running = false; }

    /**
     * 每轮最多处理32条到期计划，每条单独提交；行锁跳过其他节点正在处理的计划。
     * 数据库异常使当前事务回滚，原命中身份在下一轮继续使用。
     */
    @Scheduled(fixedDelay = "${flow.scheduler.poll-interval:1s}", initialDelay = "${flow.scheduler.poll-interval:1s}")
    public synchronized void poll() {
        if (!running) return;
        for (int index = 0; index < 32; index++) {
            var selected = new AtomicReference<ScheduleData>();
            try {
                boolean processed = dsl.transactionResult(configuration -> {
                    var context = DSL.using(configuration);
                    var next = schedules.lockNext(context, TimeUtil.now());
                    if (next.isEmpty()) return false;
                    ScheduleData data = next.orElseThrow();
                    selected.set(data);
                    process(context, data);
                    return true;
                });
                if (!processed) return;
            } catch (RuntimeException failure) {
                ScheduleData data = selected.get();
                log.warn("Scheduled occurrence failed; persisted identity retained", failure);
                if (data == null) return;
                long now = TimeUtil.now();
                try {
                    schedules.failed(dsl, data, now, now + retryDelayMillis, failure.getClass().getSimpleName());
                } catch (RuntimeException unavailable) {
                    log.warn("Cannot postpone schedule; next poll will retry original occurrence", unavailable);
                    return;
                }
            }
        }
    }

    /**
     * 读取当前正式定义，在已领取事务中同时保存命中和下一次计划。
     * @param context 持有该计划行锁的数据库事务
     * @param data 已领取的稳定命中坐标
     */
    private void process(DSLContext context, ScheduleData data) {
        Flow flow = latest(context, data.companyId(), data.flowKey()).orElse(null);
        ScheduledTrigger trigger = findTrigger(flow, data.triggerKey());
        if (trigger == null || !trigger.id().equals(data.triggerId())) {
            schedules.advance(context, data, null);
            return;
        }
        if (trigger.scheduleKey().equals(data.schedule())) {
            SchedulerEvent event = SchedulerEvent.from("TRIGGER_FIRED", flow.companyId(), data.eventId(),
                flow.key(), flow.version(), trigger.key(), data.scheduledAt(), data.executionId(),
                flow.updater().id(), flow.bindInputs(trigger.inputs()));
            runtime.stage(context, event);
        }
        Long next = trigger.nextAt(TimeUtil.now());
        schedules.advance(context, data, next == null ? null
            : ScheduleData.next(data.companyId(), data.flowKey(), trigger, next));
    }

    /**
     * 配置通知只提示重新读取最新正式版本，重复及乱序消息不恢复历史配置。
     * @param event 可信的内部持久化事件；其他事件由 Core 订阅处理
     */
    @FlowQueueListener(subscription = "flow-schedule-config")
    public void onEvent(SchedulerEvent event) {
        if ("FLOW_CHANGED".equals(event.kind())) synchronize(event);
    }

    /**
     * 按租户和流程串行同步全部计划；行锁协调到期扫描，禁用或移除同时删除未来计划。
     * @param event 非空配置变更通知，仅使用租户和流程键定位最新定义
     */
    void synchronize(SchedulerEvent event) {
        dsl.transaction(configuration -> {
            DSLContext context = DSL.using(configuration);
            context.select(DSL.field("pg_advisory_xact_lock(hashtextextended({0}, 0))",
                Object.class, DSL.val(event.companyId() + ":" + event.flowKey()))).fetch();
            Flow flow = latest(context, event.companyId(), event.flowKey()).orElse(null);
            var active = new HashSet<String>();
            var triggers = new java.util.ArrayList<ScheduledTrigger>();
            if (flow != null && !flow.deleted()) {
                for (var definition : flow.triggers()) {
                    if (!(definition instanceof ScheduledTrigger trigger) || trigger.disabled()) continue;
                    active.add(trigger.id());
                    triggers.add(trigger);
                }
            }
            schedules.removeObsolete(context, event.companyId(), event.flowKey(), active);
            for (ScheduledTrigger trigger : triggers) {
                var stored = schedules.find(context, flow.companyId(), trigger.id());
                if (stored.isPresent() && stored.orElseThrow().schedule().equals(trigger.scheduleKey())) continue;
                Long next = trigger.nextAt(TimeUtil.now());
                ScheduleData data = next == null ? null
                    : ScheduleData.next(flow.companyId(), flow.key(), trigger, next);
                if (stored.isPresent()) schedules.advance(context, stored.orElseThrow(), data);
                else if (data != null) schedules.insert(context, data);
            }
        });
    }

    /**
     * 查询最新正式定义。
     * @param context 当前事务上下文
     * @param company 非空租户
     * @param key 非空流程键
     * @return 最新正式定义；不存在时为空
     */
    private Optional<Flow> latest(DSLContext context, String company, String key) {
        return flows.findLatestByFlowId(context, FlowId.from(company, key));
    }

    /**
     * 查询当前有效的时间触发器。
     * @param flow 可空正式定义，已删除或不存在时视为没有触发器
     * @param key 原计划中的触发器键
     * @return 启用的定时定义；不存在时为 null
     */
    private ScheduledTrigger findTrigger(Flow flow, String key) {
        if (flow == null || flow.deleted()) return null;
        return flow.triggers().stream().filter(trigger -> trigger.key().equals(key) && !trigger.disabled())
            .filter(ScheduledTrigger.class::isInstance).map(ScheduledTrigger.class::cast).findFirst().orElse(null);
    }
}
