package org.cses.flow.infrastructure.repositories;

import org.cses.flow.core.exceptions.WorkflowException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.exception.DataChangedException;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 用另一种根对象验证基类公开 save，不依赖 Execution、缓存或作用域。 */
class CasRepositoryTest {

    /** 新建与更新共用基类 save，版本随对象回填，完整身份与版本条件进入 SQL。 */
    @Test
    void savesAnotherAggregateThroughTheInheritedTemplate() {
        var sql = new ArrayList<String>();
        var bindings = new ArrayList<List<Object>>();
        DSLContext records = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            bindings.add(List.of(context.bindings()));
            var result = records.newResult(AnotherRepository.LOCK);
            result.add(records.newRecord(AnotherRepository.LOCK).values((long) sql.size() - 1));
            return new MockResult[]{new MockResult(1, result)};
        }), SQLDialect.POSTGRES);
        var repository = new AnotherRepository();
        var entity = new AnotherEntity();
        repository.save(dsl, entity);
        assertEquals(0L, entity.lock);
        repository.save(dsl, entity);
        assertEquals(1L, entity.lock);
        assertTrue(sql.getFirst().contains("insert into another_root"));
        assertTrue(sql.getFirst().contains("on conflict (company_id, id) do nothing"));
        assertTrue(sql.getLast().contains("lock = (lock + ?"));
        assertTrue(sql.getLast().contains("company_id = ?"));
        assertTrue(sql.getLast().contains("id = ?"));
        assertTrue(sql.getLast().contains("lock = ?"));
        assertEquals(List.of("changed", 1L, "company", "row", 0L), bindings.getLast());
    }

    /** 零行返回统一冲突文字，数据库异常也不推进对象版本。 */
    @Test
    void failedSavesLeaveTheSnapshotVersionUnchanged() {
        var repository = new AnotherRepository();
        var entity = new AnotherEntity();
        entity.lock = 7L;
        DSLContext missing = DSL.using(new MockConnection(context -> new MockResult[]{
                new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(AnotherRepository.LOCK))
        }), SQLDialect.POSTGRES);
        assertEquals("数据已发生变化，请刷新后重试。",
                assertThrows(DataChangedException.class, () -> repository.save(missing, entity)).getMessage());
        assertEquals(7L, entity.lock);
        DSLContext rejected = DSL.using(new MockConnection(context -> {
            throw new SQLException("rejected");
        }), SQLDialect.POSTGRES);
        assertThrows(WorkflowException.class, () -> repository.save(rejected, entity));
        assertEquals(7L, entity.lock);
    }

    /** 非法或溢出版本在发 SQL 前拒绝，不能转为新建。 */
    @Test
    void rejectsInvalidAndExhaustedVersions() {
        var repository = new AnotherRepository();
        var entity = new AnotherEntity();
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
        entity.lock = -1L;
        assertThrows(IllegalArgumentException.class, () -> repository.save(dsl, entity));
        entity.lock = Long.MAX_VALUE;
        assertThrows(DataChangedException.class, () -> repository.save(dsl, entity));
        assertEquals(Long.MAX_VALUE, entity.lock);
    }

    private static class AnotherEntity {
        Long lock;
    }

    private static class AnotherRepository extends CasRepository<AnotherEntity> {
        private static Table<?> ROOT = DSL.table("another_root");
        private static Field<String> COMPANY = DSL.field("company_id", String.class);
        private static Field<String> ID = DSL.field("id", String.class);
        private static Field<Long> LOCK = DSL.field("lock", Long.class);

        /** 绑定另一张根表，验证基类不依赖 Execution 的生成类型。 */
        private AnotherRepository() {
            super(ROOT, COMPANY, ID, LOCK);
        }

        /**
         * @param entity 测试快照
         * @return 快照自带版本
         */
        @Override
        protected Long lock(AnotherEntity entity) {
            return entity.lock;
        }

        /**
         * @param entity 已保存快照
         * @param version SQL 返回版本
         */
        @Override
        protected void lock(AnotherEntity entity, long version) {
            entity.lock = version;
        }

        /**
         * @param dsl 测试上下文
         * @param entity 测试快照
         * @param expected 原版本
         * @return 根保存 SQL
         */
        @Override
        protected ResultQuery<Record1<Long>> save(DSLContext dsl, AnotherEntity entity, Long expected) {
            var root = DSL.name("saved").as(save(dsl,
                    Map.of("company_id", "company", "id", "row", "value", "changed"), expected));
            return dsl.with(root).select(root.field(LOCK)).from(root);
        }
    }
}
