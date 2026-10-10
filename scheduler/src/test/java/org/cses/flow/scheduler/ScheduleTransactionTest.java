package org.cses.flow.scheduler;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.StartupEvent;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.scheduler.repositories.ScheduleRepository;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.paas.common.util.StringUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.flow.gen.flow.Tables.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL 事务、数据库故障和排他领取的技术验证，不替代 UC 公开路径。 */
class ScheduleTransactionTest {
    /** 推进失败时命中与 outbox 一起回滚，失败元数据保留原身份，恢复后只启动一次。
     * @throws Exception 数据库故障注入或清理失败时抛出
     */
    @Test
    void failedAdvanceRollsBackOccurrenceAndRetriesSameIdentity() throws Exception {
        try (var fixture = SchedulerTestSupport.open(); var connection = connection()) {
            var scheduler = manualScheduler(fixture);
            Flow flow = fixture.deploy(dates("scheduler-atomic-failure", TimeUtil.now() + 3_000));
            SchedulerTestSupport.awaitScheduleCount(flow, 1);
            var context = DSL.using(connection, SQLDialect.POSTGRES);
            ScheduleData original = plan(context, flow);
            try {
                installFault(connection, flow.companyId(), "RAISE EXCEPTION 'schedule-test-advance-failure';");
                await(() -> TimeUtil.now() >= original.scheduledAt());
                scheduler.poll();
                assertEquals(original, plan(context, flow));
                assertEquals(1, context.select(TRIGGER_SCHEDULES.ATTEMPTS).from(TRIGGER_SCHEDULES)
                    .where(TRIGGER_SCHEDULES.COMPANY_ID.eq(flow.companyId())).fetchOne(TRIGGER_SCHEDULES.ATTEMPTS));
                assertNotNull(context.select(TRIGGER_SCHEDULES.LAST_FAILURE_AT).from(TRIGGER_SCHEDULES)
                    .where(TRIGGER_SCHEDULES.COMPANY_ID.eq(flow.companyId())).fetchOne(TRIGGER_SCHEDULES.LAST_FAILURE_AT));
                assertNoOccurrence(context, flow);
                assertTrue(fixture.executionService().executions(fixture.session()).isEmpty());
            } finally {
                removeFault(connection);
            }
            finishRetry(fixture, scheduler, context, flow, original);
        }
    }

    /** 真实命中事务在推进前被终止连接，未提交事实消失，行锁释放后同身份可再次完成。
     * @throws Exception 数据库操作、并发等待或清理失败时抛出
     */
    @Test
    void terminatedDatabaseConnectionReleasesClaimAndRollsBackOccurrence() throws Exception {
        try (var fixture = SchedulerTestSupport.open(); var control = connection();
             var executor = Executors.newSingleThreadExecutor()) {
            var scheduler = manualScheduler(fixture);
            Flow flow = fixture.deploy(dates("scheduler-connection-loss", TimeUtil.now() + 3_000));
            SchedulerTestSupport.awaitScheduleCount(flow, 1);
            var context = DSL.using(control, SQLDialect.POSTGRES);
            ScheduleData original = plan(context, flow);
            int controllerPid = context.fetchOne("select pg_backend_pid()").get(0, Integer.class);
            context.execute("select pg_advisory_lock(hashtextextended(?, 17))", flow.companyId());
            try {
                installFault(control, flow.companyId(),
                    "PERFORM pg_advisory_xact_lock(hashtextextended(OLD.company_id, 17));");
                await(() -> TimeUtil.now() >= original.scheduledAt());
                var polling = executor.submit(scheduler::poll);
                await(() -> blockedPid(context, controllerPid) != null);
                int failedPid = blockedPid(context, controllerPid);
                assertEquals(Boolean.TRUE, context.fetchOne("select pg_terminate_backend(?)", failedPid).get(0, Boolean.class));
                polling.get(10, TimeUnit.SECONDS);
                assertEquals(original, plan(context, flow));
                assertNoOccurrence(context, flow);
                assertTrue(fixture.executionService().executions(fixture.session()).isEmpty());
            } finally {
                context.execute("select pg_advisory_unlock(hashtextextended(?, 17))", flow.companyId());
                removeFault(control);
            }
            finishRetry(fixture, scheduler, context, flow, original);
        }
    }

