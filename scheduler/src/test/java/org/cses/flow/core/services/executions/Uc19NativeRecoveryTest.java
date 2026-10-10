package org.cses.flow.core.services.executions;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import io.micronaut.context.ApplicationContext;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.cses.flow.scheduler.NativeBusinessTask;
import org.cses.flow.scheduler.SchedulerTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/** UC: docs/uc/flow/UC-19 原生流程运行崩溃恢复.md */
class Uc19NativeRecoveryTest {
    /**
     * S1 在真实 B 调用窗口强杀进程，验证原串行运行自动接续。
     *
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @Test
    void s1SerialRecovery() throws Exception {
        runScenario("s1");
    }

    /**
     * S2 命中路径运行中崩溃，保留另一条跳过路径。
     *
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @Test
    void s2RouteRecovery() throws Exception {
        runScenario("s2");
    }

    /**
     * S3 一个并行分支完成后崩溃，汇总等待另一个分支。
     *
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @Test
    void s3ParallelRecovery() throws Exception {
        runScenario("s3");
    }

    /**
     * S4 固定循环第二轮中途崩溃，继续完整三轮。
     *
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @Test
    void s4FixedLoopRecovery() throws Exception {
        runScenario("s4");
    }

    /**
     * S5 条件循环第二轮中途崩溃，依据正确结果结束。
     *
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @Test
    void s5ConditionalLoopRecovery() throws Exception {
        runScenario("s5");
    }

    /**
     * S6 子流程 Pause 跨强杀保留关联，公开恢复后完成父调用。
     *
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @Test
    void s6PausedChildRecovery() throws Exception {
        runScenario("s6");
    }

    /**
     * S7 在子终态已保存而父调用仍运行的窗口强杀。
     *
     * @param result success 或 failure，分别使用独立父子流程
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @ParameterizedTest
    @ValueSource(strings = {"success", "failure"})
    void s7ChildResultRecovery(String result) throws Exception {
        runScenario("s7-" + result);
    }

    /**
     * S8 到期前强杀，恢复原重试计划与实际调用历史。
     *
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @Test
    void s8RetryRecovery() throws Exception {
        runScenario("s8");
    }

    /**
     * S9 两类定时器都独立命中后强杀并恢复。
     *
     * @param type cron 或 date，分别使用独立时间计划
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @ParameterizedTest
    @ValueSource(strings = {"cron", "date"})
    void s9ScheduledRecovery(String type) throws Exception {
        runScenario("s9-" + type);
    }

    /**
     * S10 公开 Webhook 入口受理后不重发，继续同一实例。
     *
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @Test
    void s10WebhookRecovery() throws Exception {
        runScenario("s10");
    }

    /**
     * S11 上游完成触发下游后强杀，下游不得丢失或重复。
     *
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @Test
    void s11FlowTriggerRecovery() throws Exception {
        runScenario("s11");
    }

    /**
     * S12 三类终态跨异常退出保持原历史。
     *
     * @throws Exception 子进程或证据操作失败时抛出
     */
    @Test
    void s12TerminalRecovery() throws Exception {
        runScenario("s12");
    }

