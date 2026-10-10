package org.cses.flow.core.services.executions;

import com.sun.net.httpserver.HttpServer;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.executor.commands.ExecutionCommand;
import org.cses.flow.executor.commands.Resume;
import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.paas.session.Session;
import org.paas.session.User;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 已确认运行语义的独立技术压力入口。真实 Micronaut、PostgreSQL、Pulsar 和 Worker；
 * 只替代验收范围外的业务 HTTP 服务，不修改领域状态。默认不执行长时间压力。
 */
@EnabledIfEnvironmentVariable(named = "FLOW_STRESS_ENABLED", matches = "true")
class RuntimeStressTest {
    private static String TASK = RetryBusinessTask.class.getName();
    private static List<String> KINDS = List.of("short", "composite", "pause", "race", "retry", "exhaust", "retry-cancel", "subflow", "replay");
    private Path directory;
    private List<Sample> samples = new CopyOnWriteArrayList<>();
    private Map<String, Flow> definitions = new LinkedHashMap<>();
    private List<Session<User>> tenants = new ArrayList<>();
    private List<WorkflowUcFixture> servers = new ArrayList<>();
    private AtomicBoolean stop = new AtomicBoolean();
    private AtomicInteger index = new AtomicInteger();
    private Business business;
    private long loadStartedAt;
    private long loadEndedAt;
    private long admissionClosedAt;
    private long loadDurationMillis;
    private long drainedAt;
    private String stageFailure = "";
    private String datasourceMode = System.getenv().getOrDefault("FLOW_STRESS_DATASOURCE", "pooled");
    private List<String> selectedKinds = List.of(System.getenv().getOrDefault("FLOW_STRESS_KINDS", String.join(",", KINDS)).split(","));
    private Map<String, Execution> terminalSnapshots = new ConcurrentHashMap<>();
    private java.util.concurrent.ExecutorService racePool;

    /**
     * 按显式环境配置启动有限并发或持续压力；任何非预期失败停止继续发起，保留已受理工作现场。
     * @throws Exception 环境装配、文件写入或测试失败时抛出，原始样本和现场仍保存
     */
    @Test
    void sustainedMixedWorkloadsPreserveRuntimeClosure() throws Exception {
        int concurrency = positive("FLOW_STRESS_CONCURRENCY", 1);
        int count = positive("FLOW_STRESS_COUNT", 64);
        int durationSeconds = Integer.parseInt(System.getenv().getOrDefault("FLOW_STRESS_SECONDS", "0"));
        int contexts = positive("FLOW_STRESS_CONTEXTS", 2);
        int tenantCount = positive("FLOW_STRESS_TENANTS", 4);
        String phase = System.getenv().getOrDefault("FLOW_VALIDATION_PHASE", "manual");
        directory = Path.of(System.getenv().getOrDefault("FLOW_STRESS_OUTPUT", "build/runtime-stress"));
        Files.createDirectories(directory);
        verifyLocalTargets();
        Files.writeString(directory.resolve("samples.csv"), "index,kind,company,execution,replacement,accepted,terminal,expectedFailure,canceled,timeout,startedAt,acceptedAt,terminalAt,terminalState,elapsedMillis,journeyMillis,acceptMillis,commandsAccepted,commandsRejected,error\n");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        long started = System.nanoTime();
        try (Business external = new Business(); var pool = Executors.newFixedThreadPool(concurrency);
             var races = Executors.newCachedThreadPool()) {
            business = external;
            racePool = races;
            assertTrue(!selectedKinds.isEmpty() && KINDS.containsAll(selectedKinds), "FLOW_STRESS_KINDS 包含未知场景");
            for (int i = 0; i < contexts; i++) servers.add(openServer());
            WorkflowUcFixture owner = servers.getFirst();
            for (int i = 0; i < tenantCount; i++) tenants.add(owner.sessionFor("stress-" + i));
            for (Session<User> session : tenants) deployDefinitions(owner, session);
            snapshot("before");
            brokerSnapshot("before");
            started = System.nanoTime();
            loadStartedAt = TimeUtil.now();
            jvmSnapshot("before");
            long deadline = durationSeconds == 0 ? Long.MAX_VALUE : started + TimeUnit.SECONDS.toNanos(durationSeconds);
            List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
            for (int worker = 0; worker < concurrency; worker++) {
                workers.add(pool.submit(() -> {
                    while (!stop.get() && System.nanoTime() < deadline) {
                        int next = index.getAndIncrement();
                        if (durationSeconds == 0 && next >= count) break;
                        runSample(next);
                    }
                }));
            }
            for (var worker : workers) worker.get(Math.max(300, durationSeconds + 180L), TimeUnit.SECONDS);
            loadEndedAt = TimeUtil.now();
            loadDurationMillis = (System.nanoTime() - started) / 1_000_000L;
            if (admissionClosedAt == 0) admissionClosedAt = loadEndedAt;
            jvmSnapshot("after");
            brokerSnapshot("after-requests");
            if (!stop.get()) {
                awaitDrained();
                verifyAfterDrain();
                drainedAt = TimeUtil.now();
                brokerSnapshot("drained");
            }
            snapshot("after-requests");
            writeSummary(phase, concurrency, contexts, loadDurationMillis);
            assertEquals(0, samples.stream().filter(s -> !s.error.isEmpty()).count(), "非预期结果见 " + directory);
            assertEquals(samples.size(), samples.stream().filter(s -> s.accepted && s.terminal).count());
            assertEquals(0, activeExecutions(), "终态收尾后仍有活动实例");
        } catch (Exception | AssertionError failure) {
            stageFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            Files.writeString(directory.resolve("stage-failure.txt"), stageFailure);
            throw failure;
        } finally {
            try {
                if (business != null) business.write(directory.resolve("business-calls.csv"));
                if (!servers.isEmpty()) { snapshot("before-close"); brokerSnapshot("before-close"); }
            } finally {
                try {
                    writeSummary(phase, concurrency, contexts, loadDurationMillis);
                } finally {
                    for (int i = servers.size() - 1; i >= 0; i--) servers.get(i).close();
                    logger.setLevel(previous);
                }
            }
        }
    }

