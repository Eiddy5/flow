package org.cses.flow.core.repositories.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

/**
 * Aggregate repository for Execution and its ordered TaskRun collection.
 */
public interface ExecutionRepository {

    Optional<Execution> findById(
        DSLContext dsl,
        String companyId,
        String executionId
    );

    List<Execution> findAll(DSLContext dsl, String companyId);

    long count(DSLContext dsl, String companyId);

    /**
     * 原子保存完整聚合并回填 lock；已有快照或其副本必须保留查询时版本。
     *
     * @param dsl 调用方数据库上下文，无需 CAS 会话
     * @param execution 已完成业务修改的完整聚合；事务回滚后应丢弃并重读
     * @throws org.jooq.exception.DataChangedException 加载快照已过期或缺少已有行的加载版本
     */
    void save(DSLContext dsl, Execution execution);
    /**
     * 原子保存变更后的源与新派生 Execution，成功后回填双方版本。
     * @param dsl 调用方数据库上下文
     * @param source 携带查询时版本的源对象
     * @param derived 新派生对象，lock 为 null
     * @throws org.jooq.exception.DataChangedException 源快照陈旧或新建/更新版本不符合要求
     */
    void save(DSLContext dsl, Execution source, Execution derived);

    /**
     * Loads all snapshots sharing a root, within the supplied tenant.
     * @param dsl read context
     * @param companyId owning tenant
     * @param originId first Execution ID
     * @return snapshots in creation order, or an empty list
     */
    List<Execution> findByOriginId(DSLContext dsl, String companyId, String originId);

}
