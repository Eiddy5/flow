package org.cses.flow.core.repositories.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.jooq.DSLContext;
import org.cses.flow.core.repositories.worker.ExecutionMessage;

import java.util.List;
import java.util.Optional;

/**
 * Aggregate repository for Execution and its ordered TaskRun collection.
 */
public interface ExecutionRepository {

    /**
     * 查询尚未交给消息系统的计划，不按计划到期时间筛选。
     * @param dsl 数据库上下文
     * @param limit 最大批量
     * @return 待发布的计划
     */
    default List<PendingRetry> pendingRetries(DSLContext dsl, int limit) {
        throw new UnsupportedOperationException("Retry outbox requires durable storage");
    }

    /**
     * Broker 确认后标记已发布；允许重复确认。
     * @param dsl 数据库上下文
     * @param retry 原待发布计划
     * @param now 确认时间
     */
    default void retryPublished(DSLContext dsl, PendingRetry retry, long now) {
        throw new UnsupportedOperationException("Retry outbox requires durable storage");
    }

    /** 持久化的消息发布事实，attemptNumber 为下一次调用编号。 */
    record PendingRetry(String companyId, String executionId, String taskRunId, int attemptNumber, long deliverAt) {
        /**
         * 从可信仓储行恢复计划。
         * @param companyId 租户
         * @param executionId 运行
         * @param taskRunId 任务运行
         * @param attemptNumber 下一次编号
         * @param deliverAt Broker 最早投递时间
         * @return 发布计划
         */
        public static PendingRetry from(String companyId, String executionId, String taskRunId, int attemptNumber, long deliverAt) {
            return new PendingRetry(companyId, executionId, taskRunId, attemptNumber, deliverAt);
        }
    }

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
     * 原子保存聚合及本轮任务消息；普通仓储不支持可靠投递时明确拒绝。
     * @param dsl 调用方数据库上下文
     * @param execution 非空完整聚合，成功后回填版本，失败后应丢弃重读
     * @param messages 非空的同租户同运行消息集合，只读
     * @throws UnsupportedOperationException 实现未提供可靠 Worker outbox 时抛出
     */
    default void save(DSLContext dsl, Execution execution, List<ExecutionMessage> messages) {
        throw new UnsupportedOperationException("Worker outbox requires durable storage");
    }

    /**
     * 消费或取消后删除原重试计划，允许重复清理。
     * @param dsl 调用方数据库上下文
     * @param companyId 原租户身份
     * @param executionId 原运行身份
     * @param taskRunId 原节点身份
     * @param attemptNumber 计划的正数目标尝试编号
     * @throws UnsupportedOperationException 实现未提供可靠重试 outbox 时抛出
     */
    default void retryConsumed(DSLContext dsl, String companyId, String executionId, String taskRunId, int attemptNumber) {
        throw new UnsupportedOperationException("Retry outbox requires durable storage");
    }
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
