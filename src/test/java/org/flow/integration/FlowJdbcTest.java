package org.flow.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import org.flow.jdbc.JooqDSLContextWrapper;
import org.flow.jdbc.LogJdbcDataSourceProvider;
import org.flow.queue.jdbc.client.JdbcQueueClient;
import org.flow.core.utils.Await;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false, transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlowJdbcTest implements TestPropertyProvider {
    @Inject JdbcQueueClient queue;
    @Inject JooqDSLContextWrapper transactions;
    @Inject LogJdbcDataSourceProvider logs;
    @Inject @Named("flow") DataSource flowSource;
    @Inject @Named("default") DataSource controlSource;
    @TempDir Path temporary;

    /**
     * 将两个独立验证库配置到调用应用标准数据源节点；缺少验证库时明确失败。
     *
     * @return Micronaut 测试配置，不包含运行期建表或私有连接池配置
     * @throws IllegalStateException 未提供本任务验证库 URL 时抛出
     */
    @Override
    public Map<String, String> getProperties() {
        String url = System.getenv("FLOW_TEST_URL");
        String other = System.getenv("FLOW_CONTROL_URL");
        if (url == null || url.isBlank() || other == null || other.isBlank()) {
            throw new IllegalStateException("请先创建独立验证库并配置 FLOW_TEST_URL 和 FLOW_CONTROL_URL");
        }
        return Map.ofEntries(
            Map.entry("flow.repository.type", "postgres"),
            Map.entry("flow.queue.type", "postgres"),
            Map.entry("flow.jdbc.queues.poll-size", "1"),
            Map.entry("datasources.flow.url", url),
            Map.entry("datasources.flow.username", "flow"),
            Map.entry("datasources.flow.password", "flow"),
            Map.entry("datasources.flow.driver-class-name", "org.postgresql.Driver"),
            Map.entry("datasources.default.url", other),
            Map.entry("datasources.default.username", "flow"),
            Map.entry("datasources.default.password", "flow"),
            Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver")
        );
    }

    /**
     * 在真实 PostgreSQL 中验证队列异常回滚、成功消费删除和独立事务同源。
     *
     * @throws Exception 上游 SQL 资源缺失、数据库或队列操作失败时使测试失败
     */
    @Test
    void persistsQueueOnlyInFlowAndKeepsFailedMessages() throws Exception {
        try (var flowConnection = DelegatingDataSource.unwrapDataSource(flowSource).getConnection();
             var controlConnection = DelegatingDataSource.unwrapDataSource(controlSource).getConnection()) {
            DSLContext flow = DSL.using(flowConnection, SQLDialect.POSTGRES);
            DSLContext control = DSL.using(controlConnection, SQLDialect.POSTGRES);
            assertNull(control.fetchValue("select to_regclass('queues')"));
            assertNull(flow.fetchValue("select to_regclass('flows')"), "应用启动不得自动执行引擎 DDL");
            assertNull(flow.fetchValue("select to_regclass('queues')"), "必须使用空验证库");
            assertNull(flow.fetchValue("select to_regclass('migration_probe')"));
            assertSame(DelegatingDataSource.unwrapDataSource(flowSource),
                DelegatingDataSource.unwrapDataSource(logs.dataSource()));
            logs.close();
            try (var stillOpen = DelegatingDataSource.unwrapDataSource(flowSource).getConnection()) {
                assertEquals(1, DSL.using(stillOpen, SQLDialect.POSTGRES).fetchValue("select 1", Integer.class));
            }
            try (var sql = getClass().getResourceAsStream("/migrations/2.0.02-queue-postgres.sql")) {
                assertNotNull(sql);
                flow.execute(new String(sql.readAllBytes(), StandardCharsets.UTF_8));
            }
            try {
                queue.publish("migration", null, "message", "{\"text\":\"persisted\"}");
                assertThrows(IllegalStateException.class, () -> queue.subscribeDispatch("migration", List.of(),
                    bytes -> { throw new IllegalStateException("消费失败"); }));
                assertEquals(1, flow.fetchCount(DSL.table("queues")));
                AtomicReference<String> received = new AtomicReference<>();
                assertEquals(1, queue.subscribeDispatch("migration", List.of(),
                    bytes -> received.set(new String(bytes, StandardCharsets.UTF_8))));
                assertTrue(received.get().contains("persisted"));
                assertEquals(0, flow.fetchCount(DSL.table("queues")));
                assertNull(control.fetchValue("select to_regclass('queues')"));
                verifiesCompetingConsumers();
                verifiesKilledConsumerRecovery();

                flow.execute("create table migration_probe (id integer primary key)");
                assertThrows(IllegalStateException.class, () -> transactions.transaction(configuration -> {
                    DSL.using(configuration).execute("insert into migration_probe values (1)");
                    transactions.requireNewTransaction(inner -> DSL.using(inner).execute("insert into migration_probe values (2)"));
                    throw new IllegalStateException("外层回滚");
                }));
                assertEquals(List.of(2), flow.fetch("select id from migration_probe").getValues(0, Integer.class));
                assertNull(control.fetchValue("select to_regclass('migration_probe')"));
            } finally {
                flow.execute("drop table if exists migration_probe");
                flow.execute("drop table queues");
            }
        }
    }

    /**
     * 在一个消费者持锁期间让另一个消费者领取另一条消息，验证 SKIP LOCKED 与 ACK。
     * @throws Exception 并发领取或断言失败时使测试失败
     */
    private void verifiesCompetingConsumers() throws Exception {
        queue.publish("competing", null, "first", "\"first\"");
        queue.publish("competing", null, "second", "\"second\"");
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> firstValue = new AtomicReference<>();
        AtomicReference<String> secondValue = new AtomicReference<>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> queue.subscribeDispatch("competing", List.of(), bytes -> {
                firstValue.set(new String(bytes, StandardCharsets.UTF_8));
                claimed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待另一个消费者超时");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertTrue(claimed.await(10, TimeUnit.SECONDS));
                var second = threads.submit(() -> queue.subscribeDispatch("competing", List.of(),
                    bytes -> secondValue.set(new String(bytes, StandardCharsets.UTF_8))));
                assertEquals(1, second.get(5, TimeUnit.SECONDS));
                assertNotEquals(firstValue.get(), secondValue.get());
            } finally {
                release.countDown();
            }
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(0, queue.subscribeDispatch("competing", List.of(), bytes -> fail("ACK 后不能重新领取")));
        }
    }

    /**
     * 强杀持有消费事务的独立 JVM，验证数据库回滚后消息可再次领取。
     * @throws Exception 子进程、数据库恢复或断言失败时使测试失败
     */
    private void verifiesKilledConsumerRecovery() throws Exception {
        queue.publish("crash", null, "durable", "\"survives-process-kill\"");
        Path claimed = temporary.resolve("claimed");
        Path log = temporary.resolve("consumer.log");
        Process child = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("flow.test-classpath"),
            QueueCrashProbe.class.getName(), claimed.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            Await.await().atMost(Duration.ofSeconds(25)).until(() -> Files.exists(claimed) || !child.isAlive());
            assertTrue(Files.exists(claimed), () -> "独立消费者未领取消息，参见 " + log);
            assertEquals("\"survives-process-kill\"", Files.readString(claimed));
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertNotEquals(0, child.exitValue());
            AtomicReference<String> recovered = new AtomicReference<>();
            Await.await().atMost(Duration.ofSeconds(10)).until(() -> queue.subscribeDispatch("crash", List.of(),
                bytes -> recovered.set(new String(bytes, StandardCharsets.UTF_8))) == 1);
            assertEquals("\"survives-process-kill\"", recovered.get());
            assertEquals(0, queue.subscribeDispatch("crash", List.of(), bytes -> fail("恢复 ACK 后仍有重复消息")));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }
}
