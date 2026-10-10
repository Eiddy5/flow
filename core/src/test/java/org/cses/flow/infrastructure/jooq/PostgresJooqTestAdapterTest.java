package org.cses.flow.infrastructure.jooq;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实测试池的借用归还、失败归还及最终所有者关闭。 */
@EnabledIfEnvironmentVariable(named = "FLOW_POSTGRES_TEST_URL", matches = ".+")
class PostgresJooqTestAdapterTest {
    /**
     * 成功和失败操作均归还连接；关闭适配器使同一池不可再借用且支持重复关闭。
     * @throws SQLException 实际连接或 unwrap 失败时抛出
     */
    @Test
    void returnsBorrowedConnectionsAndClosesTheOwnedPool() throws SQLException {
        HikariDataSource source;
        org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport engine;
        try (var adapter = PostgresJooqTestAdapter.fromEnvironment()) {
            engine = adapter.engine();
            source = adapter.createDSLContext().getDataSource().unwrap(HikariDataSource.class);
            try (var connection = adapter.connection()) {
                assertTrue(connection.isValid(2));
                assertEquals(1, source.getHikariPoolMXBean().getActiveConnections());
            }
            assertEquals(0, source.getHikariPoolMXBean().getActiveConnections());
            Integer answer = adapter.get(dsl -> dsl.fetchOne("select 42").get(0, Integer.class));
            assertEquals(Integer.valueOf(42), answer);
            assertEquals(0, source.getHikariPoolMXBean().getActiveConnections());
            var failure = new IllegalStateException("owned-pool-operation-failure");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> adapter.run(dsl -> {
                dsl.fetchOne("select 1");
                throw failure;
            })));
            assertEquals(0, source.getHikariPoolMXBean().getActiveConnections());
            assertFalse(source.isClosed());
            assertFalse(engine.asyncExecutor.isShutdown());
        }
        assertTrue(source.isClosed());
        assertTrue(engine.asyncExecutor.isShutdown());
        assertThrows(SQLException.class, source::getConnection);
        source.close();
        assertTrue(source.isClosed());
    }
}
