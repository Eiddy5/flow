package org.cses.flow.core.repositories.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Aggregate repository for Execution and its ordered TaskRun collection.
 */
public interface ExecutionRepository {

    /**
     * 在独立仓储会话中加载并保存聚合；结束时释放加载元数据，不开启数据库事务。
     * 显式事务调用方必须在操作返回前完成提交或回滚，不能跨事务复用会话。
     *
     * @param <T> 操作返回类型
     * @param dsl 调用方数据库上下文
     * @param operation 使用传入会话 DSL 完成的操作
     * @return 操作结果，返回的领域对象不携带可跨会话复用的写权限
     */
    <T> T inScope(DSLContext dsl, Function<DSLContext, T> operation);

    Optional<Execution> findById(
        DSLContext dsl,
        String companyId,
        String executionId
    );

    List<Execution> findAll(DSLContext dsl, String companyId);

    long count(DSLContext dsl, String companyId);

    /**
     * 在当前仓储会话内原子保存完整聚合；已有行必须使用本会话加载的原对象。
     *
     * @param dsl 当前仓储会话提供的上下文
     * @param execution 已完成业务修改的完整聚合
     * @throws org.jooq.exception.DataChangedException 加载快照已过期或缺少已有行的加载版本
     * @throws IllegalStateException 未进入仓储会话或会话已结束
     */
    void save(DSLContext dsl, Execution execution);
}