    /**
     * 运行一个独立用户旅程，记录受理、终态和断言异常；并发失败不继续升高压力。
     * @param number 本阶段从零开始的样本序号，不作为领域 ID
     */
    private void runSample(int number) {
        String selected = System.getenv().getOrDefault("FLOW_STRESS_KIND", "mixed");
        String kind = selected.equals("mixed") ? selectedKinds.get(number % selectedKinds.size()) : selected;
        int placement = datasourceMode.equals("direct") ? number : number + number / selectedKinds.size();
        Session<User> session = tenants.get(placement % tenants.size());
        WorkflowUcFixture fixture = servers.get(placement % servers.size());
        Sample sample = new Sample();
        sample.index = number;
        sample.kind = kind;
        sample.expectedFailure = kind.equals("exhaust");
        sample.company = session.getCompanyId();
        long start = System.nanoTime();
        sample.startedAt = TimeUtil.now();
        sample.startedNanos = start;
        synchronized (this) { admissionClosedAt = Math.max(admissionClosedAt, sample.startedAt); }
        String token = "sample-" + number + "-" + session.getCompanyId();
        try {
            Flow flow = definitions.get(session.getCompanyId() + ":" + kind);
            sample.execution = fixture.executionService().create(session, flow.key(), Optional.of(flow.version()), Map.of("token", token)).getExecutionId();
            sample.acceptMillis = (System.nanoTime() - start) / 1_000_000L;
            sample.accepted = true;
            sample.acceptedAt = TimeUtil.now();
            Execution terminal;
            switch (kind) {
                case "pause" -> terminal = completePause(fixture, session, flow, sample, token, false);
                case "race" -> terminal = completePause(fixture, session, flow, sample, token, true);
                case "retry-cancel" -> terminal = cancelRetry(fixture, session, flow, sample);
                case "subflow" -> terminal = completeSubFlow(fixture, session, flow, sample, token);
                case "replay" -> terminal = completeReplay(fixture, session, flow, sample, token);
                default -> terminal = await(fixture, session, sample.execution, Execution::isTerminal);
            }
            recordTerminal(sample, terminal);
            terminalSnapshots.put(terminal.id(), terminal);
            sample.canceled = terminal.state().is(State.Type.KILLED);
            sample.expectedFailure = kind.equals("exhaust");
            if (sample.expectedFailure) assertEquals(State.Type.FAILED, terminal.state().current());
            else if (!kind.equals("race") && !kind.equals("retry-cancel")) assertEquals(State.Type.SUCCESS, terminal.state().current());
            assertTrue(terminal.activeTaskRuns().isEmpty());
            assertTrue(terminal.pausedTaskRuns().isEmpty());
            assertTrue(terminal.taskRuns().stream().allMatch(t -> t.nextRetryAt() == null));
            assertEquals(token, terminal.inputs().get("token"));
            assertEquals(flow.version(), terminal.flowVersion());
            for (Session<User> other : tenants) if (!other.getCompanyId().equals(session.getCompanyId())) {
                assertTrue(fixture.executionService().execution(other, terminal.id()).isEmpty(), "跨租户读到实例");
            }
            verifyCalls(fixture, session, terminal, token);
            if (kind.equals("short")) {
                assertEquals(3, terminal.taskRuns().size());
                assertEquals(flow.tasks().stream().map(t -> t.id()).toList(), terminal.taskRuns().stream().map(TaskRun::taskId).toList());
            }
            if (kind.equals("composite")) {
                assertEquals(3, runs(terminal, flow, "iteration").size());
                assertEquals(List.of(1, 2, 3), runs(terminal, flow, "iteration").stream().map(t -> t.iteration().orElseThrow()).toList());
                assertEquals(1, runs(terminal, flow, "after").size());
                assertEquals(State.Type.SKIPPED, only(terminal, flow, "miss").state().current());
                assertTrue(runs(terminal, flow, "never").isEmpty());
                assertEquals(1, runs(terminal, flow, "until-step").size());
            }
            if (kind.equals("retry") || kind.equals("exhaust")) {
                TaskRun target = only(terminal, flow, "business");
                assertEquals(2, target.attempts().size());
                assertEquals(State.Type.FAILED, target.attempts().getFirst().state());
                assertEquals(kind.equals("retry") ? State.Type.SUCCESS : State.Type.FAILED, target.attempts().getLast().state());
                assertEquals(kind.equals("retry") ? 1 : 0, runs(terminal, flow, "after").size());
                assertEquals(1, runs(terminal, flow, "before").size());
            }
        } catch (Throwable failure) {
            sample.error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            sample.timeout = failure instanceof java.util.concurrent.TimeoutException || failure.getMessage() != null && failure.getMessage().toLowerCase().contains("timeout");
            stop.set(true);
            try {
                if (sample.accepted) {
                    var current = fixture.executionService().execution(session, sample.replacement.isEmpty() ? sample.execution : sample.replacement);
                    if (current.isPresent() && current.orElseThrow().isTerminal()) recordTerminal(sample, current.orElseThrow());
                }
            } catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            try {
                java.io.StringWriter stack = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(stack));
                Files.writeString(directory.resolve("failure-" + number + ".txt"), stack.toString());
            } catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
        } finally {
            sample.journeyMillis = (System.nanoTime() - start) / 1_000_000L;
            if (sample.elapsedMillis == 0) sample.elapsedMillis = sample.journeyMillis;
            samples.add(sample);
            synchronized (this) {
                try { Files.writeString(directory.resolve("samples.csv"), sample.csv(), java.nio.file.StandardOpenOption.APPEND); }
                catch (IOException failure) { stop.set(true); throw new IllegalStateException(failure); }
            }
        }
    }

    /**
     * 使用真实具名 Hikari/JOOQ 装配；保留显式 direct 模式用于原非池化反例的复跑。
     * @return 独立真实应用上下文的服务夹具
     * @throws Exception 无法核对实际连接池时抛出
     */
    private WorkflowUcFixture openServer() throws Exception {
        if (datasourceMode.equals("direct")) return WorkflowUcFixture.openWithProperties(Map.of("flow.test.retry-task", true));
        assertEquals("pooled", datasourceMode);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("flow.test.retry-task", true);
        properties.put("consul.client.enabled", false);
        properties.put("datasources.flow.enabled", true);
        properties.put("datasources.flow.url", System.getenv("FLOW_POSTGRES_TEST_URL"));
        properties.put("datasources.flow.username", System.getenv("FLOW_POSTGRES_TEST_USER"));
        properties.put("datasources.flow.password", System.getenv("FLOW_POSTGRES_TEST_PASSWORD"));
        properties.put("datasources.flow.driver-class-name", "org.postgresql.Driver");
        properties.put("datasources.flow.maximum-pool-size", positive("FLOW_STRESS_POOL_SIZE", 16));
        properties.put("datasources.flow.minimum-idle", 2);
        properties.put("datasources.default.enabled", true);
        properties.put("datasources.default.url", System.getenv("FLOW_POSTGRES_DEFAULT_TEST_URL"));
        properties.put("datasources.default.username", System.getenv("FLOW_POSTGRES_TEST_USER"));
        properties.put("datasources.default.password", System.getenv("FLOW_POSTGRES_TEST_PASSWORD"));
        properties.put("datasources.default.driver-class-name", "org.postgresql.Driver");
        properties.put("datasources.default.maximum-pool-size", 2);
        properties.put("datasources.default.minimum-idle", 1);
        WorkflowUcFixture fixture = WorkflowUcFixture.openWithRuntimeDataSource(properties);
        var jooq = fixture.bean(org.x9.jooq.JOOQ.class, "flow");
        assertFalse(jooq instanceof org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter);
        var pool = fixture.bean(javax.sql.DataSource.class, "flow").unwrap(io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource.class);
        assertSame(pool, jooq.createDSLContext().getDataSource().unwrap(io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource.class));
        assertEquals(positive("FLOW_STRESS_POOL_SIZE", 16), pool.getMaximumPoolSize());
        assertEquals(System.getenv("FLOW_POSTGRES_TEST_URL"), pool.getJdbcUrl());
        var defaultPool = fixture.bean(javax.sql.DataSource.class, "default").unwrap(io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource.class);
        assertEquals(System.getenv("FLOW_POSTGRES_DEFAULT_TEST_URL"), defaultPool.getJdbcUrl());
        Files.writeString(directory.resolve("datasource-" + servers.size() + ".properties"),
            "mode=pooled\njooqClass=" + jooq.getClass().getName() + "\npoolClass=" + pool.getClass().getName()
                + "\npoolName=" + pool.getPoolName() + "\nmaximumPoolSize=" + pool.getMaximumPoolSize()
                + "\nminimumIdle=" + pool.getMinimumIdle() + "\njooqUsesSameNamedPool=true\n"
                + "flowUrl=" + pool.getJdbcUrl() + "\ndefaultUrl=" + defaultPool.getJdbcUrl() + "\n");
        return fixture;
    }

    /**
     * 长压力只允许本机隔离服务，显式参数不得指向远端业务环境；证据不包含凭证。
     * @throws IOException 无法保存连接目标证据时抛出
     */
    private void verifyLocalTargets() throws IOException {
        StringBuilder evidence = new StringBuilder();
        for (String name : List.of("FLOW_POSTGRES_TEST_URL", "FLOW_POSTGRES_DEFAULT_TEST_URL", "FLOW_PULSAR_TEST_URL", "FLOW_PULSAR_TEST_ADMIN_URL")) {
            String value = java.util.Objects.requireNonNull(System.getenv(name), name);
            java.net.URI uri = java.net.URI.create(value.replaceFirst("^jdbc:", ""));
            assertTrue(List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost()), "压力仅允许本机隔离目标：" + name);
            assertNull(uri.getUserInfo(), "连接目标不得内嵌凭证");
            evidence.append(name).append('=').append(value).append('\n');
        }
        evidence.append("micronaut.config-client.enabled=false\nconsul.client.registration.enabled=false\nconsul.client.watch.service.enabled=false\nconsul.client.enabled=false (pooled)\nbusinessHttpHost=127.0.0.1\n");
        Files.writeString(directory.resolve("local-targets.properties"), evidence);
    }

    /**
     * 首次观察目标终态即固定延迟，后续等待迟到消息与断言耗时另计入旅程耗时。
     * @param sample 当前用户旅程
     * @param terminal 已经从公开查询取得的目标终态
     */
    private void recordTerminal(Sample sample, Execution terminal) {
        if (sample.terminalAt != 0) return;
        sample.terminal = terminal.isTerminal();
        sample.terminalAt = TimeUtil.now();
        sample.terminalState = terminal.state().current().name();
        sample.elapsedMillis = (System.nanoTime() - sample.startedNanos) / 1_000_000L;
        sample.canceled = terminal.state().is(State.Type.KILLED);
    }

    /**
     * 查询具体 Pause 后正常恢复并重复提交，或真实并发提交恢复与取消。
     * @param fixture 调用方所在真实上下文
     * @param session 当前租户
     * @param flow 本实例绑定的正式定义
     * @param sample 当前样本的指标记录
     * @param token 本样本唯一输入
     * @param race true 时同时取消和恢复，false 时完成后重复恢复
     * @return 公开查询的终态快照
     * @throws Exception 并发操作或等待失败时抛出
     */
    private Execution completePause(WorkflowUcFixture fixture, Session<User> session, Flow flow, Sample sample, String token, boolean race) throws Exception {
        Execution paused = await(fixture, session, sample.execution, e -> !e.pausedTaskRuns().isEmpty());
        String pause = paused.pausedTaskRuns().getFirst().id();
        if (race) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            var resume = racePool.submit(() -> raceCommand(ready, go, () -> fixture.executionService().resume(session, sample.execution, pause, Map.of("decision", token))));
            var cancel = racePool.submit(() -> raceCommand(ready, go, () -> fixture.executionService().cancel(session, sample.execution)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            sample.commandsAccepted += resume.get(30, TimeUnit.SECONDS) + cancel.get(30, TimeUnit.SECONDS);
            sample.commandsRejected += 2 - sample.commandsAccepted;
        } else {
            fixture.executionService().resume(session, sample.execution, pause, Map.of("decision", token));
            sample.commandsAccepted++;
        }
        Execution result = await(fixture, session, sample.execution, Execution::isTerminal);
        recordTerminal(sample, result);
        if (race) {
            assertTrue(result.state().is(State.Type.SUCCESS) || result.state().is(State.Type.KILLED));
            assertEquals(result.state().current(), result.requireTaskRun(pause).state().current());
            assertEquals(1, result.state().history().stream().filter(h -> h.state() == State.Type.SUCCESS || h.state() == State.Type.KILLED).count());
            assertEquals(1, result.requireTaskRun(pause).state().history().stream().filter(h -> h.state() == State.Type.SUCCESS || h.state() == State.Type.KILLED).count());
            if (result.state().is(State.Type.KILLED)) assertTrue(result.requireTaskRun(pause).outputs().isEmpty());
        } else {
            assertEquals(token, only(result, flow, "after").outputs().get("value"));
            assertThrows(WorkflowException.class, () -> fixture.executionService().resume(session, sample.execution, pause, Map.of("decision", "duplicate")));
            sample.commandsRejected++;
            ExecutionAssertions.unchanged(result, fixture.executionService().execution(session, sample.execution).orElseThrow());
        }
        return result;
    }

    /**
     * 使用同一个门闩并发发出命令，仅把明确业务拒绝算作拒绝。
     * @param ready 操作线程已就绪的计数器
     * @param go 同时放行的门闩
     * @param command 公开业务命令
     * @return 受理为 1，明确拒绝为 0
     * @throws InterruptedException 调用线程被中断时抛出
     */
    private int raceCommand(CountDownLatch ready, CountDownLatch go, Runnable command) throws InterruptedException {
        ready.countDown();
        go.await();
        try { command.run(); return 1; }
        catch (WorkflowException rejected) { return 0; }
    }

    /**
     * 在重试等待期间取消，并跨过已观察到的原计划时间持续核对没有新调用。
     * @param fixture 真实服务夹具
     * @param session 当前租户
     * @param flow 重试流程定义
     * @param sample 当前样本
     * @return 仍保持取消的终态
     */
    private Execution cancelRetry(WorkflowUcFixture fixture, Session<User> session, Flow flow, Sample sample) {
        Execution waiting = await(fixture, session, sample.execution, e -> runs(e, flow, "business").stream().anyMatch(t -> t.state().is(State.Type.RETRY_WAIT)));
        TaskRun target = only(waiting, flow, "business");
        long plan = target.nextRetryAt();
        fixture.executionService().cancel(session, sample.execution);
        sample.commandsAccepted++;
        Execution canceled = await(fixture, session, sample.execution, Execution::isTerminal);
        recordTerminal(sample, canceled);
        assertEquals(State.Type.KILLED, canceled.state().current());
        while (TimeUtil.now() <= plan + 500) {
            assertEquals(1, business.calls(target.id()).size());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        Execution result = fixture.executionService().execution(session, sample.execution).orElseThrow();
        ExecutionAssertions.unchanged(canceled, result);
        assertEquals(1, only(result, flow, "business").attempts().size());
        assertTrue(runs(result, flow, "after").isEmpty());
        return result;
    }

    /**
     * 通过父运行来源查询子流程并恢复子 Pause，检查父调用持续等待和结果回传。
     * @param fixture 真实服务夹具
     * @param session 当前租户
     * @param flow 父正式定义
     * @param sample 当前样本
     * @param token 唯一业务输入
     * @return 父运行的成功终态
     */
    private Execution completeSubFlow(WorkflowUcFixture fixture, Session<User> session, Flow flow, Sample sample, String token) {
        await(fixture, session, sample.execution, e -> runs(e, flow, "call").stream().anyMatch(t -> t.state().is(State.Type.RUNNING)));
        Execution child = awaitChild(fixture, session, sample.execution);
        Execution paused = await(fixture, session, child.id(), e -> !e.pausedTaskRuns().isEmpty());
        assertEquals(sample.execution, paused.origin().parentId());
        assertEquals(sample.execution, paused.origin().originId());
        assertEquals(token, paused.inputs().get("token"));
        assertTrue(runs(fixture.executionService().execution(session, sample.execution).orElseThrow(), flow, "after").isEmpty());
        fixture.executionService().resume(session, child.id(), paused.pausedTaskRuns().getFirst().id(), Map.of("decision", token));
        sample.commandsAccepted++;
        Execution childDone = await(fixture, session, child.id(), Execution::isTerminal);
        assertEquals(State.Type.SUCCESS, childDone.state().current());
        assertTrue(childDone.activeTaskRuns().isEmpty());
        verifyCalls(fixture, session, childDone, token);
        terminalSnapshots.put(childDone.id(), childDone);
        Execution result = await(fixture, session, sample.execution, Execution::isTerminal);
        recordTerminal(sample, result);
        assertEquals(child.id(), only(result, flow, "call").outputs().get("executionId"));
        assertEquals(token, only(result, flow, "after").outputs().get("value"));
        assertEquals(2, fixture.executionService().lineage(session, sample.execution).size());
        return result;
    }

    /**
     * 退回后通过真实 Broker 注入旧实例恢复与推进消息，再核对旧历史和新实例隔离。
     * @param fixture 真实服务夹具
     * @param session 当前租户
     * @param flow 正式定义
     * @param sample 当前样本，记录公开返回的派生编号
     * @param token 唯一业务输入
     * @return 派生实例的成功终态
     */
    private Execution completeReplay(WorkflowUcFixture fixture, Session<User> session, Flow flow, Sample sample, String token) {
        Execution paused = await(fixture, session, sample.execution, e -> !e.pausedTaskRuns().isEmpty());
        String pause = paused.pausedTaskRuns().getFirst().id();
        String before = only(paused, flow, "before").id();
        String target = only(paused, flow, "target").id();
        RewindResult accepted = fixture.executionService().rewind(session, sample.execution, pause, target, "pressure replay");
        sample.commandsAccepted++;
        sample.replacement = accepted.executionId();
        Execution replay = await(fixture, session, sample.replacement, e -> !e.pausedTaskRuns().isEmpty());
        Execution source = await(fixture, session, sample.execution, Execution::isTerminal);
        terminalSnapshots.put(source.id(), source);
        assertNotEquals(sample.execution, sample.replacement);
        assertEquals(before, only(replay, flow, "before").id());
        assertEquals(2, runs(replay, flow, "target").size());
        assertThrows(WorkflowException.class, () -> fixture.executionService().resume(session, source.id(), pause, Map.of("decision", "stale")));
        sample.commandsRejected++;
        fixture.emitEvent(ExecutionCommand.class, Resume.from(session, source.id(), pause, Map.of("decision", "stale")));
        fixture.emitEvent(ExecutorEvent.class, ExecutorEvent.from(source, ExecutorEvent.EventType.UPDATED));
        fixture.executionService().resume(session, replay.id(), replay.pausedTaskRuns().getFirst().id(), Map.of("decision", token));
        sample.commandsAccepted++;
        Execution result = await(fixture, session, replay.id(), Execution::isTerminal);
        recordTerminal(sample, result);
        ExecutionAssertions.unchanged(source, fixture.executionService().execution(session, source.id()).orElseThrow());
        assertEquals(source.id(), result.origin().parentId());
        assertEquals(source.id(), result.origin().originId());
        assertEquals(1, business.calls(before).size());
        assertEquals(1, runs(result, flow, "after").size());
        assertEquals(token, only(result, flow, "after").outputs().get("value"));
        return result;
    }

    /**
     * 仅以公开查询寻找父实例所属的独立子运行。
     * @param fixture 真实服务夹具
     * @param session 当前租户
     * @param parent 父运行编号
     * @return 唯一直接子运行
     */
    private Execution awaitChild(WorkflowUcFixture fixture, Session<User> session, String parent) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            List<Execution> children = fixture.executionService().lineage(session, parent).stream().filter(e -> parent.equals(e.origin().parentId())).toList();
            if (!children.isEmpty()) { assertEquals(1, children.size()); return children.getFirst(); }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("child timeout: " + parent);
    }

    /**
     * 对每个真正调用的业务 TaskRun 校验外部次数、顺序、归属和最终输出。
     * @param fixture 真实公开查询入口
     * @param session 当前租户
     * @param execution 已结束快照，可能包含沿用记录
     * @param token 本次输入的唯一值
     * @return 具有真实调用历史的业务步骤编号，用于核对全部外部副作用
     */
    private java.util.Set<String> verifyCalls(WorkflowUcFixture fixture, Session<User> session, Execution execution, String token) {
        java.util.Set<String> calledTasks = new java.util.HashSet<>();
        Flow flow = fixture.flowService().flow(session, execution.flowKey(), execution.flowVersion()).orElseThrow();
        for (TaskRun run : execution.taskRuns()) {
            var definition = flow.findTask(run.taskId()).orElseThrow();
            if (!(definition instanceof RetryBusinessTask)) continue;
            List<Call> calls = business.calls(run.id());
            assertEquals(run.attempts().size(), calls.size(), "副作用重复或尝试遗漏: " + run.id());
            if (!run.attempts().isEmpty()) calledTasks.add(run.id());
            for (int i = 0; i < calls.size(); i++) {
                Call call = calls.get(i);
                assertEquals(token, call.body());
                assertTrue(call.at() >= run.attempts().get(i).startedAt());
                assertTrue(call.at() <= run.attempts().get(i).completedAt());
                if (i > 0) assertTrue(call.at() >= run.attempts().get(i - 1).completedAt() + 1000);
            }
            if (run.state().is(State.Type.SUCCESS)) assertEquals(token, run.outputs().get("value"));
        }
        return calledTasks;
    }

    /**
     * 持续通过公开入口查询直到业务条件成立，不用固定等待替代完成证据。
     * @param fixture 真实服务夹具
     * @param session 当前租户
     * @param id 公开运行编号
     * @param predicate 允许继续的业务条件
     * @return 满足条件的快照
     */
    private Execution await(WorkflowUcFixture fixture, Session<User> session, String id, Predicate<Execution> predicate) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        Execution last = null;
        while (System.nanoTime() < deadline) {
            Optional<Execution> current = fixture.executionService().execution(session, id);
            if (current.isPresent()) {
                last = current.orElseThrow();
                if (predicate.test(last)) return last;
                if (last.isTerminal()) throw new AssertionError("unexpected terminal: " + id + " " + last.state().current());
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("execution timeout: " + id + " state=" + (last == null ? "missing" : last.state().current()));
    }

    /**
     * 每个租户独立发布同一组业务 key，以复用定义验证多实例与跨租户隔离。
     * @param fixture 真实发布入口
     * @param session 独立租户
     */
    private void deployDefinitions(WorkflowUcFixture fixture, Session<User> session) {
        Flow child = deploy(fixture, session, "stress-child", pause() + step("returned", "/success", "{{ outputs.wait.decision }}", null));
        definitions.put(session.getCompanyId() + ":short", deploy(fixture, session, "stress-short", step("before", "/success", "{{ inputs.token }}", null) + step("business", "/success", "{{ outputs.before.value }}", null) + step("after", "/success", "{{ outputs.business.value }}", null)));
        String composite = "  - key: sequence\n    type: org.cses.flow.extensions.flow.Sequence\n    tasks:\n" + ("  - key: parallel\n    type: org.cses.flow.extensions.flow.Parallel\n    tasks:\n" + ("  - key: loop\n    type: org.cses.flow.extensions.flow.Loop\n    times: 3\n    tasks:\n" + step("iteration", "/success", "{{ inputs.token }}", null).indent(4) + "  - key: hit\n    type: org.cses.flow.extensions.flow.Route\n    route: '{{ inputs.token }} != NEVER'\n    tasks:\n" + step("selected", "/success", "{{ inputs.token }}", null).indent(4)).indent(4) + "  - key: miss\n    type: org.cses.flow.extensions.flow.Route\n    route: '{{ inputs.token }} == NEVER'\n    tasks:\n" + step("never", "/success", "{{ inputs.token }}", null).indent(4) + "  - key: until\n    type: org.cses.flow.extensions.flow.LoopUntil\n    condition: '{{ inputs.token }} != NEVER'\n    maxIterations: 2\n    tasks:\n" + step("until-step", "/success", "{{ inputs.token }}", null).indent(4)).indent(4) + step("after", "/success", "{{ inputs.token }}", null);
        definitions.put(session.getCompanyId() + ":composite", deploy(fixture, session, "stress-composite", composite));
        definitions.put(session.getCompanyId() + ":pause", deploy(fixture, session, "stress-pause", pause() + step("after", "/success", "{{ outputs.wait.decision }}", null)));
        definitions.put(session.getCompanyId() + ":race", deploy(fixture, session, "stress-race", pause()));
        for (String kind : List.of("retry", "exhaust", "retry-cancel")) {
            String retry = "{type: constant, maxAttempts: 2, intervalMillis: " + (kind.equals("retry-cancel") ? 3000 : 1000) + "}";
            definitions.put(session.getCompanyId() + ":" + kind, deploy(fixture, session, "stress-" + kind, step("before", "/success", "{{ inputs.token }}", null) + step("business", kind.equals("retry") ? "/flaky" : "/exhaust", "{{ inputs.token }}", retry) + step("after", "/success", "{{ outputs.business.value }}", null)));
        }
        String call = "  - key: call\n    type: org.cses.flow.extensions.flow.SubFlow\n    flowKey: " + child.key() + "\n    flowVersion: " + child.version() + "\n    inputs:\n      - key: token\n        type: STRING\n";
        definitions.put(session.getCompanyId() + ":subflow", deploy(fixture, session, "stress-subflow", step("before", "/success", "{{ inputs.token }}", null) + call + step("after", "/success", "{{ outputs.call.outputs.returned.value }}", null)));
        definitions.put(session.getCompanyId() + ":replay", deploy(fixture, session, "stress-replay", step("before", "/success", "{{ inputs.token }}", null) + step("target", "/success", "{{ inputs.token }}", null) + pause() + step("after", "/success", "{{ outputs.wait.decision }}", null)));
    }

    /**
     * 保存草稿并公开发布独立正式定义。
     * @param fixture 发布入口
     * @param session 当前租户
     * @param key 固定业务 key，各租户独立使用
     * @param tasks 已缩进的任务 YAML
     * @return 新正式定义
     */
    private Flow deploy(WorkflowUcFixture fixture, Session<User> session, String key, String tasks) {
        fixture.flowService().save(session, PublishFlowCommand.from("key: " + key + "\ninputs:\n  - key: token\n    type: STRING\n    required: true\ntasks:\n" + tasks));
        return fixture.flowService().save(session, PublishFlowCommand.from(key, false));
    }

    /**
     * 生成真实 HTTP 业务任务定义。
     * @param key 步骤业务标识
     * @param path 外部业务的成功或失败路径
     * @param message 可渲染的输入模板
     * @param retry 可空重试定义
     * @return 缩进为顶层步骤的 YAML
     */
    private String step(String key, String path, String message, String retry) {
        return "  - key: " + key + "\n    type: " + TASK + "\n    endpoint: 'http://127.0.0.1:" + business.server.getAddress().getPort() + path + "'\n    message: '" + message + "'\n" + (retry == null ? "" : "    retry: " + retry + "\n");
    }

    /** @return 带真实前置业务动作、必填恢复结果的单一 Pause 定义 */
    private String pause() {
        return "  - key: wait\n    type: org.cses.flow.extensions.flow.Pause\n    onPause:\n" + step("opened", "/success", "{{ inputs.token }}", null).replaceFirst("  - key:", "    key:").indent(2) + "    onResume:\n      - key: decision\n        type: STRING\n        required: true\n";
    }

    /**
     * 读取某步骤的全部真实历史。
     * @param execution 公开运行快照
     * @param flow 绑定正式定义
     * @param key 步骤 key
     * @return 同一任务的有序运行记录
     */
    private List<TaskRun> runs(Execution execution, Flow flow, String key) {
        String id = flow.allTasks().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow().id();
        return execution.taskRunsForTask(id);
    }

    /**
     * 查询并断言唯一步骤记录。
     * @param execution 公开运行快照
     * @param flow 绑定定义
     * @param key 步骤业务标识
     * @return 唯一运行记录
     */
    private TaskRun only(Execution execution, Flow flow, String key) {
        List<TaskRun> records = runs(execution, flow, key);
        assertEquals(1, records.size(), key);
        return records.getFirst();
    }

    /**
     * 按所有本轮租户保存公开资源数量，物理删除不作为完成证据。
     * @param name 当前检查点名称
     * @throws IOException 证据文件不可写时抛出
     */
    private void snapshot(String name) throws IOException {
        StringBuilder csv = new StringBuilder("company,executions,activeExecutions,pausedTasks,activeTasks,retryPlans,states\n");
        for (Session<User> session : tenants) {
            List<Execution> executions = servers.getFirst().executionService().executions(session);
            csv.append(session.getCompanyId()).append(',').append(executions.size()).append(',')
                .append(executions.stream().filter(e -> !e.isTerminal()).count()).append(',')
                .append(executions.stream().mapToLong(e -> e.pausedTaskRuns().size()).sum()).append(',')
                .append(executions.stream().mapToLong(e -> e.activeTaskRuns().size()).sum()).append(',')
                .append(executions.stream().flatMap(e -> e.effectiveTaskRuns().stream()).filter(t -> t.nextRetryAt() != null).count()).append(',')
                .append(quote(executions.stream().collect(Collectors.groupingBy(e -> e.state().current(), Collectors.counting())).toString())).append('\n');
        }
        Files.writeString(directory.resolve("resources-" + name + ".csv"), csv);
    }

    /** @return 公开查询中本轮租户尚未终止的实际实例数量 */
    private long activeExecutions() {
        return tenants.stream().flatMap(s -> servers.getFirst().executionService().executions(s).stream()).filter(e -> !e.isTerminal()).count();
    }

    /**
     * 只读记录真实 Broker 订阅积压、未确认数及消费者数。
     * @param name 检查点名称
     * @throws Exception Broker 管理接口不可用或证据文件无法写入时抛出
     */
    private void brokerSnapshot(String name) throws Exception {
        StringBuilder csv = new StringBuilder("topic,subscription,backlog,unacked,consumers\n");
        try (var admin = PulsarTestEnvironment.admin()) {
            for (String topic : List.of("flow-executor-command", "flow-executor-event", "flow-delay", "flow-scheduler-event")) {
                try {
                    var stats = admin.topics().getStats("persistent://public/default/" + topic);
                    stats.getSubscriptions().forEach((subscription, value) -> csv.append(topic).append(',').append(subscription).append(',').append(value.getMsgBacklog()).append(',').append(value.getUnackedMessages()).append(',').append(value.getConsumers().size()).append('\n'));
                } catch (org.apache.pulsar.client.admin.PulsarAdminException.NotFoundException ignored) { }
            }
        }
        Files.writeString(directory.resolve("broker-" + name + ".csv"), csv);
    }

    /**
     * 有界等待实际参与本轮的运行队列积压与未确认消息清零；不跳过或删除消息。
     * @throws Exception Broker 管理查询失败或六十秒内未排空时抛出
     */
    private void awaitDrained() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        int emptyObservations = 0;
        try (var admin = PulsarTestEnvironment.admin()) {
            while (System.nanoTime() < deadline) {
                long pending = 0;
                for (String topic : List.of("flow-executor-command", "flow-executor-event", "flow-delay")) {
                    try {
                        for (var subscription : admin.topics().getStats("persistent://public/default/" + topic).getSubscriptions().values()) {
                            pending += subscription.getMsgBacklog() + subscription.getUnackedMessages();
                        }
                    } catch (org.apache.pulsar.client.admin.PulsarAdminException.NotFoundException ignored) { }
                }
                emptyObservations = pending == 0 ? emptyObservations + 1 : 0;
                if (emptyObservations >= 3) return;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            }
        }
        throw new AssertionError("Broker drain timeout; 未清理积压，保留现场");
    }

    /** 排空后重新查询所有终态和实际副作用，捕获最后一个用户请求之后才到达的旧消息影响。 */
    private void verifyAfterDrain() {
        java.util.Set<String> actualIds = tenants.stream()
            .flatMap(session -> servers.getFirst().executionService().executions(session).stream())
            .map(Execution::id).collect(Collectors.toSet());
        assertEquals(terminalSnapshots.keySet(), actualIds, "出现未受理或重复派生的额外实例");
        java.util.Set<String> calledTasks = new java.util.HashSet<>();
        for (Execution expected : terminalSnapshots.values()) {
            Session<User> session = tenants.stream().filter(s -> s.getCompanyId().equals(expected.companyId())).findFirst().orElseThrow();
            Execution current = servers.getFirst().executionService().execution(session, expected.id()).orElseThrow();
            ExecutionAssertions.unchanged(expected, current);
            calledTasks.addAll(verifyCalls(servers.getFirst(), session, current, (String) current.inputs().get("token")));
        }
        assertEquals(calledTasks, business.calls.keySet(), "存在没有对应持久化步骤历史的外部副作用");
    }

    /**
     * 保存阶段的真实请求与终态计数、延迟分位及吞吐；不把业务预期失败算作框架错误。
     * @param phase 本次运行的名称
     * @param concurrency 配置的并发旅程数
     * @param contexts 同时参与的真实上下文数
     * @param durationMillis 本次负载的实际持续毫秒数
     * @throws IOException 文件不可写时抛出
     */
    private void writeSummary(String phase, int concurrency, int contexts, long durationMillis) throws IOException {
        List<Long> times = samples.stream().filter(s -> s.terminal).map(s -> s.elapsedMillis).sorted().toList();
        long accepted = samples.stream().filter(s -> s.accepted).count();
        long terminal = samples.stream().filter(s -> s.terminal).count();
        long errors = samples.stream().filter(s -> !s.error.isEmpty()).count();
        String summary = "phase=" + phase + "\ndatasource=" + datasourceMode + "\nselectedKinds=" + selectedKinds + "\nloadStartedAt=" + loadStartedAt + "\nloadEndedAt=" + loadEndedAt + "\nadmissionClosedAt=" + admissionClosedAt + "\ndrainedAt=" + drainedAt + "\nconcurrency=" + concurrency + "\ncontexts=" + contexts + "\nsamples=" + samples.size() + "\naccepted=" + accepted + "\nterminal=" + terminal + "\nsuccessful=" + samples.stream().filter(s -> s.terminalState.equals("SUCCESS")).count() + "\nverifiedJourneys=" + samples.stream().filter(s -> s.accepted && s.terminal && s.error.isEmpty()).count() + "\nexpectedFailures=" + samples.stream().filter(s -> s.expectedFailure && s.terminalState.equals("FAILED")).count() + "\ncanceled=" + samples.stream().filter(s -> s.canceled).count() + "\nstageFailure=" + stageFailure + "\nunexpected=" + errors + "\ntimeouts=" + samples.stream().filter(s -> s.timeout).count() + "\ndurationMillis=" + durationMillis + "\nthroughputPerSecond=" + terminal * 1000.0 / Math.max(1, durationMillis) + "\np50Millis=" + percentile(times, .50) + "\np95Millis=" + percentile(times, .95) + "\np99Millis=" + percentile(times, .99) + "\nkindCounts=" + samples.stream().collect(Collectors.groupingBy(s -> s.kind, Collectors.counting())) + "\n";
        Files.writeString(directory.resolve("summary.properties"), summary);
        System.out.println("STRESS " + summary.replace('\n', ' '));
    }

    /**
     * 记录当前测试 JVM 堆与线程快照；不以中间件容器指标替代应用指标。
     * @param checkpoint 检查点名称
     * @throws IOException 指标无法落盘时抛出
     */
    private void jvmSnapshot(String checkpoint) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        String snapshot = "at=" + TimeUtil.now() + "\nmaxMemory=" + runtime.maxMemory()
            + "\ntotalMemory=" + runtime.totalMemory() + "\nfreeMemory=" + runtime.freeMemory()
            + "\nthreadCount=" + threads.getThreadCount() + "\npeakThreadCount=" + threads.getPeakThreadCount() + "\n";
        Files.writeString(directory.resolve("jvm-" + checkpoint + ".properties"), snapshot);
    }

    /**
     * 使用 nearest-rank 计算实际完成样本的分位数。
     * @param sorted 已升序排序的耗时，不修改
     * @param quantile 零至一的目标分位
     * @return 对应毫秒数；没有完成样本时为 -1
     */
    private long percentile(List<Long> sorted, double quantile) {
        return sorted.isEmpty() ? -1 : sorted.get((int) Math.ceil(sorted.size() * quantile) - 1);
    }

    /**
     * 读取必须为正数的可选环境配置。
     * @param name 环境变量名称
     * @param fallback 缺失时使用的正数
     * @return 当前配置的正整数
     */
    private static int positive(String name, int fallback) {
        int value = Integer.parseInt(System.getenv().getOrDefault(name, Integer.toString(fallback)));
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    /**
     * 转义 CSV 字段，避免异常文本破坏行边界。
     * @param value 原始文本，不允许 null
     * @return 带引号且不含换行的 CSV 字段
     */
    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\""; }

    /** 单个用户旅程的原始指标，不共享业务状态。 */
    private static class Sample {
        int index;
        String kind = "";
        String company = "";
        String execution = "";
        String replacement = "";
        String terminalState = "";
        boolean accepted;
        boolean terminal;
        boolean expectedFailure;
        boolean canceled;
        boolean timeout;
        long startedAt;
        long startedNanos;
        long journeyMillis;
        long acceptedAt;
        long terminalAt;
        long elapsedMillis;
        long acceptMillis;
        int commandsAccepted;
        int commandsRejected;
        String error = "";

        /** @return 一个样本的完整 CSV 行 */
        String csv() { return index + "," + kind + "," + company + "," + execution + "," + replacement + "," + accepted + "," + terminal + "," + expectedFailure + "," + canceled + "," + timeout + "," + startedAt + "," + acceptedAt + "," + terminalAt + "," + terminalState + "," + elapsedMillis + "," + journeyMillis + "," + acceptMillis + "," + commandsAccepted + "," + commandsRejected + "," + quote(error) + "\n"; }
    }

    /** 真实 HTTP 外部业务样本；每个 taskRun 独立记录尝试，不代替 Flow 运行逻辑。 */
    private static class Business implements AutoCloseable {
        HttpServer server;
        java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(8);
        Map<String, List<Call>> calls = new ConcurrentHashMap<>();

        /**
         * 在本机动态端口启动业务服务，flaky 首次失败，exhaust 持续失败。
         * @throws IOException 无法绑定端口时抛出
         */
        Business() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
            server.setExecutor(pool);
            server.createContext("/", exchange -> {
                String execution = exchange.getRequestHeaders().getFirst("X-Execution-Id");
                String task = exchange.getRequestHeaders().getFirst("X-TaskRun-Id");
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                List<Call> history = calls.computeIfAbsent(task, key -> new CopyOnWriteArrayList<>());
                history.add(Call.from(execution, task, path, body, TimeUtil.now()));
                boolean failed = path.equals("/exhaust") || path.equals("/flaky") && history.size() == 1;
                byte[] response = (failed ? "stress-business-failure-" + history.size() : body).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failed ? 503 : 200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
                exchange.close();
            });
            server.start();
        }

        /**
         * 查询一个实际步骤的全部外部调用。
         * @param taskRun 公开查询取得的步骤运行编号
         * @return 独立不可变调用列表
         */
        List<Call> calls(String taskRun) { return List.copyOf(calls.getOrDefault(taskRun, List.of())); }

        /**
         * 保存所有实际副作用事实，包含执行归属及输入，用于独立重算重复与污染。
         * @param path 本轮证据路径
         * @throws IOException 文件无法写入时抛出
         */
        void write(Path path) throws IOException {
            StringBuilder csv = new StringBuilder("execution,taskRun,path,body,at\n");
            calls.values().stream().flatMap(List::stream).sorted(Comparator.comparingLong(Call::at)).forEach(c -> csv.append(c.execution()).append(',').append(c.taskRun()).append(',').append(c.path()).append(',').append(quote(c.body())).append(',').append(c.at()).append('\n'));
            Files.writeString(path, csv);
        }

        /** 停止本场景 HTTP 接口并释放专用线程池。 */
        @Override public void close() { server.stop(0); pool.shutdownNow(); }
    }

    /** 外部业务每次实际收到请求的只读事实。 */
    private record Call(String execution, String taskRun, String path, String body, long at) {
        /**
         * 创建一个外部请求事实。
         * @param execution 所属运行编号
         * @param taskRun 所属步骤运行编号
         * @param path 业务服务路径
         * @param body 请求文本
         * @param at 接收时间的 Unix 毫秒
         * @return 新建只读请求记录
         */
        static Call from(String execution, String taskRun, String path, String body, long at) { return new Call(execution, taskRun, path, body, at); }
    }
}
