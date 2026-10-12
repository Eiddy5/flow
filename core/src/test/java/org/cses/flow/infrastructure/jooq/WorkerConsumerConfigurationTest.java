package org.cses.flow.infrastructure.jooq;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.executor.ExecutorService;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.executor.ExecutionOutboxPublisher;
import org.cses.flow.executor.WorkerResultConsumer;
import org.cses.flow.infrastructure.queues.pulsar.PulsarQueueListeners;
import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.cses.flow.worker.WorkerCommand;
import org.cses.flow.worker.WorkerCommandConsumer;
import org.cses.flow.worker.WorkerResultEvent;
import org.flow.gen.flow.Tables;
import org.junit.jupiter.api.Test;
import org.x9.jooq.JOOQ;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/** 真实具名 Flow 数据库下的全局消费开关装配验证，不作为 UC 场景。 */
class WorkerConsumerConfigurationTest {
    private static final String CONSUMER_ENABLED = "pulsar.consumer.enabled";
    private static final String TOPIC_PREFIX = "persistent://public/default/";
    private static final String POSTGRES_URL = "FLOW_POSTGRES_TEST_URL";
    private static final String POSTGRES_USER = "FLOW_POSTGRES_TEST_USER";
    private static final String POSTGRES_PASSWORD = "FLOW_POSTGRES_TEST_PASSWORD";
    private static final String POSTGRES_HOST_URL = "FLOW_POSTGRES_DEFAULT_TEST_URL";
    private static final String FLOW_ENABLED = "datasources.flow.enabled";
    private static final String FLOW_URL = "datasources.flow.url";
    private static final String FLOW_USERNAME = "datasources.flow.username";
    private static final String FLOW_PASSWORD = "datasources.flow.password";
    private static final String FLOW_DRIVER = "datasources.flow.driver-class-name";
    private static final String FLOW_MAXIMUM_POOL = "datasources.flow.maximum-pool-size";
    private static final String FLOW_MINIMUM_IDLE = "datasources.flow.minimum-idle";
    private static final String FLOW_DIALECT = "jooq.datasources.flow.sql-dialect";
    private static final String HOST_DATA_SOURCE_NAME = "default";
    private static final String HOST_ENABLED = "datasources.default.enabled";
    private static final String HOST_URL = "datasources.default.url";
    private static final String HOST_USERNAME = "datasources.default.username";
    private static final String HOST_PASSWORD = "datasources.default.password";
    private static final String HOST_DRIVER = "datasources.default.driver-class-name";
    private static final String HOST_MAXIMUM_POOL = "datasources.default.maximum-pool-size";
    private static final String HOST_MINIMUM_IDLE = "datasources.default.minimum-idle";
    private static final String HOST_DIALECT = "jooq.datasources.default.sql-dialect";