    /** 两连接分别领取不同到期行，第三连接不等待；回滚首连接后原身份立即可以再次领取。
     * @throws Exception 数据库连接或事务操作失败时抛出
     */
    @Test
    void lockedRowsAreSkippedAndRollbackMakesOriginalOccurrenceAvailable() throws Exception {
        var repository = new ScheduleRepository();
        String company = StringUtil.newId();
        ScheduleData first = data(company, StringUtil.newId());
        ScheduleData second = data(company, StringUtil.newId());
        try (var setup = connection(); var one = connection(); var two = connection(); var three = connection()) {
            var shared = DSL.using(setup, SQLDialect.POSTGRES);
            repository.insert(shared, first);
            repository.insert(shared, second);
            one.setAutoCommit(false); two.setAutoCommit(false); three.setAutoCommit(false);
            try {
                var selected = repository.lockNext(DSL.using(one, SQLDialect.POSTGRES), TimeUtil.now()).orElseThrow();
                var other = repository.lockNext(DSL.using(two, SQLDialect.POSTGRES), TimeUtil.now()).orElseThrow();
                assertNotEquals(selected.triggerId(), other.triggerId());
                three.createStatement().execute("set local statement_timeout='2s'");
                assertTrue(repository.lockNext(DSL.using(three, SQLDialect.POSTGRES), TimeUtil.now()).isEmpty());
                one.rollback();
                assertEquals(selected, repository.lockNext(DSL.using(three, SQLDialect.POSTGRES), TimeUtil.now()).orElseThrow());
            } finally {
                one.rollback(); two.rollback(); three.rollback();
                repository.removeObsolete(shared, company, "technical-plan", Set.of());
            }
        }
    }

    /** 旧命中推进或失败回写不能覆盖新计划、复活已删除计划，也不能影响同ID的另一租户。
     * @throws Exception 数据库连接或清理失败时抛出
     */
    @Test
    void staleCompletionCannotReplaceOrReviveChangedAndDeletedPlans() throws Exception {
        var repository = new ScheduleRepository();
        String company = StringUtil.newId();
        ScheduleData original = data(company, StringUtil.newId());
        ScheduleData replacement = data(company, original.triggerId());
        ScheduleData otherTenant = data(StringUtil.newId(), original.triggerId());
        try (var connection = connection()) {
            var context = DSL.using(connection, SQLDialect.POSTGRES);
            repository.insert(context, original);
            repository.insert(context, otherTenant);
            try {
                repository.advance(context, original, replacement);
                repository.failed(context, original, TimeUtil.now(), TimeUtil.now() + 1000, "stale");
                assertThrows(IllegalStateException.class, () -> repository.advance(context, original, data(company, original.triggerId())));
                assertEquals(replacement, repository.find(context, company, original.triggerId()).orElseThrow());
                assertEquals(0, context.select(TRIGGER_SCHEDULES.ATTEMPTS).from(TRIGGER_SCHEDULES)
                    .where(TRIGGER_SCHEDULES.COMPANY_ID.eq(company)).fetchOne(TRIGGER_SCHEDULES.ATTEMPTS));
                repository.advance(context, replacement, null);
                repository.failed(context, original, TimeUtil.now(), TimeUtil.now() + 1000, "late");
                assertThrows(IllegalStateException.class, () -> repository.advance(context, replacement, original));
                assertTrue(repository.find(context, company, original.triggerId()).isEmpty());
                assertEquals(otherTenant, repository.find(context, otherTenant.companyId(), otherTenant.triggerId()).orElseThrow());
            } finally {
                repository.removeObsolete(context, company, "technical-plan", Set.of());
                repository.removeObsolete(context, otherTenant.companyId(), "technical-plan", Set.of());
            }
        }
    }

    /**
     * 停止容器的自动轮询，使用相同真实组件构造由测试显式调用的调度器。
     * @param fixture 非空真实应用夹具，不替换其命中与仓储实现
     * @return 已允许领取且重试间隔100毫秒的真实调度器，没有后台测试线程
     */
    private static FlowScheduler manualScheduler(WorkflowUcFixture fixture) {
        fixture.bean(FlowScheduler.class).stop();
        var scheduler = new FlowScheduler(PostgresJooqTestAdapter.fromEnvironment(), fixture.bean(FlowRepository.class),
            fixture.bean(TriggerRuntime.class), fixture.bean(ScheduleRepository.class), Duration.ofMillis(100));
        scheduler.start(new StartupEvent(fixture.bean(ApplicationContext.class)));
        return scheduler;
    }

