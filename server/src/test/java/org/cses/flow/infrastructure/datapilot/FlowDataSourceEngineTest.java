package org.cses.flow.infrastructure.datapilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dataPilot.conf.DataSourceType;
import org.dataPilot.conf.DbType;
import org.dataPilot.conf.option.DbDataSourceOption;
import org.junit.jupiter.api.Test;

final class FlowDataSourceEngineTest {

    @Test
    void configuresFlowPostgreSqlDataSource() {
        FlowDataSourceEngine engine = new FlowDataSourceEngine();
        DbDataSourceOption option = engine.postgreSqlOption();

        assertEquals("flow", option.dbName);
        assertEquals("default", option.name);
        assertEquals("public", option.jooqSchema.getName());
        assertEquals(DataSourceType.orm, option.type);
        assertEquals(DbType.postgresql, option.dbType);
    }

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
    }
}
