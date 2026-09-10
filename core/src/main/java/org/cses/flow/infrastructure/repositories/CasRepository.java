package org.cses.flow.infrastructure.repositories;

import org.cses.flow.core.exceptions.WorkflowException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.ResultQuery;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.DataChangedException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 使用对象自带版本保存聚合的无状态仓储基类；不创建事务或缓存加载对象。 */
public abstract class CasRepository<T> {

    private Table<?> table;
    private Field<String> companyId;
    private Field<String> id;
    private Field<Long> lock;

    /**
     * 指定聚合根表及其完整身份和版本字段；子类负责该根所属子记录的原子 SQL。
     * @param table 聚合根表
     * @param companyId 根表租户字段
     * @param id 根表身份字段
     * @param lock 根表非空技术版本字段
     */
    protected CasRepository(Table<?> table, Field<String> companyId, Field<String> id, Field<Long> lock) {
        this.table = Objects.requireNonNull(table);
        this.companyId = Objects.requireNonNull(companyId);
        this.id = Objects.requireNonNull(id);
        this.lock = Objects.requireNonNull(lock);
    }

    /**
     * 使用对象当前版本原子保存聚合，并仅在 SQL 成功后回填新版本。
     * @param dsl 调用方数据库上下文，不需要 CAS 作用域
     * @param entity 完整聚合；新建版本为 null，已有快照必须保留查询时版本
     * @throws DataChangedException 身份已存在、根已删除或快照陈旧，需重新加载
     * @throws WorkflowException SQL 写入失败；显式事务回滚后必须丢弃内存对象
     */
    public final void save(DSLContext dsl, T entity) {
        save(entity, save(dsl, entity, lock(entity)));
    }

    /**
     * 构造完整聚合的单条保存 SQL，不在此方法执行；基类统一执行并回填版本。
     * @param dsl 调用方数据库上下文
     * @param entity 已完成业务修改的聚合
     * @param expected 查询时版本，null 仅代表新建
     * @return 根和子记录共同成功时返回根版本、冲突时不返回行的 SQL
     */
    protected abstract ResultQuery<Record1<Long>> save(DSLContext dsl, T entity, Long expected);

    /**
     * 读取快照随身携带的版本，不查询数据库或修改对象。
     * @param entity 非空聚合
     * @return 新建时为 null，否则为非负持久化版本
     */
    protected abstract Long lock(T entity);

    /**
     * 将 SQL 返回版本回填到同一个对象，不触发业务行为。
     * @param entity 已成功保存的聚合
     * @param version 数据库返回的非负版本
     */
    protected abstract void lock(T entity, long version);

    /**
     * 用完整字段构造根 INSERT 或 CAS UPDATE，供子类继续组合原子聚合 SQL。
     * @param dsl 调用方数据库上下文
     * @param fields Entry 的完整字段 Map，包含租户和主键；不会修改输入 Map
     * @param expected 查询时版本，null 仅尝试 INSERT，不回退为覆盖更新
     * @return 成功时返回根身份与新版本的 SQL
     * @throws IllegalArgumentException 当版本为负数时抛出
     * @throws DataChangedException 版本已达上限时拒绝保存
     */
    protected final ResultQuery<Record2<String, Long>> save(
            DSLContext dsl, Map<String, Object> fields, Long expected) {
        String tenant = (String) Objects.requireNonNull(fields.get(companyId.getName()), "companyId");
        String identity = (String) Objects.requireNonNull(fields.get(id.getName()), "id");
        Map<String, Object> values = new LinkedHashMap<>(fields);
        values.remove(lock.getName());
        if (expected == null) {
            return dsl.insertInto(table).set(values).set(lock, 0L)
                    .onConflict(companyId, id).doNothing().returningResult(id, lock);
        }
        if (expected < 0) throw new IllegalArgumentException("lock must not be negative");
        if (expected == Long.MAX_VALUE) throw conflict();
        values.remove(companyId.getName());
        values.remove(id.getName());
        return dsl.update(table).set(values).set(lock, lock.add(1L))
                .where(companyId.eq(tenant).and(id.eq(identity)).and(lock.eq(expected)))
                .returningResult(id, lock);
    }

    /**
     * 执行已组装的原子 SQL，检查返回行并回填版本；SQL 失败时保持对象版本不变。
     * @param entity 需要回填根版本的聚合
     * @param statement 同时保存根及所属子记录的单条 SQL
     * @throws DataChangedException 未返回根版本，需重读后重新应用业务操作
     * @throws WorkflowException 数据库拒绝保存，需排查原因并重读对象
     */
    protected final void save(T entity, ResultQuery<Record1<Long>> statement) {
        try {
            Long version = statement.fetchOne(0, Long.class);
            if (version == null) throw conflict();
            lock(entity, version);
        } catch (DataChangedException conflict) {
            throw conflict;
        } catch (DataAccessException failure) {
            throw new WorkflowException("聚合保存失败", failure);
        }
    }

    /** @return 可被现有重试逻辑识别的冲突异常，提示不暴露技术版本 */
    protected final DataChangedException conflict() {
        return new DataChangedException("数据已发生变化，请刷新后重试。");
    }
}
