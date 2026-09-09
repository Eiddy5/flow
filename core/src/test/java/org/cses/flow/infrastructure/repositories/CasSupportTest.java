package org.cses.flow.infrastructure.repositories;

import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.exception.DataChangedException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 通用 CAS 元数据测试，不依赖 Execution 的类型或 equals 实现。 */
class CasSupportTest {

    /** 相等对象、可变 hash 和不同根表都不能互相借用加载版本。 */
    @Test
    void tracksObjectIdentityAndTableIndependently() {
        var firstTable = DSL.table("first_root");
        var secondTable = DSL.table("second_root");
        EqualEntity first = new EqualEntity();
        EqualEntity second = new EqualEntity();
        assertEquals(first, second);
        FlowDatabase.execute(DSL.using(SQLDialect.POSTGRES), dsl -> {
            CasSupport.loaded(dsl, firstTable, first, 3);
            CasSupport.loaded(dsl, firstTable, second, 7);
            CasSupport.loaded(dsl, secondTable, first, 11);
            first.value++;
            assertEquals(3L, CasSupport.saving(dsl, firstTable, first));
            assertEquals(7L, CasSupport.saving(dsl, firstTable, second));
            assertEquals(11L, CasSupport.saving(dsl, secondTable, first));
            CasSupport.saved(dsl, firstTable, first, 4);
            assertEquals(4L, CasSupport.saving(dsl, firstTable, first));
            assertEquals("数据已发生变化，请刷新后重试。",
                    assertThrows(DataChangedException.class,
                            () -> CasSupport.saving(dsl, firstTable, first)).getMessage());
            return null;
        });
    }

    /** 内层操作独立，退出后外层凭据仍在，退出全部操作后派生 DSL 也不可复用。 */
    @Test
    void isolatesNestedOperationsAndClosesEscapedContexts() {
        DSLContext database = DSL.using(SQLDialect.POSTGRES);
        var table = DSL.table("root");
        Object entity = new Object();
        AtomicReference<DSLContext> expired = new AtomicReference<>();
        AtomicReference<DSLContext> child = new AtomicReference<>();
        FlowDatabase.execute(database, outer -> {
            expired.set(outer);
            child.set(outer.configuration().derive().dsl());
            CasSupport.loaded(outer, table, entity, 2);
            FlowDatabase.execute(outer, inner -> {
                assertNull(CasSupport.saving(inner, table, entity));
                CasSupport.saved(inner, table, entity, 100);
                return null;
            });
            assertEquals(2L, CasSupport.saving(outer, table, entity));
            return null;
        });
        assertNull(database.configuration().data(CasSupport.class));
        assertNull(expired.get().configuration().data(CasSupport.class));
        assertThrows(IllegalStateException.class, () -> CasSupport.saving(expired.get(), table, entity));
        assertThrows(IllegalStateException.class, () -> CasSupport.saving(child.get(), table, entity));
    }

    /** 异常退出同样清理；会话外读取不偷偷建立缓存或提供写权限。 */
    @Test
    void exceptionalCompletionAndUnmanagedReadsDoNotKeepTokens() {
        DSLContext database = DSL.using(SQLDialect.POSTGRES);
        var table = DSL.table("root");
        Object entity = new Object();
        AtomicReference<DSLContext> expired = new AtomicReference<>();
        CasSupport.loaded(database, table, entity, 3);
        assertNull(database.configuration().data(CasSupport.class));
        assertThrows(IllegalStateException.class, () -> FlowDatabase.execute(database, dsl -> {
            expired.set(dsl.configuration().derive().dsl());
            CasSupport.loaded(dsl, table, entity, 3);
            throw new IllegalStateException("operation failed");
        }));
        assertThrows(IllegalStateException.class, () -> CasSupport.saving(expired.get(), table, entity));
        FlowDatabase.execute(database, dsl -> {
            assertNull(CasSupport.saving(dsl, table, entity));
            return null;
        });
    }

    /** 通用更新保留完整身份条件与加载版本，不依赖 Execution 生成类型。 */
    @Test
    void buildsVersionedUpdateForAnotherRootTable() {
        var table = DSL.table("another_root");
        var tenant = DSL.field("tenant", String.class);
        var id = DSL.field("id", String.class);
        var lock = DSL.field("lock", Long.class);
        FlowDatabase.execute(DSL.using(SQLDialect.POSTGRES), dsl -> {
            var query = CasSupport.update(dsl, table, Map.of("value", "changed"),
                    tenant.eq("company").and(id.eq("row")), lock, 7);
            assertEquals(java.util.List.of("changed", 1L, "company", "row", 7L), query.getBindValues());
            assertTrue(query.getSQL().contains("update another_root"));
            assertTrue(query.getSQL().contains("lock = (lock + ?"));
            assertTrue(query.getSQL().contains("lock = ?"));
            return null;
        });
    }

    /** 模拟按可变业务值比较的另一类领域对象。 */
    private static class EqualEntity {
        private int value;

        /** @return 可变业务值的 hash */
        @Override
        public int hashCode() {
            return value;
        }

        /**
         * 按业务值而不是引用比较。
         * @param other 待比较对象
         * @return 业务值是否相同
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualEntity entity && entity.value == value;
        }
    }
}
