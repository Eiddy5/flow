package org.cses.flow.infrastructure.datapilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dataPilot.conf.DataSourceType;
import org.dataPilot.conf.DbType;
import org.dataPilot.conf.option.DbDataSourceOption;
import org.junit.jupiter.api.Test;

class FlowDataSourceEngineTest {

    /** 具名 Flow 源选择生产 public 元数据，不路由到宿主默认源。 */
    @Test
    void configuresFlowPostgreSqlDataSource() {
        FlowDataSourceEngine engine = new FlowDataSourceEngine();
        DbDataSourceOption option = engine.flowDatasource();

        assertEquals("flow", option.dbName);
        assertEquals("flow", option.name);
        assertEquals("public", option.jooqSchema.getName());
        assertEquals(DataSourceType.orm, option.type);
        assertEquals(DbType.postgresql, option.dbType);
        engine.asyncExecutor.shutdownNow();
    }

    /** 引擎声明与管理源都使用 Flow 自有键，保留既有调试及追踪配置。 */
    @Test
    void configuresFlowEngineDefaults() {
        FlowDataSourceEngine engine = new FlowDataSourceEngine();

        engine.configureFlow(engine);

        assertEquals(FlowDataSourceEngine.ENGINE_KEY, engine.getKey());
        assertEquals(DataSourceKey.POSTGRESQL, engine.defaultDBDataSource);
        assertEquals(DataSourceKey.POSTGRESQL, engine.managerDataSourceKey);
        assertFalse(engine.isDebug);
        assertTrue(engine.openTrace);
        assertFalse(engine.openDetailTrace);
        engine.asyncExecutor.shutdownNow();
    }
}
