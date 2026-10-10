package org.cses.flow.infrastructure.repositories;

import org.cses.flow.infrastructure.datapilot.FlowDataSourceEngineTestSupport;
import org.cses.flow.infrastructure.repositories.enhancers.CasEnhancer;
import org.cses.flow.infrastructure.repositories.executions.ExecutionRepositoryImpl;
import org.cses.flow.infrastructure.repositories.flows.FlowRepositoryImpl;
import org.cses.flow.infrastructure.repositories.triggers.TriggerRepositoryImpl;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 业务仓储负责租户和技术身份，基础 CRUD 只处理数据库元数据。 */
class BusinessRepositoryIdentityTest {
    private FlowDataSourceEngineTestSupport engine = new FlowDataSourceEngineTestSupport();

    /** 关闭此技术测试拥有的 Engine 资源。 */
    @AfterEach
    void closeEngine() { engine.close(); }

    /** 三种 Core 业务仓储在进入 Builder 前拒绝空或空白租户与行身份。 */
    @Test
    void rejectsBlankBusinessIdentityBeforeCreatingCrudBuilders() {
        var flow = new FlowRepositoryImpl(engine);
        var execution = new ExecutionRepositoryImpl(engine, new CasEnhancer());
        var trigger = new TriggerRepositoryImpl(engine);
        var dsl = DSL.using(SQLDialect.POSTGRES);
        for (String invalid : new String[]{null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> flow.findById(dsl, invalid, "row"));
            assertThrows(IllegalArgumentException.class, () -> flow.findById(dsl, "tenant", invalid));
            assertThrows(IllegalArgumentException.class, () -> execution.findById(dsl, invalid, "row"));
            assertThrows(IllegalArgumentException.class, () -> execution.findById(dsl, "tenant", invalid));
            assertThrows(IllegalArgumentException.class, () -> trigger.find(dsl, invalid, "row"));
            assertThrows(IllegalArgumentException.class, () -> trigger.find(dsl, "tenant", invalid));
        }
        assertEquals(0, engine.queries());
        assertEquals(0, engine.updates());
        assertEquals(0, engine.executions());
    }
}