    /**
     * 真实配置回调遇到同业务key的旧身份计划时，恢复当前定义的计划并保持重复事件幂等。
     * @throws Exception 数据库连接、故障布置或清理失败时抛出
     */
    @Test
    void replacingTriggerIdentityRetainsTheNewPlanForTheSameKey() throws Exception {
        try (var fixture = SchedulerTestSupport.open(); var connection = connection()) {
            var scheduler = fixture.bean(FlowScheduler.class);
            scheduler.stop();
            Flow flow = fixture.deploy(dates("scheduler-replaced-identity", TimeUtil.now() + 60_000));
            SchedulerTestSupport.awaitScheduleCount(flow, 1);
            var repository = fixture.bean(ScheduleRepository.class);
            var context = DSL.using(connection, SQLDialect.POSTGRES);
            ScheduleData original = plan(context, flow);
            ScheduleData stale = ScheduleData.from(flow.companyId(), StringUtil.newId(), original.flowKey(),
                original.triggerKey(), original.schedule(), original.scheduledAt(), StringUtil.newId(), StringUtil.newId());
            context.transaction(configuration -> {
                var transaction = DSL.using(configuration);
                repository.removeObsolete(transaction, flow.companyId(), flow.key(), Set.of());
                repository.insert(transaction, stale);
            });
            assertEquals(stale, repository.find(context, flow.companyId(), stale.triggerId()).orElseThrow());
            assertTrue(repository.find(context, flow.companyId(), original.triggerId()).isEmpty());
            String payload = context.fetchOne("select payload::text from scheduler_events "
                + "where company_id = ? and kind = 'FLOW_CHANGED' order by created_at desc limit 1", flow.companyId())
                .get(0, String.class);
            var event = org.paas.json.JsonObject.Parse(payload).asObject(org.cses.flow.scheduler.events.SchedulerEvent.class);
            scheduler.onEvent(event);
            assertTrue(repository.find(context, flow.companyId(), stale.triggerId()).isEmpty());
            ScheduleData restored = plan(context, flow);
            assertEquals(original.triggerId(), restored.triggerId());
            assertEquals(original.scheduledAt(), restored.scheduledAt());
            assertNotEquals(stale.eventId(), restored.eventId());
            assertEquals(1, context.fetchCount(TRIGGER_SCHEDULES, TRIGGER_SCHEDULES.COMPANY_ID.eq(flow.companyId())));
            scheduler.onEvent(event);
            assertEquals(restored, plan(context, flow));
            assertTrue(fixture.executionService().executions(fixture.session()).isEmpty());
            SchedulerTestSupport.deleteAndAwaitCancellation(fixture, flow);
        }
    }

    /**
     * 故障移除后按持久化可领取时间重试，核对原身份、唯一运行及公开完成结果。
     * @param fixture 非空本场景夹具
     * @param scheduler 没有后台线程的真实调度器
     * @param context 只读检查事实的数据库上下文
     * @param flow 本场景正式定义
     * @param original 首次保存的计划，不修改
     */
    private static void finishRetry(WorkflowUcFixture fixture, FlowScheduler scheduler, DSLContext context,
            Flow flow, ScheduleData original) {
        await(() -> context.select(TRIGGER_SCHEDULES.AVAILABLE_AT).from(TRIGGER_SCHEDULES)
            .where(TRIGGER_SCHEDULES.COMPANY_ID.eq(flow.companyId())).fetchOne(TRIGGER_SCHEDULES.AVAILABLE_AT) <= TimeUtil.now());
        scheduler.poll();
        var executions = SchedulerTestSupport.awaitCount(fixture, 1, TimeUtil.now() + 10_000);
        assertEquals(original.executionId(), executions.getFirst().id());
        SchedulerTestSupport.assertCompleted(fixture, flow, executions);
        assertEquals(1, context.fetchCount(TRIGGER_RUNS, TRIGGER_RUNS.COMPANY_ID.eq(flow.companyId())));
        assertEquals(original.eventId(), context.select(TRIGGER_RUNS.ID).from(TRIGGER_RUNS)
            .where(TRIGGER_RUNS.COMPANY_ID.eq(flow.companyId())).fetchOne(TRIGGER_RUNS.ID));
        assertEquals(1, context.fetchCount(SCHEDULER_EVENTS, SCHEDULER_EVENTS.COMPANY_ID.eq(flow.companyId())
            .and(SCHEDULER_EVENTS.KIND.eq("TRIGGER_FIRED"))));
        scheduler.poll();
        SchedulerTestSupport.assertCountThrough(fixture, 1, TimeUtil.now() + 200);
        SchedulerTestSupport.deleteAndAwaitCancellation(fixture, flow);
        scheduler.stop();
    }