    /**
     * 仅以独立子进程运行引擎，控制端只提供范围外的 HTTP 业务样本。 显式设置 FLOW_NATIVE_CONTROL=true 时只释放外部操作，保留同一 JVM 验证正常对照。
     *
     * @param scenario 已定义场景标识，不得为空
     * @throws Exception 子进程启动、进程收尾或证据保存失败时抛出
     */
    private void runScenario(String scenario) throws Exception {
        boolean control = Boolean.parseBoolean(System.getenv("FLOW_NATIVE_CONTROL"));
        Path dir =
                Path.of(
                                Objects.toString(
                                        System.getenv("FLOW_NATIVE_EVIDENCE_DIR"),
                                        "../build/native-validation/local/processes"),
                                (control ? "control-" : "") + scenario + "-" + StringUtil.newId())
                        .toAbsolutePath()
                        .normalize();
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("metadata.txt"),
                "phase="
                        + Objects.toString(System.getenv("FLOW_VALIDATION_PHASE"), "native")
                        + "\nscenario="
                        + scenario
                        + "\nmode="
                        + (control ? "control" : "crash")
                        + "\n");
        var calls = new CopyOnWriteArrayList<String>();
        var detail = new CopyOnWriteArrayList<String>();
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(pool);
        server.createContext(
                "/",
                exchange -> {
                    String key = exchange.getRequestURI().getPath().substring(1);
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    calls.add(key + ":" + body);
                    detail.add(
                            org.cses.flow.core.utils.TimeUtil.now()
                                    + "\t"
                                    + key
                                    + "\t"
                                    + exchange.getRequestHeaders().getFirst("X-Execution-Id")
                                    + "\t"
                                    + exchange.getRequestHeaders().getFirst("X-TaskRun-Id")
                                    + "\t"
                                    + body);
                    long attempt = calls.stream().filter(c -> c.startsWith(key + ":")).count();
                    try {
                        if (key.equals("b")
                                && (!(scenario.equals("s4") || scenario.equals("s5"))
                                        || body.equals("round-2-b"))) release.await();
                        boolean failed =
                                key.equals("fail")
                                        || (scenario.equals("s8")
                                                && key.equals("a")
                                                && attempt == 1);
                        byte[] result =
                                (failed ? "native-business-failure" : body)
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(failed ? 503 : 200, result.length);
                        exchange.getResponseBody().write(result);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        Process initial = null, recovery = null;
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort(),
                company = "uc19-" + StringUtil.newId();
        try {
            initial = start(control ? "control" : "prepare", dir, scenario, endpoint, company);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(65);
            while (!Files.exists(dir.resolve("ready"))
                    && initial.isAlive()
                    && System.nanoTime() < end) LockSupport.parkNanos(25_000_000);
            assertTrue(Files.exists(dir.resolve("ready")), "故障窗口未命中: " + dir);
            if (scenario.equals("s1")) assertEquals(List.of("a:seed", "b:seed-b"), calls);
            if (control) {
                release.countDown();
                Files.writeString(dir.resolve("continue"), "仅释放范围外业务操作，原 JVM 正常继续");
                Files.writeString(
                        dir.resolve("control.txt"), "pid=" + initial.pid() + " no-crash=true");
                recovery = initial;
                System.out.println("NATIVE_CONTROL " + scenario + " " + dir);
            } else {
                long pid = initial.pid();
                initial.destroyForcibly();
                assertTrue(initial.waitFor(10, TimeUnit.SECONDS));
                Files.writeString(
                        dir.resolve("crash.txt"),
                        "pid="
                                + pid
                                + " exit="
                                + initial.exitValue()
                                + " at="
                                + org.cses.flow.core.utils.TimeUtil.now());
                assertNotEquals(0, initial.exitValue());
                release.countDown();
                recovery = start("recover", dir, scenario, endpoint, company);
            }
            assertTrue(recovery.waitFor(65, TimeUnit.SECONDS), "恢复 JVM 超时: " + dir);
            assertEquals(0, recovery.exitValue(), "恢复断言失败: " + dir);
            if (scenario.equals("s4") || scenario.equals("s5"))
                assertEquals(
                        scenario.equals("s4") ? 3 : 2,
                        calls.stream().filter(c -> c.startsWith("a:")).count());
            else if (scenario.equals("s8"))
                assertEquals(2, calls.stream().filter(c -> c.startsWith("a:")).count());
            else if (!scenario.equals("s6")
                    && !scenario.startsWith("s7")
                    && !scenario.equals("s12"))
                assertEquals(1, calls.stream().filter(c -> c.startsWith("a:")).count());
            if (!scenario.equals("s12") && !scenario.equals("s7-failure"))
                assertEquals(1, calls.stream().filter(c -> c.startsWith("c:")).count());
            assertEquals(0, calls.stream().filter(c -> c.startsWith("wrong:")).count());
            if (scenario.equals("s6") || scenario.equals("s7-success"))
                assertEquals(1, calls.stream().filter(c -> c.startsWith("child-result:")).count());
            if (scenario.equals("s7-failure") || scenario.equals("s12"))
                assertEquals(1, calls.stream().filter(c -> c.startsWith("fail:")).count());
            if (scenario.equals("s12")) {
                assertEquals(1, calls.stream().filter(c -> c.startsWith("done:")).count());
                assertEquals(1, calls.stream().filter(c -> c.startsWith("after:")).count());
            }
        } finally {
            if (initial != null && initial.isAlive()) initial.destroyForcibly().waitFor();
            if (recovery != null && recovery.isAlive()) recovery.destroyForcibly().waitFor();
            release.countDown();
            server.stop(0);
            pool.shutdownNow();
            Files.write(dir.resolve("calls.txt"), calls);
            Files.write(dir.resolve("calls-detail.tsv"), detail);
        }
    }

    /**
     * 启动连接同一持久化环境的新 JVM，保留独立日志和实际 PID。
     *
     * @param mode prepare、recover 或显式的无崩溃 control
     * @param dir 已创建的本场景证据目录
     * @param scenario 场景标识
     * @param endpoint 仅限本机的外部业务端点
     * @param company 跨进程保持的本场景租户编号
     * @return 由调用方负责终止的专属子进程
     * @throws Exception 无法启动或写入 PID 文件时抛出
     */
    private Process start(String mode, Path dir, String scenario, String endpoint, String company)
            throws Exception {
        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-Xmx768m",
                                "-cp",
                                System.getProperty("flow.test.runtime-classpath"),
                                ScenarioProcess.class.getName(),
                                mode,
                                dir.toString(),
                                scenario,
                                endpoint,
                                company)
                        .redirectErrorStream(true)
                        .redirectOutput(dir.resolve(mode + ".log").toFile())
                        .start();
        Files.writeString(dir.resolve(mode + ".pid"), Long.toString(process.pid()));
        return process;
    }

    /** 专属崩溃测试子进程，只通过公开服务准备、查询和恢复用户运行。 */
    public static class ScenarioProcess {
        private ApplicationContext context;
        private org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport engine;
        private Session<User> session;
        private ExecutionService executions;
        private FlowService flows;
        private Path directory;
        private String endpoint;
        private String scenario;

        /**
         * 启动全新应用；准备进程等待强杀，恢复进程完成断言后正常关闭。 control 模式在同一 JVM 收到外部操作放行信号后验证正常结束。
         *
         * @param args 模式、证据目录、场景、业务端点和租户五项非空参数
         * @throws Exception 本地环境启动或证据写入失败时抛出
         */
        public static void main(String[] args) throws Exception {
            var runner = new ScenarioProcess();
            runner.directory = Path.of(args[1]);
            runner.scenario = args[2];
            runner.endpoint = args[3];
            System.setProperty("flow.native.mode", args[0]);
            System.setProperty("flow.native.directory", args[1]);
            runner.open(args[4]);
            try {
                if (args[0].equals("prepare") || args[0].equals("control")) {
                    runner.prepare();
                    Files.writeString(
                            runner.directory.resolve("ready"),
                            "PID=" + ProcessHandle.current().pid());
                    if (args[0].equals("control")) {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65);
                        while (!Files.exists(runner.directory.resolve("continue"))
                                && System.nanoTime() < deadline) LockSupport.parkNanos(25_000_000);
                        assertTrue(
                                Files.exists(runner.directory.resolve("continue")), "未收到正常对照继续信号");
                        runner.recover();
                    } else new java.util.concurrent.CountDownLatch(1).await();
                } else runner.recover();
            } catch (Throwable failure) {
                failure.printStackTrace();
                runner.snapshot("failed");
                System.exit(2);
            } finally {
                try {
                    runner.context.close();
                } finally {
                    runner.engine.close();
                }
            }
        }

        /**
         * 使用明确的本机测试数据源与 Broker 装配真实生产运行组件。
         *
         * @param company 本场景稳定租户编号，不得为空
         * @throws Exception 数据源或证据目录不可用时抛出
         */
        private void open(String company) throws Exception {
            Map<String, Object> p = new LinkedHashMap<>(PulsarTestEnvironment.properties());
            p.put("micronaut.config-client.enabled", false);
            p.put("consul.client.enabled", false);
            p.put("consul.client.registration.enabled", false);
            p.put("consul.client.watch.service.enabled", false);
            p.put("grpc.server.enabled", false);
            p.put("thrift.server.enabled", false);
            p.put("jooq.send-event", false);
            p.put("flow.test.native-subflow-gate", scenario.startsWith("s7"));
            p.put("flow.test.native-task", true);
            p.put("flow.scheduler.poll-interval", "100ms");
            for (String name : List.of("flow", "default")) {
                String url =
                        System.getenv(
                                name.equals("flow")
                                        ? "FLOW_POSTGRES_TEST_URL"
                                        : "FLOW_POSTGRES_DEFAULT_TEST_URL");
                if (!url.startsWith("jdbc:postgresql://127.0.0.1:"))
                    throw new IllegalArgumentException("Only loopback PostgreSQL allowed");
                p.put("datasources." + name + ".enabled", true);
                p.put("datasources." + name + ".url", url);
                p.put(
                        "datasources." + name + ".username",
                        System.getenv("FLOW_POSTGRES_TEST_USER"));
                p.put(
                        "datasources." + name + ".password",
                        System.getenv("FLOW_POSTGRES_TEST_PASSWORD"));
                p.put("datasources." + name + ".driver-class-name", "org.postgresql.Driver");
                p.put("datasources." + name + ".maximum-pool-size", 4);
            }
            if (!System.getenv("FLOW_PULSAR_TEST_URL").startsWith("pulsar://127.0.0.1:"))
                throw new IllegalArgumentException("Only loopback Broker allowed");
            if (!System.getenv("FLOW_PULSAR_TEST_ADMIN_URL").startsWith("http://127.0.0.1:"))
                throw new IllegalArgumentException("Only loopback Admin allowed");
            // 与普通真实 PG 夹具一致：保留原生 CRUD Handler，不启动范围外索引或外部插件。
            engine = new org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport();
            context = ApplicationContext.builder().properties(p).build();
            context.registerSingleton(org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine.class,
                    engine, null, false);
            try {
                context.start();
            } catch (RuntimeException failure) {
                try {
                    context.close();
                } finally {
                    engine.close();
                }
                throw failure;
            }
            var flowPool =
                    context.getBean(
                                    javax.sql.DataSource.class,
                                    io.micronaut.inject.qualifiers.Qualifiers.byName("flow"))
                            .unwrap(
                                    io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource
                                            .class);
            var defaultPool =
                    context.getBean(
                                    javax.sql.DataSource.class,
                                    io.micronaut.inject.qualifiers.Qualifiers.byName("default"))
                            .unwrap(
                                    io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource
                                            .class);
            assertEquals(System.getenv("FLOW_POSTGRES_TEST_URL"), flowPool.getJdbcUrl());
            assertEquals(System.getenv("FLOW_POSTGRES_DEFAULT_TEST_URL"), defaultPool.getJdbcUrl());
            Files.writeString(
                    directory.resolve(System.getProperty("flow.native.mode") + "-environment.txt"),
                    "flow="
                            + flowPool.getJdbcUrl()
                            + "\ndefault="
                            + defaultPool.getJdbcUrl()
                            + "\nbroker="
                            + System.getenv("FLOW_PULSAR_TEST_URL")
                            + "\nadmin="
                            + System.getenv("FLOW_PULSAR_TEST_ADMIN_URL"));
            executions = context.getBean(ExecutionService.class);
            flows = context.getBean(FlowService.class);
            User user = new User();
            user.setId("native-user");
            user.setName("Native recovery user");
            user.setCompanyId(company);
            session = new Session<>();
            session.setUser(user);
        }

        /**
         * 每个场景独立发布最小定义，观察到指定故障点后保存公开快照。
         *
         * @throws Exception 准备、故障点观察或证据保存失败时抛出
         */
        private void prepare() throws Exception {
            if (scenario.equals("s12")) {
                prepareTerminal();
                return;
            }
            String tasks =
                    step("a", "{{ inputs.seed }}")
                            + step("b", "{{ outputs.a.value }}-b")
                            + step("c", "{{ outputs.b.value }}-c");
            String trigger = "";
            if (scenario.equals("s2"))
                tasks =
                        "  - key: routes\n"
                                + "    type: org.cses.flow.extensions.flow.Parallel\n"
                                + "    tasks:\n"
                                + ("  - key: selected\n"
                                                + "    type: org.cses.flow.extensions.flow.Route\n"
                                                + "    route: '{{ inputs.seed }} == seed'\n"
                                                + "    tasks:\n"
                                                + (step("a", "{{ inputs.seed }}")
                                                                + step(
                                                                        "b",
                                                                        "{{ outputs.a.value }}-b"))
                                                        .indent(4)
                                                + "  - key: skipped\n"
                                                + "    type: org.cses.flow.extensions.flow.Route\n"
                                                + "    route: '{{ inputs.seed }} == other'\n"
                                                + "    tasks:\n"
                                                + step("wrong", "wrong").indent(4))
                                        .indent(4)
                                + step("c", "{{ outputs.b.value }}-c");
            if (scenario.equals("s3"))
                tasks =
                        "  - key: parallel\n"
                                + "    type: org.cses.flow.extensions.flow.Parallel\n"
                                + "    tasks:\n"
                                + (step("a", "{{ inputs.seed }}") + step("b", "branch-b")).indent(4)
                                + step("c", "{{ outputs.a.value }}+{{ outputs.b.value }}");
            if (scenario.equals("s4") || scenario.equals("s5"))
                tasks =
                        "  - key: loop\n    type: org.cses.flow.extensions.flow."
                                + (scenario.equals("s4")
                                        ? "Loop\n    times: 3\n"
                                        : "LoopUntil\n"
                                                + "    condition: '{{ outputs.b.value }} =="
                                                + " round-2-b'\n"
                                                + "    maxIterations: 3\n")
                                + "    tasks:\n"
                                + (step("a", "round-{{ taskRun.inputs.loop.iteration }}")
                                                + step("b", "{{ outputs.a.value }}-b"))
                                        .indent(4)
                                + step("c", "{{ outputs.b.value }}-c");
            if (scenario.equals("s6") || scenario.startsWith("s7")) {
                Flow child =
                        deploy(
                                header("child")
                                        + "tasks:\n"
                                        + (scenario.equals("s6")
                                                ? pause()
                                                        + step(
                                                                "child-result",
                                                                "{{ inputs.seed }}-{{"
                                                                    + " outputs.pause.answer }}")
                                                : step(
                                                        scenario.endsWith("failure")
                                                                ? "fail"
                                                                : "child-result",
                                                        "{{ inputs.seed }}-child")));
                tasks =
                        "  - key: call\n"
                                + "    type: org.cses.flow.extensions.flow.SubFlow\n"
                                + "    flowKey: "
                                + child.key()
                                + "\n    flowVersion: "
                                + child.version()
                                + "\n    inputs:\n      - key: seed\n        type: STRING\n"
                                + step("c", "{{ outputs.call.outputs.child-result.value }}-c");
            }
            if (scenario.equals("s8"))
                tasks =
                        step("a", "{{ inputs.seed }}")
                                + "    retry: {type: constant, maxAttempts: 2, intervalMillis:"
                                + " 10000}\n"
                                + step("c", "{{ outputs.a.value }}-c");
            if (scenario.startsWith("s9")) {
                long at = org.cses.flow.core.utils.TimeUtil.now() + 6000;
                trigger =
                        "triggers:\n"
                                + "  - key: scheduled\n"
                                + "    type: org.cses.flow.extensions.triggers."
                                + (scenario.endsWith("cron")
                                        ? "ScheduleTrigger\n    cron: '"
                                                + SchedulerTestSupport.cron(at)
                                                + "'\n    timezone: Asia/Shanghai\n"
                                        : "ScheduleOnDatesTrigger\n    dates: [" + at + "]\n")
                                + "    inputs: {seed: seed}\n";
            }
            if (scenario.equals("s10"))
                trigger =
                        "triggers:\n"
                                + "  - key: webhook\n"
                                + "    type: org.cses.flow.extensions.triggers.WebhookTrigger\n";
            Flow upstream = null;
            if (scenario.equals("s11")) {
                upstream =
                        deploy(
                                header("upstream")
                                        + "tasks:\n"
                                        + step("upstream-result", "upstream"));
                trigger =
                        "triggers:\n"
                                + "  - key: upstream-finished\n"
                                + "    type: org.cses.flow.extensions.triggers.FlowTrigger\n"
                                + "    flowKey: "
                                + upstream.key()
                                + "\n    states: [SUCCESS]\n    inputs: {seed: seed}\n";
            }
            Flow flow = deploy(header("main") + trigger + "tasks:\n" + tasks);
            assertEquals(
                    flow.source(),
                    flows.flow(session, flow.key(), flow.version()).orElseThrow().source());
            assertTrue(executions.executions(session).isEmpty());
            String id;
            if (scenario.equals("s10"))
                id =
                        context.getBean(
                                        org.cses.flow.core.services.triggers.WebhookTriggerService
                                                .class)
                                .fire(
                                        session,
                                        flow.key(),
                                        "webhook",
                                        org.paas.common.util.StringUtil.newId(),
                                        Map.of("seed", "seed"));
            else if (scenario.startsWith("s9") || scenario.equals("s11")) {
                if (upstream != null)
                    executions.create(
                            session,
                            upstream.key(),
                            Optional.of(upstream.version()),
                            Map.of("seed", "seed"));
                long end = org.cses.flow.core.utils.TimeUtil.now() + 20000;
                while (executions.executions(session).stream()
                                .noneMatch(e -> e.flowKey().equals(flow.key()))
                        && org.cses.flow.core.utils.TimeUtil.now() < end)
                    LockSupport.parkNanos(25_000_000);
                id =
                        executions.executions(session).stream()
                                .filter(e -> e.flowKey().equals(flow.key()))
                                .findFirst()
                                .orElseThrow()
                                .id();
            } else
                id =
                        executions
                                .create(
                                        session,
                                        flow.key(),
                                        Optional.of(flow.version()),
                                        Map.of("seed", "seed"))
                                .executionId();
            Files.writeString(directory.resolve("execution-id"), id);
            Execution before;
            if (scenario.equals("s6")) {
                before =
                        await(
                                id,
                                e ->
                                        executions.executions(session).stream()
                                                .anyMatch(
                                                        c ->
                                                                !c.id().equals(id)
                                                                        && c.state().isPaused()));
                assertTrue(runs(before, flow, "c").isEmpty());
            } else if (scenario.startsWith("s7")) {
                before =
                        await(
                                id,
                                e ->
                                        Files.exists(directory.resolve("subflow-gate"))
                                                && has(e, flow, "call", State.Type.RUNNING));
                assertTrue(
                        executions.executions(session).stream()
                                .anyMatch(c -> !c.id().equals(id) && c.isTerminal()));
                assertTrue(runs(before, flow, "c").isEmpty());
            } else if (scenario.equals("s8")) {
                before = await(id, e -> has(e, flow, "a", State.Type.RETRY_WAIT));
                TaskRun a = runs(before, flow, "a").getFirst();
                assertEquals(1, a.attempts().size());
                assertTrue(a.nextRetryAt() > org.cses.flow.core.utils.TimeUtil.now());
                Files.writeString(directory.resolve("retry-at"), a.nextRetryAt().toString());
            } else {
                int round = scenario.equals("s4") || scenario.equals("s5") ? 2 : 1;
                before =
                        await(
                                id,
                                e ->
                                        runs(e, flow, "a").stream()
                                                                .filter(
                                                                        t ->
                                                                                t.state()
                                                                                        .is(
                                                                                                State
                                                                                                        .Type
                                                                                                        .SUCCESS))
                                                                .count()
                                                        == round
                                                && runs(e, flow, "b").size() == round
                                                && runs(e, flow, "b")
                                                        .getLast()
                                                        .state()
                                                        .is(State.Type.RUNNING));
                assertTrue(runs(before, flow, "c").isEmpty());
                if (scenario.equals("s2")) {
                    before = await(id, e -> has(e, flow, "skipped", State.Type.SKIPPED));
                    assertTrue(runs(before, flow, "wrong").isEmpty());
                }
            }
            snapshot("before");
        }

        /**
         * 恢复只读取原编号，等待原运行；Pause 仅由公开用户恢复操作推进。
         *
         * @throws Exception 证据读取或恢复断言失败时抛出
         */
        private void recover() throws Exception {
            if (scenario.equals("s12")) {
                verifyTerminal();
                return;
            }
            String id = Files.readString(directory.resolve("execution-id"));
            var e = executions.execution(session, id).orElseThrow();
            Flow flow = flows.flow(session, e.flowKey(), e.flowVersion()).orElseThrow();
            assertEquals("seed", e.inputs().get("seed"));
            snapshot("restored");
            if (scenario.equals("s6")) {
                var child =
                        executions.executions(session).stream()
                                .filter(c -> !c.id().equals(id))
                                .findFirst()
                                .orElseThrow();
                assertTrue(child.state().isPaused());
                assertEquals(id, child.origin().parentId());
                assertEquals("seed", child.inputs().get("seed"));
                assertTrue(runs(e, flow, "c").isEmpty());
                var pause = child.pausedTaskRuns().getFirst();
                executions.resume(session, child.id(), pause.id(), Map.of("answer", "done"));
            }
            if (scenario.equals("s8")) {
                long plan = Long.parseLong(Files.readString(directory.resolve("retry-at")));
                assertTrue(org.cses.flow.core.utils.TimeUtil.now() < plan, "恢复观察必须早于原重试时间");
                assertEquals(plan, runs(e, flow, "a").getFirst().nextRetryAt());
                assertEquals(1, runs(e, flow, "a").getFirst().attempts().size());
            }
            var completed = await(id, Execution::isTerminal);
            assertEquals(
                    scenario.endsWith("failure") ? State.Type.FAILED : State.Type.SUCCESS,
                    completed.state().current());
            assertConfirmedHistory();
            if (scenario.equals("s6") || scenario.startsWith("s7")) {
                Execution child =
                        executions.executions(session).stream()
                                .filter(value -> !value.id().equals(id))
                                .findFirst()
                                .orElseThrow();
                TaskRun call = runs(completed, flow, "call").getFirst();
                assertEquals(id, child.origin().parentId());
                assertEquals(call.id(), child.parentTaskRunId());
                assertEquals(
                        scenario.endsWith("failure") ? State.Type.FAILED : State.Type.SUCCESS,
                        child.state().current());
                if (scenario.endsWith("failure")) {
                    assertTrue(call.error().isPresent());
                    assertTrue(
                            child.taskRuns().stream().anyMatch(task -> task.error().isPresent()));
                } else assertEquals(child.id(), call.outputs().get("executionId"));
            }
            if (!scenario.endsWith("failure")) {
                assertEquals(1, runs(completed, flow, "c").size());
                String expected =
                        switch (scenario) {
                            case "s3" -> "seed+branch-b";
                            case "s4" -> "round-3-b-c";
                            case "s5" -> "round-2-b-c";
                            case "s6" -> "seed-done-c";
                            case "s7-success" -> "seed-child-c";
                            case "s8" -> "seed-c";
                            default -> "seed-b-c";
                        };
                assertEquals(
                        expected, runs(completed, flow, "c").getFirst().outputs().get("value"));
            } else assertTrue(runs(completed, flow, "c").isEmpty());
            if (scenario.equals("s2")) {
                assertTrue(has(completed, flow, "skipped", State.Type.SKIPPED));
                assertTrue(runs(completed, flow, "wrong").isEmpty());
            }
            if (scenario.equals("s4") || scenario.equals("s5")) {
                int count = scenario.equals("s4") ? 3 : 2;
                TaskRun loop = runs(completed, flow, "loop").getFirst();
                assertTrue(loop.generation().current().isEmpty());
                assertEquals(
                        scenario.equals("s4")
                                ? List.of(
                                        "INITIAL",
                                        "FIXED_COUNT_NOT_REACHED",
                                        "FIXED_COUNT_NOT_REACHED")
                                : List.of("INITIAL", "CONDITION_NOT_SATISFIED"),
                        loop.generation().history().currents().stream()
                                .map(
                                        org.cses.flow.core.domains.executions.Generation.Current
                                                ::reason)
                                .toList());
                for (String key : List.of("a", "b")) {
                    assertEquals(count, runs(completed, flow, key).size());
                    for (int i = 0; i < count; i++) {
                        assertEquals(
                                i + 1, runs(completed, flow, key).get(i).iteration().orElseThrow());
                        assertEquals(
                                loop.id(),
                                runs(completed, flow, key).get(i).parentId().orElseThrow());
                        assertEquals(
                                "round-" + (i + 1) + (key.equals("b") ? "-b" : ""),
                                runs(completed, flow, key).get(i).outputs().get("value"));
                    }
                }
            }
            if (scenario.equals("s8")) {
                var a = runs(completed, flow, "a").getFirst();
                assertEquals(2, a.attempts().size());
                assertEquals(State.Type.FAILED, a.attempts().getFirst().state());
                assertEquals(State.Type.SUCCESS, a.attempts().getLast().state());
                assertTrue(
                        a.attempts().getLast().startedAt()
                                >= Long.parseLong(Files.readString(directory.resolve("retry-at"))));
                assertNull(a.nextRetryAt());
            }
            int count =
                    scenario.equals("s6") || scenario.startsWith("s7") || scenario.equals("s11")
                            ? 2
                            : 1;
            for (int i = 0; i < 20; i++) {
                assertEquals(count, executions.executions(session).size());
                assertTrue(executions.executions(session).stream().allMatch(Execution::isTerminal));
                LockSupport.parkNanos(25_000_000);
            }
            for (var current : executions.executions(session)) {
                assertTrue(current.activeTaskRuns().isEmpty());
                assertTrue(current.pausedTaskRuns().isEmpty());
            }
            if (scenario.startsWith("s9")) {
                flows.delete(session, flow.key(), false);
                assertTrue(flows.latestFlow(session, flow.key()).map(Flow::deleted).orElse(true));
                SchedulerTestSupport.awaitScheduleCount(flow, 0);
                for (int i = 0; i < 20; i++) {
                    assertEquals(1, executions.executions(session).size());
                    LockSupport.parkNanos(25_000_000);
                }
            }
            snapshot("after");
        }

        /**
         * 创建三个已经确认终态的独立流程。
         *
         * @throws Exception 终态查询或快照保存失败时抛出
         */
        private void prepareTerminal() throws Exception {
            for (String kind : List.of("success", "failure", "cancel")) {
                Flow f =
                        deploy(
                                header(kind)
                                        + "tasks:\n"
                                        + (kind.equals("cancel")
                                                ? pause() + step("wrong", "wrong")
                                                : step(
                                                                kind.equals("failure")
                                                                        ? "fail"
                                                                        : "done",
                                                                "terminal")
                                                        + step("after", "after")));
                String id =
                        executions
                                .create(
                                        session,
                                        f.key(),
                                        Optional.of(f.version()),
                                        Map.of("seed", "seed"))
                                .executionId();
                if (kind.equals("cancel")) {
                    await(id, e -> e.state().isPaused());
                    executions.cancel(session, id);
                }
                var e = await(id, Execution::isTerminal);
                assertEquals(
                        kind.equals("success")
                                ? State.Type.SUCCESS
                                : kind.equals("failure") ? State.Type.FAILED : State.Type.KILLED,
                        e.state().current());
            }
            snapshot("before");
        }

        /**
         * 终态连续查询保持身份、结果和历史，不允许重新启动。
         *
         * @throws Exception 证据读取或最终快照写入失败时抛出
         */
        private void verifyTerminal() throws Exception {
            for (int i = 0; i < 30; i++) {
                assertEquals(3, executions.executions(session).size());
                assertTrue(executions.executions(session).stream().allMatch(Execution::isTerminal));
                assertConfirmedHistory();
                LockSupport.parkNanos(25_000_000);
            }
            for (var e : executions.executions(session)) {
                assertTrue(e.activeTaskRuns().isEmpty());
                assertTrue(e.pausedTaskRuns().isEmpty());
            }
            snapshot("after");
        }

        /**
         * 对照公开快照保持已确认结果、来源、历史前缀和运行身份。
         *
         * @throws Exception 原快照读取失败时抛出
         */
        @SuppressWarnings("unchecked")
        private void assertConfirmedHistory() throws Exception {
            List<Map<String, Object>> before =
                    (List)
                            org.paas.json.JsonObjects.Parse(
                                            Files.readString(directory.resolve("before.json")))
                                    .asObjects(Map.class);
            for (var old : before) {
                var current = executions.execution(session, (String) old.get("id")).orElseThrow();
                var currentView =
                        org.paas.json.JsonObject.Parse(
                                        org.paas.json.JsonObject.ToJsonContent(
                                                org.cses.flow.core.serializers.JacksonMapper
                                                        .toPersistenceMap(current)))
                                .asMap();
                assertEquals(old.get("flowKey"), current.flowKey());
                assertEquals(((Number) old.get("flowVersion")).longValue(), current.flowVersion());
                assertEquals(old.get("inputs"), current.inputs());
                assertEquals(old.get("origin"), currentView.get("origin"));
                assertEquals(old.get("parentTaskRunId"), currentView.get("parentTaskRunId"));
                for (var task : (List<Map<String, Object>>) old.get("taskRuns")) {
                    var run = current.requireTaskRun((String) task.get("id"));
                    var runView =
                            org.paas.json.JsonObject.Parse(
                                            org.paas.json.JsonObject.ToJsonContent(
                                                    org.cses.flow.core.serializers.JacksonMapper
                                                            .toPersistenceMap(run)))
                                    .asMap();
                    for (String field : List.of("taskId", "parentId", "iteration", "inputs"))
                        assertEquals(task.get(field), runView.get(field));
                    var oldAttempts =
                            (List<Map<String, Object>>) task.getOrDefault("attempts", List.of());
                    var newAttempts =
                            (List<Map<String, Object>>) runView.getOrDefault("attempts", List.of());
                    assertTrue(newAttempts.size() >= oldAttempts.size());
                    for (int i = 0; i < oldAttempts.size(); i++)
                        if (oldAttempts.get(i).containsKey("completedAt"))
                            assertEquals(oldAttempts.get(i), newAttempts.get(i));
                    String state =
                            (String) ((Map<String, Object>) task.get("state")).get("current");
                    if (List.of("SUCCESS", "FAILED", "KILLED", "SKIPPED").contains(state)) {
                        assertEquals(state, run.state().current().name());
                        assertEquals(task.getOrDefault("outputs", Map.of()), run.outputs());
                        assertEquals(task.get("error"), run.error().orElse(null));
                        assertEquals(oldAttempts, newAttempts);
                    }
                }
                if (scenario.equals("s12")) assertEquals(old, currentView);
            }
        }

        /**
         * 生成固定输入的独立流程头。
         *
         * @param name 场景内流程名称后缀，不得为空
         * @return 包含业务名称和输入定义的 YAML
         */
        private String header(String name) {
            return "key: uc19-"
                    + scenario
                    + "-"
                    + name
                    + "\ninputs:\n  - key: seed\n    type: STRING\n";
        }

        /**
         * 原生 Pause 仅用于明确需要用户恢复的场景。
         *
         * @return 包含公开恢复输入的 Pause YAML
         */
        private String pause() {
            return "  - key: pause\n"
                    + "    type: org.cses.flow.extensions.flow.Pause\n"
                    + "    onPause:\n"
                    + "      key: announce\n"
                    + "      type: org.cses.flow.extensions.log.Log\n"
                    + "      message: waiting\n"
                    + "    onResume:\n"
                    + "      - key: answer\n"
                    + "        type: STRING\n";
        }

        /**
         * 通过公开草稿和发布操作创建场景定义。
         *
         * @param source 完整流程 YAML，不得为空
         * @return 已保存并发布的独立版本
         */
        private Flow deploy(String source) {
            var draft = flows.save(session, PublishFlowCommand.from(source));
            return flows.save(session, PublishFlowCommand.from(draft.key(), false));
        }

        /**
         * 创建访问确定外部业务的任务定义。
         *
         * @param key 场景内唯一的步骤业务标识
         * @param message 可引用运行变量的业务输入模板
         * @return 以两个空格缩进的任务 YAML
         */
        private String step(String key, String message) {
            return "  - key: "
                    + key
                    + "\n    type: "
                    + NativeBusinessTask.class.getName()
                    + "\n    endpoint: '"
                    + endpoint
                    + "/"
                    + key
                    + "'\n    message: '"
                    + message
                    + "'\n";
        }

        /**
         * 公开查询直到所需事实成立，超时保留真实运行状态。
         *
         * @param id 公开启动取得的运行编号
         * @param condition 只读状态判定，不修改运行对象
         * @return 满足判定条件的公开快照
         * @throws AssertionError 在 35000 毫秒内未达到目标状态时抛出
         */
        private Execution await(String id, Predicate<Execution> condition) {
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(35);
            do {
                var e = executions.execution(session, id);
                if (e.isPresent() && condition.test(e.get())) return e.get();
                LockSupport.parkNanos(25_000_000);
            } while (System.nanoTime() < end);
            throw new AssertionError(
                    "Expected state not reached for "
                            + id
                            + ": "
                            + executions
                                    .execution(session, id)
                                    .map(e -> e.state().current().name())
                                    .orElse("missing"));
        }

        /**
         * 按定义业务标识定位公开任务历史。
         *
         * @param e 公开运行快照
         * @param f 对应精确版本定义
         * @param key 定义中存在的步骤业务标识
         * @return 对应步骤的有序运行历史，未启动时为空
         */
        private List<TaskRun> runs(Execution e, Flow f, String key) {
            return e.taskRunsForTask(
                    f.allTasks().stream()
                            .filter(t -> t.key().equals(key))
                            .findFirst()
                            .orElseThrow()
                            .id());
        }

        /**
         * 查询指定步骤是否已有目标状态。
         *
         * @param e 公开运行快照
         * @param f 对应精确版本定义
         * @param key 步骤业务标识
         * @param state 期望的公开运行状态
         * @return 任一对应步骤已有此状态时为 true
         */
        private boolean has(Execution e, Flow f, String key, State.Type state) {
            return runs(e, f, key).stream().anyMatch(t -> t.state().is(state));
        }

        /**
         * 将公开查询的身份、状态、输出和尝试历史保存为可复核证据。
         *
         * @param name 本阶段证据名称，不得为空
         * @throws Exception 文件写入或 Broker 只读查询失败时抛出
         */
        private void snapshot(String name) throws Exception {
            var lines = new ArrayList<String>();
            for (var e : executions.executions(session)) {
                lines.add(
                        "execution="
                                + e.id()
                                + " flow="
                                + e.flowKey()
                                + "@"
                                + e.flowVersion()
                                + " state="
                                + e.state().current()
                                + " inputs="
                                + e.inputs());
                Flow f = flows.flow(session, e.flowKey(), e.flowVersion()).orElseThrow();
                for (var t : e.taskRuns())
                    lines.add(
                            "task="
                                    + f.findTask(t.taskId()).orElseThrow().key()
                                    + " id="
                                    + t.id()
                                    + " state="
                                    + t.state().current()
                                    + " outputs="
                                    + t.outputs()
                                    + " attempts="
                                    + t.attempts().stream()
                                            .map(
                                                    a ->
                                                            "#"
                                                                    + a.number()
                                                                    + ":"
                                                                    + a.state()
                                                                    + ":"
                                                                    + a.startedAt()
                                                                    + ":"
                                                                    + a.completedAt()
                                                                    + ":"
                                                                    + a.error())
                                            .toList()
                                    + " retry="
                                    + t.nextRetryAt());
            }
            Files.write(directory.resolve(name + ".txt"), lines);
            Files.writeString(
                    directory.resolve(name + ".json"),
                    org.paas.json.JsonObjects.FromList(
                                    executions.executions(session).stream()
                                            .map(
                                                    org.cses.flow.core.serializers.JacksonMapper
                                                            ::toPersistenceMap)
                                            .toList())
                            .toJson());
            try (var admin = PulsarTestEnvironment.admin()) {
                var stats = new LinkedHashMap<String, Object>();
                for (String topic : admin.topics().getList("public/default"))
                    stats.put(topic, admin.topics().getStats(topic));
                Files.writeString(
                        directory.resolve(name + "-broker.json"),
                        org.paas.json.JsonObject.ToJsonContent(stats));
            }
        }
    }
}
