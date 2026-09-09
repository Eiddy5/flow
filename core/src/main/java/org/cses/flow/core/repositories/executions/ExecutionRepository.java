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
     * 原子保存完整聚合；已有行使用本次数据库操作加载的原对象，内部检查加载版本。
     *
     * @param dsl 执行入口提供的上下文，调用方无需单独管理 CAS 会话
     * @param execution 已完成业务修改的完整聚合
     * @throws org.jooq.exception.DataChangedException 加载快照已过期或缺少已有行的加载版本
     * @throws IllegalStateException 数据库操作未建立或已经结束
     */
    void save(DSLContext dsl, Execution execution);
    /**
     * Atomically stores a changed source and one new derived Execution.
     * @param dsl managed database operation used to load the source
     * @param source loaded source after its transition
     * @param derived new snapshot created from that source
     * @throws org.jooq.exception.DataChangedException when the source snapshot is stale
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
