package org.cses.flow.infrastructure.repositories;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TransactionContext;
import org.jooq.UpdateConditionStep;
import org.jooq.exception.DataChangedException;
import org.jooq.impl.DefaultTransactionListener;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 由数据库执行入口管理的 CAS 元数据；不保存领域快照，不开启数据库事务。 */
public class CasSupport extends DefaultTransactionListener implements AutoCloseable {

    private DSLContext dsl;
    private Map<IdentityKey, Long> versions = new HashMap<>();
    private ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private boolean closed;
    private int transactionDepth;

    /**
     * 为入口派生独立配置，保留已有事务监听器。
     * @param source 调用方上下文，不能跨线程共享派生后的操作上下文
     */
    public CasSupport(DSLContext source) {
        dsl = source.configuration().deriveAppending(this).dsl();
        dsl.configuration().data(CasSupport.class, this);
    }

    /** @return 本次操作的上下文；调用方必须在操作结束时关闭本对象 */
    public DSLContext dsl() {
        return dsl;
    }

    /**
     * 登记同一次数据库读取产生的对象与版本；普通会话外查询不登记。
     * @param dsl 当前操作上下文
     * @param table 聚合根表，隔离不同仓储的版本空间
     * @param entity 本次读取创建的原对象，不按 equals 比较
     * @param version 与该快照一起读取的非负版本
     */
    public static void loaded(DSLContext dsl, Table<?> table, Object entity, long version) {
        CasSupport context = context(dsl);
        if (context != null) {
            context.put(table, entity, version);
        }
    }

    /**
     * 取得加载版本并先标记本次保存不可重试；成功后由 saved 更新。
     * @param dsl 当前操作上下文
     * @param table 聚合根表
     * @param entity 待保存原对象
     * @return 加载版本；null 只允许尝试 INSERT，不能更新已有行
     * @throws DataChangedException 上次保存失败或当前对象已失效
     * @throws IllegalStateException 未进入受管理的数据库操作
     */
    public static Long saving(DSLContext dsl, Table<?> table, Object entity) {
        Long version = require(dsl).put(table, entity, -1L);
        if (version != null && version < 0) {
            throw conflict();
        }
        return version;
    }

    /**
     * 保存成功后推进当前对象版本，其他查询副本保持原版本。
     * @param dsl 当前操作上下文
     * @param table 聚合根表
     * @param entity 成功保存的原对象
     * @param version 数据库写入后返回的版本
     */
    public static void saved(DSLContext dsl, Table<?> table, Object entity, long version) {
        require(dsl).put(table, entity, version);
    }

    /**
     * 构建完整身份约束下的 CAS 更新，由仓储继续组装原子聚合 SQL。
     * @param <R> 根表记录类型
     * @param dsl 当前操作上下文
     * @param table 聚合根表
     * @param values 已排除主键的待保存字段
     * @param identity 完整租户与主键条件，不得传入无条件谓词
     * @param lock 技术版本字段
     * @param expected 加载时版本
     * @return 尚未执行的根更新，版本比较与递增不可省略
     */
    public static <R extends Record> UpdateConditionStep<R> update(
            DSLContext dsl, Table<R> table, Map<String, Object> values,
            Condition identity, Field<Long> lock, long expected) {
        return dsl.update(table).set(values).set(lock, lock.add(1L))
                .where(identity).and(lock.eq(expected));
    }

    /** @return 保持可被现有重试逻辑识别、且不向用户暴露技术版本的冲突异常 */
    public static DataChangedException conflict() {
        return new DataChangedException("数据已发生变化，请刷新后重试。");
    }

    /**
     * 读取有效上下文，不为普通读取隐式创建长生命周期缓存。
     * @param dsl 调用上下文
     * @return 有效上下文，未建立或已关闭时为 null
     */
    private static CasSupport context(DSLContext dsl) {
        CasSupport context = (CasSupport) dsl.configuration().data(CasSupport.class);
        return context == null || context.closed ? null : context;
    }

    /**
     * 要求写入由数据库执行入口管理。
     * @param dsl 调用上下文
     * @return 当前有效上下文
     * @throws IllegalStateException 操作不存在或已经结束
     */
    private static CasSupport require(DSLContext dsl) {
        CasSupport context = context(dsl);
        if (context == null) {
            throw new IllegalStateException("Repository write requires an active database operation");
        }
        return context;
    }

    /**
     * 清除已回收对象的元数据，再按弱引用身份写入版本。
     * @param table 根表
     * @param entity 原对象
     * @param version 新版本或保存失败标记
     * @return 该对象之前的版本，无登记时为 null
     */
    private Long put(Table<?> table, Object entity, long version) {
        Object removed;
        while ((removed = collected.poll()) != null) {
            versions.remove(removed);
        }
        return versions.put(new IdentityKey(table, entity, collected), version);
    }

    /** @param context 本次事务；记录嵌套层数，避免 savepoint 提交提前清理 */
    @Override
    public void beginStart(TransactionContext context) {
        if (context.configuration().data(CasSupport.class) == this) {
            transactionDepth++;
        }
    }

    /** @param context 已提交事务；最外层事务结束后使加载凭据失效 */
    @Override
    public void commitEnd(TransactionContext context) {
        if (context.configuration().data(CasSupport.class) == this && --transactionDepth == 0) {
            close();
        }
    }

    /** @param context 已回滚事务；含 savepoint 回滚，旧对象必须重新进入操作并加载 */
    @Override
    public void rollbackEnd(TransactionContext context) {
        if (context.configuration().data(CasSupport.class) == this) {
            close();
        }
    }

    /** 清理加载凭据；派生配置持有的同一上下文也立即失效，重复关闭无副作用。 */
    @Override
    public void close() {
        closed = true;
        versions.clear();
        while (collected.poll() != null) {
            // 丢弃已回收对象的引用，不保留操作结束后的元数据。
        }
        dsl.configuration().data().remove(CasSupport.class);
    }

    /** 身份比较不调用领域 equals/hashCode，也不强引用领域对象。 */
    private static class IdentityKey extends WeakReference<Object> {
        private Table<?> table;
        private int hash;

        /**
         * 建立根表与原对象身份的弱键。
         * @param table 根表
         * @param entity 非空原对象
         * @param queue 对象回收通知队列
         */
        private IdentityKey(Table<?> table, Object entity, ReferenceQueue<Object> queue) {
            super(Objects.requireNonNull(entity, "entity"), queue);
            this.table = Objects.requireNonNull(table, "table");
            hash = 31 * table.hashCode() + System.identityHashCode(entity);
        }

        /** @return 创建时固定的身份 hash，不随对象业务字段变化 */
        @Override
        public int hashCode() {
            return hash;
        }

        /**
         * 按根表与引用身份比较；已回收的键仅与自身相等。
         * @param other 待比较对象
         * @return 是否为同一个根表的同一个存活对象，或键自身
         */
        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof IdentityKey key
                    && get() != null && get() == key.get() && table.equals(key.table);
        }
    }
}