    /**
     * 全局停用消费者时真实应用可启动，保留公开服务与 Flow 库，不装配 Worker 定时续租或发布器。
     * @throws Exception 真实测试 Broker 或数据库不可用时抛出
     */
    @Test
    void disabledConsumersKeepDatabaseAndPublicServicesAvailable() throws Exception {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.openWithRuntimeDataSource(properties(false));
             var admin = PulsarTestEnvironment.admin()) {
            assertNamedDatabaseAndServices(fixture);
            assertTrue(fixture.findBean(PulsarQueueListeners.class).isEmpty());
            assertTrue(fixture.findBean(WorkerCommandConsumer.class).isEmpty(),
                "Worker 未装配，因此其 Scheduled 续租与命令消费入口均不运行");
            assertTrue(fixture.findBean(ExecutionOutboxPublisher.class).isEmpty());
            for (String queue : new String[]{WorkerCommand.QUEUE_NAME, WorkerResultEvent.QUEUE_NAME}) {
                try {
                    var subscriptions = admin.topics().getStats(TOPIC_PREFIX + queue).getSubscriptions();
                    assertTrue(subscriptions.values().stream().allMatch(value -> value.getConsumers().isEmpty()),
                        "全局关闭时不得创建 Worker 命令或结果消费者");
                } catch (org.apache.pulsar.client.admin.PulsarAdminException.NotFoundException ignored) {
                    // 独立执行本场景时允许主题尚未创建；该事实同样表明没有消费者。
                }
            }
        }
    }

    /**
     * 正常启用时具名数据库装配真实双队列组件，公开启动一个 Log 流程并消费清理全部 outbox。
     * @throws java.sql.SQLException 真实 Flow 连接池解包失败时抛出
     */
    @Test
    void enabledConsumersWireBothQueuesAndCompleteAnExecution() throws java.sql.SQLException {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.openWithRuntimeDataSource(properties(true))) {
            assertNamedDatabaseAndServices(fixture);
            assertNotNull(fixture.bean(WorkerCommandConsumer.class));
            assertNotNull(fixture.bean(WorkerResultConsumer.class));
            assertNotNull(fixture.bean(ExecutionOutboxPublisher.class));
            var listeners = fixture.bean(PulsarQueueListeners.class);
            assertTrue(listeners.isReady(WorkerCommand.QUEUE_NAME, WorkerCommand.QUEUE_NAME));
            assertTrue(listeners.isReady(WorkerResultEvent.QUEUE_NAME, WorkerResultEvent.QUEUE_NAME));
            var flow = fixture.deploy("""
                key: worker-consumer-configuration
                tasks:
                  - key: observe
                    type: org.cses.flow.extensions.log.Log
                    message: consumer-enabled
                """);
            String executionId = fixture.startCreated(flow).id();
            Execution done = fixture.awaitExecution(fixture.session(), executionId, Execution::isTerminal);
            assertEquals(State.Type.SUCCESS, done.state().current());
            assertEquals(1, done.taskRuns().size());
            assertEquals(1, done.taskRuns().getFirst().attempts().size());
            assertTrue(done.activeTaskRuns().isEmpty());
            JOOQ database = fixture.bean(JOOQ.class, FlowDatabase.DATA_SOURCE_NAME);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            int remaining;
            do {
                remaining = database.get(dsl -> dsl.fetchCount(Tables.EXECUTION_MESSAGES,
                    Tables.EXECUTION_MESSAGES.EXECUTION_ID.eq(executionId)));
                if (remaining == 0) break;
                assertTrue(System.nanoTime() < deadline, "成功消费后应删除本实例全部 outbox");
                LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
            } while (true);
            assertEquals(0, remaining);
        }
    }

    /**
     * 核对标准 Flow 数据源和生产 JOOQ 真实连接 PostgreSQL，三个公开服务均可获取。
     * @param fixture 已启动的当前真实上下文
     * @throws java.sql.SQLException 真实 Flow 连接池解包失败时抛出
     */
    private void assertNamedDatabaseAndServices(WorkflowUcFixture fixture) throws java.sql.SQLException {
        DataSource flowSource = fixture.bean(DataSource.class, FlowDatabase.DATA_SOURCE_NAME);
        assertNotSame(fixture.bean(DataSource.class, HOST_DATA_SOURCE_NAME), flowSource,
            "真实 Flow 池与本任务空宿主池必须独立");
        JOOQ database = fixture.bean(JOOQ.class, FlowDatabase.DATA_SOURCE_NAME);
        assertFalse(database instanceof PostgresJooqTestAdapter,
            "本测试必须使用标准具名数据源装配，而非直连测试 JOOQ 替身");
        int observed = database.get(dsl -> dsl.selectOne().fetchSingle().value1());
        assertEquals(1, observed);
        assertSame(flowSource.unwrap(io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource.class),
            database.createDSLContext().getDataSource().unwrap(io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource.class));
        assertNotNull(fixture.bean(FlowService.class));
        assertNotNull(fixture.bean(ExecutionService.class));
        assertNotNull(fixture.bean(ExecutorService.class));
    }

    /**
     * 装配真实专属 Flow 数据源与明确的全局消费开关，不输出连接凭据。
     * @param enabled 是否装配真实消费者
     * @return 当前场景独立配置
     */
    private Map<String, Object> properties(boolean enabled) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(CONSUMER_ENABLED, enabled);
        properties.put(FLOW_ENABLED, true);
        properties.put(FLOW_URL, required(POSTGRES_URL));
        properties.put(FLOW_USERNAME, required(POSTGRES_USER));
        properties.put(FLOW_PASSWORD, required(POSTGRES_PASSWORD));
        properties.put(FLOW_DRIVER, org.postgresql.Driver.class.getName());
        properties.put(FLOW_MAXIMUM_POOL, 3);
        properties.put(FLOW_MINIMUM_IDLE, 0);
        properties.put(FLOW_DIALECT, org.jooq.SQLDialect.POSTGRES.name());
        properties.put(HOST_ENABLED, true);
        properties.put(HOST_URL, required(POSTGRES_HOST_URL));
        properties.put(HOST_USERNAME, required(POSTGRES_USER));
        properties.put(HOST_PASSWORD, required(POSTGRES_PASSWORD));
        properties.put(HOST_DRIVER, org.postgresql.Driver.class.getName());
        properties.put(HOST_MAXIMUM_POOL, 1);
        properties.put(HOST_MINIMUM_IDLE, 0);
        properties.put(HOST_DIALECT, org.jooq.SQLDialect.POSTGRES.name());
        return properties;
    }

    /**
     * 读取显式专属验证环境参数，不回退到共享服务。
     * @param name 必填环境变量名称
     * @return 当前环境中的非空配置值
     * @throws IllegalStateException 缺少明确验证配置时抛出
     */
    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
