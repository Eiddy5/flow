package org.cses.flow.infrastructure.repositories.worker;

import jakarta.inject.Singleton;
import org.cses.flow.common.Fields;
import org.cses.flow.core.repositories.worker.ExecutionMessage;
import org.cses.flow.core.repositories.worker.WorkerDelivery;
import org.cses.flow.core.repositories.worker.WorkerRuntimeRepository;
import org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine;
import org.cses.flow.infrastructure.repositories.BaseRepository;
import org.cses.flow.infrastructure.repositories.worker.entries.ExecutionMessageEntry;
import org.cses.flow.infrastructure.repositories.worker.entries.WorkerDeliveryEntry;
import org.jooq.Condition;
import org.jooq.DSLContext;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import static org.flow.gen.flow.Tables.EXECUTION_MESSAGES;
import static org.flow.gen.flow.Tables.WORKER_DELIVERIES;

/** 通过 Flow Engine 管理短技术事务、执行许可和消费后消息删除。 */
@Singleton
public class WorkerRuntimeRepositoryImpl extends BaseRepository implements WorkerRuntimeRepository {
    /** 绑定纯 CRUD 基础与消息表元数据，不取得连接所有权。
     * @param engine 非空 Flow DataSourceEngine，复用调用方数据库上下文
     */
    public WorkerRuntimeRepositoryImpl(FlowDataSourceEngine engine) { super(engine, EXECUTION_MESSAGES); }
    /**
     * 在 Engine 借用的同一连接执行短事务，业务调用和 Broker 发送不得进入回调。
     * @param <T> 短技术操作的返回类型
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param operation 在同一连接执行的短事务回调，不得调用 Runner 或 Broker
     * @return 短事务回调的原返回值，失败时事务整体回滚
     * @throws RuntimeException 回调失败时原异常传播，调用方应丢弃已修改的聚合
     */
    @Override
    public <T> T transaction(DSLContext dsl, Function<DSLContext, T> operation) {
        return execute(dsl, context -> context.transactionResult(configuration -> operation.apply(configuration.dsl())));
    }
    /**
     * 使用生成字段限定租户和节点，可在短事务内排他读取许可。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param executionId 非空白的原运行身份，不生成或更改
     * @param taskRunId 非空白的原任务运行身份，不生成或更改
     * @param lock true 在当前事务中锁定许可，false 仅普通读取
     * @return 当前许可；不存在时为空
     */
    @Override
    public Optional<WorkerDelivery> delivery(DSLContext dsl, String companyId, String executionId, String taskRunId, boolean lock) {
        return execute(dsl, context -> {
            var query = context.selectFrom(WORKER_DELIVERIES).where(identity(companyId, executionId, taskRunId));
            WorkerDeliveryEntry entry = lock ? query.forUpdate().fetchOneInto(WorkerDeliveryEntry.class) : query.fetchOneInto(WorkerDeliveryEntry.class);
            return Optional.ofNullable(entry).map(WorkerDeliveryEntry::to);
        });
    }
    /**
     * 仅在持有当前许可锁且根 CAS 已核验的事务中保存完整许可。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param delivery 非空的当前完整许可，只读
     */
    @Override
    public void saveDelivery(DSLContext dsl, WorkerDelivery delivery) {
        var entry = WorkerDeliveryEntry.from(delivery);
        execute(dsl, context -> {
            context.insertInto(WORKER_DELIVERIES).set(entry.buildInsertMap())
                .onConflict(WORKER_DELIVERIES.COMPANY_ID, WORKER_DELIVERIES.EXECUTION_ID, WORKER_DELIVERIES.TASK_RUN_ID)
                .doUpdate().set(entry.buildUpdateMap()).execute();
            return null;
        });
    }
    /**
     * 使用 Engine 原生创建，重发身份冲突不覆盖正文。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param message 非空的完整持久消息，读取而不修改
     */
    @Override
    public void add(DSLContext dsl, ExecutionMessage message) { createIfAbsent(dsl, ExecutionMessageEntry.from(message).buildInsertMap()); }
    /**
     * 使用原生单行读取恢复完整消息。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param id 非空白的稳定消息身份，重发时沿用
     * @return 完整原消息；消费删除或不存在时为空
     */
    @Override
    public Optional<ExecutionMessage> message(DSLContext dsl, String companyId, String id) {
        return Optional.ofNullable(readOne(query(dsl, messageIdentity(companyId, id)), ExecutionMessageEntry.class)).map(ExecutionMessageEntry::to);
    }
    /**
     * 使用原生 Builder 读取限定批量的未确认发布消息。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param limit 正数批量上限，仅返回最多该数量的未确认消息
     * @return 最多 limit 条尚未确认发布的消息，可能为空
     * @throws IllegalArgumentException limit 小于 1 时抛出
     */
    @Override
    public List<ExecutionMessage> pending(DSLContext dsl, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid outbox limit");
        return query(dsl, EXECUTION_MESSAGES.PUBLISHED_AT.isNull()).asc(EXECUTION_MESSAGES.CREATED_AT, EXECUTION_MESSAGES.ID)
            .pageSize(limit).list(ExecutionMessageEntry.class).items.stream().map(ExecutionMessageEntry::to).toList();
    }
    /**
     * 只更新尚存的原消息，不把消费后的删除重新变成插入。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param id 非空白的稳定消息身份，重发时沿用
     * @param now 当前 UTC Unix 毫秒值，由调用边界提供
     */
    @Override
    public void published(DSLContext dsl, String companyId, String id, long now) {
        execute(dsl, context -> context.update(EXECUTION_MESSAGES).set(EXECUTION_MESSAGES.PUBLISHED_AT, now)
            .where(messageIdentity(companyId, id)).and(EXECUTION_MESSAGES.PUBLISHED_AT.isNull()).execute());
    }
    /**
     * 消费闭合后经原生删除清理记录，允许重复确认。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param id 非空白的稳定消息身份，重发时沿用
     */
    @Override
    public void consumed(DSLContext dsl, String companyId, String id) { deleteRows(dsl, messageIdentity(companyId, id)); }
    /**
     * 限定 owner、代次及未过期条件续租，不修改聚合版本。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文，不关闭其连接
     * @param delivery 非空的当前完整许可，只读
     * @param now 当前 UTC Unix 毫秒值，由调用边界提供
     * @param until 续租目标 UTC Unix 毫秒值，必须晚于当前时间
     * @return true 表示当前未过期许可续租成功，false 表示已失去许可
     */
    @Override
    public boolean renew(DSLContext dsl, WorkerDelivery delivery, long now, long until) {
        return execute(dsl, context -> context.update(WORKER_DELIVERIES).set(WORKER_DELIVERIES.LEASE_UNTIL_AT, until)
            .where(identity(delivery.companyId(), delivery.executionId(), delivery.taskRunId()))
            .and(WORKER_DELIVERIES.ATTEMPT_NUMBER.eq(delivery.attemptNumber()))
            .and(WORKER_DELIVERIES.DELIVERY_GENERATION.eq(delivery.deliveryGeneration()))
            .and(WORKER_DELIVERIES.OWNER_ID.eq(delivery.ownerId()))
            .and(WORKER_DELIVERIES.PHASE.eq(WorkerDelivery.Phase.RUNNING.name()))
            .and(WORKER_DELIVERIES.LEASE_UNTIL_AT.gt(now)).execute() == 1);
    }
    /**
     * 构造当前许可完整租户与节点身份条件。
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param executionId 非空白的原运行身份，不生成或更改
     * @param taskRunId 非空白的原任务运行身份，不生成或更改
     * @return 按输入持久事实创建的独立返回值
     */
    private Condition identity(String companyId, String executionId, String taskRunId) {
        requireText(companyId, Fields.COMPANY_ID);
        requireText(executionId, Fields.EXECUTION_ID);
        requireText(taskRunId, Fields.TASK_RUN_ID);
        return WORKER_DELIVERIES.COMPANY_ID.eq(companyId)
            .and(WORKER_DELIVERIES.EXECUTION_ID.eq(executionId))
            .and(WORKER_DELIVERIES.TASK_RUN_ID.eq(taskRunId));
    }
    /**
     * 构造消息完整租户和稳定身份条件。
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param id 非空白的稳定消息身份，重发时沿用
     * @return 按输入持久事实创建的独立返回值
     */
    private Condition messageIdentity(String companyId, String id) {
        requireText(companyId, Fields.COMPANY_ID);
        requireText(id, Fields.ID);
        return EXECUTION_MESSAGES.COMPANY_ID.eq(companyId).and(EXECUTION_MESSAGES.ID.eq(id));
    }
    /**
     * 拒绝缺失身份，避免宽条件读写。
     * @param value 需要核验的字段值，null 或空白会被拒绝
     * @param name 当前字段名称，作为缺失身份错误信息
     * @throws IllegalArgumentException value 为 null 或空白时抛出
     */
    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