    /**
     * 验证当前租户没有部分提交的命中或命中事件。
     * @param context 真实数据库上下文
     * @param flow 当前租户的正式定义
     */
    private static void assertNoOccurrence(DSLContext context, Flow flow) {
        assertEquals(0, context.fetchCount(TRIGGER_RUNS, TRIGGER_RUNS.COMPANY_ID.eq(flow.companyId())));
        assertEquals(0, context.fetchCount(SCHEDULER_EVENTS, SCHEDULER_EVENTS.COMPANY_ID.eq(flow.companyId())
            .and(SCHEDULER_EVENTS.KIND.eq("TRIGGER_FIRED"))));
    }

    /**
     * 读取仅有的一条测试计划，不改变计划事实。
     * @param context 真实数据库上下文
     * @param flow 当前正式定义
     * @return 当前完整计划身份
     */
    private static ScheduleData plan(DSLContext context, Flow flow) {
        return new ScheduleRepository().find(context, flow.companyId(), flow.triggers().getFirst().id()).orElseThrow();
    }

    /**
     * 查询被指定控制连接阻塞的生产事务。
     * @param context 控制连接的数据库上下文
     * @param controllerPid 控制连接PID
     * @return 等待锁的PID，没有等待者时为空
     */
    private static Integer blockedPid(DSLContext context, int controllerPid) {
        return context.fetch("select pid from pg_stat_activity where ? = any(pg_blocking_pids(pid))", controllerPid)
            .stream().map(row -> row.get("pid", Integer.class)).findFirst().orElse(null);
    }

    /**
     * 安装只影响给定测试租户的删除故障；调用者必须在finally中移除。
     * @param connection 当前独立测试数据库连接
     * @param company 测试租户，不得传入业务租户
     * @param action 固定测试SQL动作，仅允许测试源码常量
     * @throws Exception 数据库拒绝安装触发器时抛出
     */
    private static void installFault(Connection connection, String company, String action) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("create function flow_test_schedule_fault() returns trigger language plpgsql as $$ begin "
                + "if OLD.company_id = TG_ARGV[0] then " + action + " end if; return OLD; end $$");
            statement.execute("create trigger flow_test_schedule_fault before delete on trigger_schedules "
                + "for each row execute function flow_test_schedule_fault(" + DSL.inline(company) + ")");
        }
    }

    /**
     * 删除本测试专属的数据库故障触发器与函数。
     * @param connection 仍可使用的控制连接
     * @throws Exception 数据库拒绝清理时抛出
     */
    private static void removeFault(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("drop trigger if exists flow_test_schedule_fault on trigger_schedules");
            statement.execute("drop function if exists flow_test_schedule_fault()");
        }
    }

    /**
     * 创建只有一个指定日期与一条自动完成步骤的独立定义。
     * @param key 测试业务名称
     * @param due UTC毫秒时间，晚于发布与注册时间
     * @return 完整YAML文本
     */
    private static String dates(String key, long due) {
        return """
            key: %s
            triggers:
              - key: once
                type: org.cses.flow.extensions.triggers.ScheduleOnDatesTrigger
                dates: [%d]
            tasks:
              - key: finish
                type: org.cses.flow.extensions.log.Log
                message: completed
            """.formatted(key, due);
    }

    /**
     * 为技术仓储测试创建已到期的独立命中坐标。
     * @param company 非空测试租户
     * @param trigger 非空触发器身份，允许跨租户复用以验证隔离
     * @return 每次分配新eventId和executionId的完整坐标
     */
    private static ScheduleData data(String company, String trigger) {
        return ScheduleData.from(company, trigger, "technical-plan", trigger, "technical", 1,
            StringUtil.newId(), StringUtil.newId());
    }

    /**
     * 连接显式指定的独立PG，不使用宿主业务数据库。
     * @return 调用方必须关闭的真实连接
     * @throws Exception 环境参数缺失或连接失败时抛出
     */
    private static Connection connection() throws Exception {
        return DriverManager.getConnection(System.getenv("FLOW_POSTGRES_TEST_URL"),
            System.getenv("FLOW_POSTGRES_TEST_USER"), System.getenv("FLOW_POSTGRES_TEST_PASSWORD"));
    }

    /**
     * 有界轮询条件，中断或十秒内不成立时失败。
     * @param condition 无副作用的观察条件
     */
    private static void await(BooleanSupplier condition) {
        long deadline = TimeUtil.now() + 10_000;
        while (!condition.getAsBoolean()) {
            assertTrue(TimeUtil.now() < deadline, "技术验证条件未在10秒内成立");
            try { TimeUnit.MILLISECONDS.sleep(20); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }
}
