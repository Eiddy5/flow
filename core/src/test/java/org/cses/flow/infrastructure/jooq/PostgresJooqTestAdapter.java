package org.cses.flow.infrastructure.jooq;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.paas.common.util.StringUtil;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.x9.jooq.JOOQ;
import org.x9.jooq.intf.JooqRunnable;
import org.x9.jooq.intf.JooqRunnableResult;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.FLOW_TASKS;
import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.TASK_RUNS;

/**
 * 使用测试拥有的连接池执行真实 PostgreSQL 操作，调用方必须关闭适配器。
 */
public class PostgresJooqTestAdapter extends JOOQ implements AutoCloseable {

    private HikariDataSource dataSource;
    private org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport engine;
    private boolean cleanupEnabled;

    /**
     * 将测试专属连接池交给 JOOQ，保留生产 Entry 映射和显式事务能力。
     *
     * @param dataSource 本适配器拥有的非空连接池，关闭适配器时释放
     * @param cleanupEnabled 是否允许夹具关闭时删除自己创建的租户数据
     */
    private PostgresJooqTestAdapter(
        HikariDataSource dataSource,
        boolean cleanupEnabled
    ) {
        super(
            FlowJooqTestConfiguration.configure(
                DSL.using(SQLDialect.POSTGRES)
            ).configuration(),
            dataSource
        );
        this.dataSource = dataSource;
        this.cleanupEnabled = cleanupEnabled;
    }

