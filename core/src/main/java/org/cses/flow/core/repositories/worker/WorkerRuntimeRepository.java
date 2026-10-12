package org.cses.flow.core.repositories.worker;

import org.jooq.DSLContext;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** 仅承载 Worker 许可和消息交接，不修改聚合业务状态。 */
public interface WorkerRuntimeRepository {
    /**
     * 在 Flow 引擎选定的同一连接执行短技术事务，失败时整体回滚。
     * @param <T> 回调返回类型
     * @param dsl 调用方拥有的数据库上下文，不关闭其连接
     * @param operation 非空短事务回调，不得跨 Runner 或 Broker
     * @return 回调原返回值
     * @throws RuntimeException 回调失败时原异常继续传播，调用方应丢弃已修改聚合
     */
    <T> T transaction(DSLContext dsl, Function<DSLContext, T> operation);
    /**
     * 按完整租户和节点身份读取当前许可。
     * @param dsl 调用方数据库上下文
     * @param companyId 非空白原租户身份
     * @param executionId 非空白原实例身份
     * @param taskRunId 非空白原节点身份
     * @param lock true 在短事务内排他读取，false 普通读取
     * @return 不存在时为空，否则为独立许可快照
     */
    Optional<WorkerDelivery> delivery(DSLContext dsl, String companyId, String executionId, String taskRunId, boolean lock);
    /**
     * 保存由 Executor 核验的完整当前许可；调用方须完成根 CAS 或持有当前许可锁。
     * @param dsl 当前短事务上下文
     * @param delivery 非空完整许可，只读
     */
    void saveDelivery(DSLContext dsl, WorkerDelivery delivery);
    /**
     * 保存稳定消息，身份冲突不覆盖既有正文。
     * @param dsl 当前短事务上下文
     * @param message 非空完整消息，只读
     */
    void add(DSLContext dsl, ExecutionMessage message);
    /**
     * 恢复待消费消息，不重建已删除内容。
     * @param dsl 调用方数据库上下文
     * @param companyId 非空白原租户身份
     * @param id 非空白稳定消息身份
     * @return 完整消息，消费后或不存在时为空
     */
    Optional<ExecutionMessage> message(DSLContext dsl, String companyId, String id);
    /**
     * 按创建顺序读取尚未得到 Broker 确认的消息。
     * @param dsl 调用方数据库上下文
     * @param limit 正数批量上限
     * @return 不超过 limit 的独立消息快照，可能为空
     * @throws IllegalArgumentException 批量上限小于 1 时抛出
     */
    List<ExecutionMessage> pending(DSLContext dsl, int limit);
    /**
     * Broker 确认后条件标记，不重建已消费删除的行。
     * @param dsl 调用方数据库上下文
     * @param companyId 非空白原租户身份
     * @param id 非空白稳定消息身份
     * @param now Broker 确认 UTC Unix 毫秒值
     */
    void published(DSLContext dsl, String companyId, String id, long now);
    /**
     * 消费处理成功后物理删除指定消息，重复调用无副作用。
     * @param dsl 当前短事务或调用方数据库上下文
     * @param companyId 非空白原租户身份
     * @param id 非空白稳定消息身份
     */
    void consumed(DSLContext dsl, String companyId, String id);
    /**
     * 仅当前 owner、代次及未过期许可可续租，过期许可不会复活。
     * @param dsl 调用方数据库上下文
     * @param delivery 非空原领取许可，用于核验身份，不修改
     * @param now 当前 UTC Unix 毫秒值
     * @param until 目标到期 UTC Unix 毫秒值，必须晚于 now
     * @return true 续租成功，false 已失去许可
     */
    boolean renew(DSLContext dsl, WorkerDelivery delivery, long now, long until);
}