    /**
     * 根据显式测试环境创建专属连接池与适配器，不使用宿主默认库。
     * @return 新适配器，调用方必须关闭；连接池不与其他夹具共享
     * @throws IllegalStateException 缺少测试数据库 URL 时抛出
     */
    public static PostgresJooqTestAdapter fromEnvironment() {
        String url = System.getenv("FLOW_POSTGRES_TEST_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                "FLOW_POSTGRES_TEST_URL is required for UC tests"
            );
        }
        return new PostgresJooqTestAdapter(
            dataSource(url,
                System.getenv().getOrDefault("FLOW_POSTGRES_TEST_USER", "flow"),
                System.getenv().getOrDefault("FLOW_POSTGRES_TEST_PASSWORD", "flow")),
            Boolean.parseBoolean(System.getenv().getOrDefault(
                "FLOW_POSTGRES_TEST_CLEANUP",
                "false"
            ))
        );
    }

    /**
     * 为独立技术测试创建可配置的连接池，不创建数据库或表。
     * @param url 非空白 PostgreSQL JDBC URL；调用方负责选择专属测试数据库
     * @param user 测试数据库账号，不作为业务用户
     * @param password 测试凭据，不写入日志
     * @return 调用方拥有且必须关闭的新连接池，每次最多四个连接
     * @throws IllegalArgumentException URL 为空或空白时抛出
     */
    public static HikariDataSource dataSource(String url, String user, String password) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Test database URL is required");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        config.setInitializationFailTimeout(1_000);
        config.setPoolName("flow-test-" + StringUtil.newId());
        return new HikariDataSource(config);
    }

    /**
     * 从本适配器拥有的连接池借出连接，不转移连接池所有权。
     * @return 调用方必须关闭以归还连接池的连接
     * @throws SQLException 连接池关闭或数据库不可用时抛出
     */
    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 返回本适配器拥有的真实轻量 Flow Engine，注册生产生成表并复用原生 CRUD Handler。
     * @return 延迟创建的独占 Engine，随本适配器关闭，不改变 JDBC 所有权
     */
    public synchronized org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport engine() {
        if (engine == null) engine = new org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport();
        return engine;
    }

    /** @return 是否允许最终清理本夹具创建的租户数据 */
    public boolean cleanupEnabled() {
        return cleanupEnabled;
    }

    /**
     * 在只读操作完成后回滚事务并归还连接。
     * @param runnable 使用当前真实上下文的只读操作
     * @param <T> 返回结果类型
     * @return 操作返回值
     */
    @Override
    public <T> T get(JooqRunnableResult<T> runnable) {
        return transaction(false, runnable);
    }

    /**
     * 执行只读列表查询，操作完成后回滚事务并归还连接。
     * @param runnable 使用当前真实上下文的只读查询
     * @param <T> 列表元素类型
     * @return 查询返回列表
     */
    @Override
    public <T> List<T> read(
        JooqRunnableResult<List<T>> runnable
    ) {
        return transaction(false, runnable);
    }

    /**
     * 提交当前操作的真实事务并归还连接。
     * @param runnable 使用当前上下文的写操作
     * @param <T> 返回结果类型
     * @return 成功提交后的操作返回值
     */
    @Override
    public <T> T runReturn(JooqRunnableResult<T> runnable) {
        return transaction(true, runnable);
    }

    /**
     * 提交无返回值的写操作，失败时回滚并归还连接。
     * @param runnable 使用当前真实上下文的写操作
     */
    @Override
    public void run(JooqRunnable runnable) {
        transaction(true, dsl -> {
            runnable.run(dsl);
            return null;
        });
    }

    /**
     * 删除指定测试租户的调度计划、流程定义与运行数据，不操作 Broker 消息。
     * @param companyId 本次测试创建的租户标识；不得传入其他租户
     */
    public void removeTenant(String companyId) {
        transaction(true, dsl -> {
            dsl.deleteFrom(org.flow.gen.flow.Tables.TRIGGER_SCHEDULES)
                .where(org.flow.gen.flow.Tables.TRIGGER_SCHEDULES.COMPANY_ID.eq(companyId)).execute();
            dsl.deleteFrom(org.flow.gen.flow.Tables.TASK_RETRY_MESSAGES)
                .where(org.flow.gen.flow.Tables.TASK_RETRY_MESSAGES.COMPANY_ID.eq(companyId)).execute();
            dsl.deleteFrom(org.flow.gen.flow.Tables.SCHEDULER_EVENTS)
                .where(org.flow.gen.flow.Tables.SCHEDULER_EVENTS.COMPANY_ID.eq(companyId)).execute();
            dsl.deleteFrom(org.flow.gen.flow.Tables.TRIGGER_RUNS)
                .where(org.flow.gen.flow.Tables.TRIGGER_RUNS.COMPANY_ID.eq(companyId)).execute();
            dsl.deleteFrom(TASK_RUNS)
                .where(TASK_RUNS.EXECUTION_ID.in(
                    dsl.select(EXECUTIONS.ID)
                        .from(EXECUTIONS)
                        .where(EXECUTIONS.COMPANY_ID.eq(companyId))
                ))
                .execute();
            dsl.deleteFrom(EXECUTIONS)
                .where(EXECUTIONS.COMPANY_ID.eq(companyId))
                .execute();
            dsl.deleteFrom(FLOW_TASKS)
                .where(FLOW_TASKS.COMPANY_ID.eq(companyId))
                .execute();
            dsl.deleteFrom(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(companyId))
                .execute();
            return null;
        });
    }

    /**
     * 从测试连接池借出连接并执行一次完整操作；失败时回滚。
     * @param commit true 提交成功操作，false 回滚成功的只读操作
     * @param runnable 当前事务中执行的操作，不保留连接
     * @param <T> 操作返回类型
     * @return 操作返回值
     * @throws IllegalStateException 数据库连接或 JDBC 操作失败时抛出
     */
    private <T> T transaction(
        boolean commit,
        JooqRunnableResult<T> runnable
    ) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                var plainDsl = DSL.using(connection, SQLDialect.POSTGRES);
                T result = runnable.run(
                    FlowJooqTestConfiguration.configure(plainDsl)
                );
                if (commit) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return result;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw new IllegalStateException(
                    "PostgreSQL UC operation failed",
                    exception
                );
            } catch (RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Cannot access PostgreSQL UC database",
                exception
            );
        }
    }

    /**
     * 回滚失败操作，并把回滚失败附加到原始异常。
     * @param connection 当前事务连接，不能为 null
     * @param failure 原始失败，不替换它
     */
    private static void rollback(
        Connection connection,
        Throwable failure
    ) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    /** 关闭测试拥有的连接池；已经关闭时重复调用无额外副作用。 */
    @Override
    public void close() {
        try {
            if (engine != null) engine.close();
        } finally {
            dataSource.close();
        }
    }
}
