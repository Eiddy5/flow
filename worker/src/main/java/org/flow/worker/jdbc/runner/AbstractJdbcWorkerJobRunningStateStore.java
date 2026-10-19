package org.flow.worker.jdbc.runner;

import org.flow.core.jdbc.runner.JdbcTransactionContext;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import com.google.common.annotations.VisibleForTesting;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.WorkerJobRunningTable;
import org.flow.core.executor.WorkerJobRunningStateStore;
import org.flow.core.runners.TransactionContext;
import org.flow.core.runners.WorkerJobRunning;
import org.flow.core.runners.WorkerTaskRunning;
import org.flow.core.runners.WorkerTriggerRunning;
import org.flow.core.jdbc.repository.AbstractJdbcRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractJdbcWorkerJobRunningStateStore extends AbstractJdbcRepository implements WorkerJobRunningStateStore {
    protected org.flow.core.jdbc.AbstractJdbcRepository<WorkerJobRunning> jdbcRepository;

    /**
     * 返回当前存储使用的生成表，保留配置的物理名称和字段类型。
     *
     * @return 与当前 JDBC Repository 绑定的生成表
     */
    @Override
    protected WorkerJobRunningTable table() {
        return jdbcRepository.getTable(Tables.WORKER_JOB_RUNNING);
    }

    public AbstractJdbcWorkerJobRunningStateStore(org.flow.core.jdbc.AbstractJdbcRepository<WorkerJobRunning> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    /**
     * 在调用方事务中登记工作任务归属；重复投递返回 false，较新的代次仍被旧归属占用时抛出异常。
     *
     * @param txContext 调用方传入的事务上下文；不能为 null
     * @param job 需要领取的工作任务及投递代次；不能为 null
     * @return 成功登记返回 true；已存在相同或较新代次时返回 false
     * @throws IllegalStateException 当旧归属尚未释放而收到较新代次时抛出
     */
    @Override
    public boolean claim(TransactionContext txContext, WorkerJobRunning job) {
        var context = txContext.unwrap(JdbcTransactionContext.class).getDslContext();
        boolean acquired = context.insertInto(table())
            .set(table().KEY, job.uid())
            .set(jdbcRepository.persistFields(job))
            .onDuplicateKeyIgnore().execute() == 1;
        if (acquired) {
            return true;
        }
        var record = context.select(table().VALUE).from(table())
            .where(table().KEY.eq(job.uid())).forUpdate().fetchOne();
        if (record == null || generation(job) > generation(jdbcRepository.deserialize(record.get(table().VALUE).data()))) {
            // A later evaluation/retry is not a duplicate. Keep its queue message until
            // the previous holder has released the lease instead of acknowledging it.
            throw new IllegalStateException("Previous worker job attempt still owns " + job.uid());
        }
        return false;
    }

    private static long generation(WorkerJobRunning job) {
        if (job instanceof WorkerTriggerRunning trigger) {
            return trigger.getDispatchEpoch();
        }
        var task = (WorkerTaskRunning) job;
        return task.getTaskRun().getAttempts() == null ? 0 : task.getTaskRun().getAttempts().size();
    }

    /**
     * 锁定并校验工作任务归属后发布结果；终态发布后释放触发器或标记任务结果待确认。
     *
     * @param key 需要定位的记录或消息键；不能为 null
     * @param workerUid 需要匹配的 Worker 标识；不能为 null
     * @param deliveryId 本次投递标识；用于拒绝已被替换的投递
     * @param dispatchEpoch 触发器投递代次；null 时不额外校验代次
     * @param terminal true 表示终态结果，发布后释放触发器或标记任务待确认；false 保留当前归属
     * @param resultId 终态结果标识；供后续确认时匹配
     * @param publication 在锁定记录的事务中发布结果的回调；不能为 null
     * @return 归属和投递身份匹配且已发布时为 true，否则为 false
     */
    @Override
    public boolean publishResult(String key, String workerUid, String deliveryId, Long dispatchEpoch, boolean terminal, String resultId,
        Runnable publication) {
        return jdbcRepository.getDslContextWrapper().transactionResult(configuration -> {
            var context = DSL.using(configuration);
            var record = context.select(table().VALUE).from(table())
                .where(table().KEY.eq(key)).and(table().WORKER_UID.eq(workerUid))
                .forUpdate().fetchOne();
            if (record == null) {
                return false;
            }
            var job = jdbcRepository.deserialize(record.get(table().VALUE).data());
            if (job.isResultPending() || !Objects.equals(job.getDeliveryId(), deliveryId) || (dispatchEpoch != null &&
                (!(job instanceof WorkerTriggerRunning trigger) || trigger.getDispatchEpoch() != dispatchEpoch))) {
                return false;
            }
            publication.run();
            if (terminal) {
                if (job instanceof WorkerTriggerRunning) {
                    deleteByKey(context, key);
                } else {
                    job.setResultPending(true);
                    job.setResultId(resultId);
                    jdbcRepository.persist(job, context, jdbcRepository.persistFields(job));
                }
            }
            return true;
        });
    }

    /**
     * 锁定工作任务记录，只删除结果标识匹配且处于待确认状态的记录。
     *
     * @param key 需要定位的记录或消息键；不能为 null
     * @param resultId 终态结果标识；供后续确认时匹配
     */
    @Override
    public void acknowledgeResult(String key, String resultId) {
        jdbcRepository.getDslContextWrapper().transaction(configuration -> {
            var context = DSL.using(configuration);
            var record = context.select(table().VALUE).from(table())
                .where(table().KEY.eq(key)).forUpdate().fetchOne();
            if (record != null) {
                var job = jdbcRepository.deserialize(record.get(table().VALUE).data());
                if (job.isResultPending() && Objects.equals(job.getResultId(), resultId)) {
                    deleteByKey(context, key);
                }
            }
        });
    }

    @Override
    public WorkerJobRunning save(TransactionContext txContext, WorkerJobRunning workerJobRunning) {
        // if both queue and repository support the same transaction type, we participate in the transaction, otherwise, not
        if (txContext.supports(JdbcTransactionContext.class)) {
            var dslContext = txContext.unwrap(JdbcTransactionContext.class).getDslContext();
            this.jdbcRepository.persist(workerJobRunning, dslContext, this.jdbcRepository.persistFields(workerJobRunning));
        } else {
            // Commit on a dedicated connection: the entry must be visible to concurrent result
            // processing as soon as the job is sent to the worker, even when the caller holds an
            // open thread-bound transaction (the dispatch-queue poll transaction) — otherwise the
            // terminal result of a fast task deletes nothing and the entry leaks forever.
            this.jdbcRepository.getDslContextWrapper().requireNewTransaction(
                configuration -> this.jdbcRepository.persist(workerJobRunning, DSL.using(configuration), this.jdbcRepository.persistFields(workerJobRunning))
            );
        }
        return workerJobRunning;
    }

    /**
     * 按键删除工作任务归属；传入 JDBC 事务时在该事务中执行。
     *
     * @param txContext 调用方传入的事务上下文；不能为 null
     * @param key 需要定位的记录或消息键；不能为 null
     */
    @Override
    public void deleteByKey(TransactionContext txContext, String key) {
        // if both queue and repository support the same transaction type, we participate in the transaction, otherwise, not
        if (txContext.supports(JdbcTransactionContext.class)) {
            var dslContext = txContext.unwrap(JdbcTransactionContext.class).getDslContext();
            deleteByKey(dslContext, key);
        } else {
            this.jdbcRepository.getDslContextWrapper().transaction(configuration ->
            {
                var dslContext = DSL.using(configuration);
                deleteByKey(dslContext, key);
            });
        }
    }

    /**
     * 按键删除工作任务归属；传入 JDBC 事务时在该事务中执行。
     *
     * @param dslContext 当前事务使用的 DSLContext；不能为 null
     * @param key 需要定位的记录或消息键；不能为 null
     */
    private void deleteByKey(DSLContext dslContext, String key) {
        dslContext
            .transaction(
                configuration -> DSL
                    .using(configuration)
                    .deleteFrom(table())
                    .where(table().KEY.eq(key))
                    .execute()
            );
    }

    /**
     * 读取全部工作任务归属状态，供验证存储内容使用。
     *
     * @return 当前全部归属状态；无记录时为空
     */
    @VisibleForTesting
    public List<WorkerJobRunning> findAll() {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table());

                return this.jdbcRepository.fetch(select);
            });
    }

    /**
     * 按键删除工作任务归属；传入 JDBC 事务时在该事务中执行。
     *
     * @param key 需要定位的记录或消息键；不能为 null
     */
    @Override
    public void deleteByKey(String key) {
        this.jdbcRepository
            .getDslContextWrapper()
            .transaction(
                configuration -> DSL
                    .using(configuration)
                    .deleteFrom(table())
                    .where(table().KEY.eq(key))
                    .execute()
            );
    }

    /**
     * 同时匹配键和 Worker 标识后删除归属，防止移除其他 Worker 的记录。
     *
     * @param txContext 调用方传入的事务上下文；不能为 null
     * @param key 需要定位的记录或消息键；不能为 null
     * @param workerUid 需要匹配的 Worker 标识；不能为 null
     */
    @Override
    public void deleteByKeyAndWorker(TransactionContext txContext, String key, String workerUid) {
        // if both queue and repository support the same transaction type, we participate in the transaction, otherwise, not
        if (txContext.supports(JdbcTransactionContext.class)) {
            deleteByKeyAndWorker(txContext.unwrap(JdbcTransactionContext.class).getDslContext(), key, workerUid);
        } else {
            this.jdbcRepository
                .getDslContextWrapper()
                .transaction(configuration -> deleteByKeyAndWorker(DSL.using(configuration), key, workerUid));
        }
    }

    /**
     * 同时匹配键和 Worker 标识后删除归属，防止移除其他 Worker 的记录。
     *
     * @param dslContext 当前事务使用的 DSLContext；不能为 null
     * @param key 需要定位的记录或消息键；不能为 null
     * @param workerUid 需要匹配的 Worker 标识；不能为 null
     */
    private void deleteByKeyAndWorker(DSLContext dslContext, String key, String workerUid) {
        dslContext
            .transaction(
                configuration -> DSL
                    .using(configuration)
                    .deleteFrom(table())
                    .where(table().KEY.eq(key))
                    .and(table().WORKER_UID.eq(workerUid))
                    .execute()
            );
    }

    /**
     * 读取仍持有工作任务的不同 Worker 标识。
     *
     * @return Worker 标识集合；无记录时为空
     */
    @Override
    public Set<String> findWorkerUidsWithRunningJobs() {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> DSL
                    .using(configuration)
                    .selectDistinct(table().WORKER_UID)
                    .from(table())
                    .fetchSet(table().WORKER_UID)
            );
    }

    /**
     * 处理失活 Worker 集合持有的任务；空集合不读取数据库。
     *
     * @param txContext 调用方传入的事务上下文；不能为 null
     * @param inactiveWorkerUids 需要恢复的失活 Worker 标识集合；不能为 null，空集合不处理
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     */
    @Override
    public void processOrphanWorkerJobs(TransactionContext txContext, Set<String> inactiveWorkerUids, BiConsumer<TransactionContext, WorkerJobRunning> consumer) {
        if (inactiveWorkerUids.isEmpty()) {
            return;
        }

        process(txContext, table().WORKER_UID.in(inactiveWorkerUids), consumer);
    }

    /**
     * 处理给定失活 Worker 仍持有的工作任务。
     *
     * @param txContext 调用方传入的事务上下文；不能为 null
     * @param workerUid 需要匹配的 Worker 标识；不能为 null
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     */
    @Override
    public void processWorkerJobsForDeadWorker(TransactionContext txContext, String workerUid, BiConsumer<TransactionContext, WorkerJobRunning> consumer) {
        process(txContext, table().WORKER_UID.eq(workerUid), consumer);
    }

    /**
     * 锁定符合条件的工作任务，在事务中清理旧版记录或调用消费者。
     *
     * @param txContext 调用方传入的事务上下文；不能为 null
     * @param condition 限制待处理记录的 SQL 条件；不能为 null
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     */
    private void process(TransactionContext txContext, Condition condition, BiConsumer<TransactionContext, WorkerJobRunning> consumer) {
        // if both queue and repository support the same transaction type, we participate in the transaction, otherwise, not
        if (txContext.supports(JdbcTransactionContext.class)) {
            var dslContext = txContext.unwrap(JdbcTransactionContext.class).getDslContext();
            process(dslContext, txContext, condition, consumer);
        } else {
            this.jdbcRepository
                .getDslContextWrapper()
                .transaction(configuration -> process(DSL.using(configuration), new JdbcTransactionContext(DSL.using(configuration)), condition, consumer));
        }
    }

    /**
     * 锁定符合条件的工作任务，在事务中清理旧版记录或调用消费者。
     *
     * @param dslContext 当前事务使用的 DSLContext；不能为 null
     * @param txContext 调用方传入的事务上下文；不能为 null
     * @param condition 限制待处理记录的 SQL 条件；不能为 null
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     */
    private void process(DSLContext dslContext,
        TransactionContext txContext,
        Condition condition,
        BiConsumer<TransactionContext, WorkerJobRunning> consumer) {
        dslContext
            .select(table().KEY, table().VALUE)
            .from(table())
            .where(condition)
            .forUpdate()
            .fetch()
            .forEach(record ->
            {
                String key = record.get(table().KEY);
                WorkerJobRunning workerJobRunning = this.jdbcRepository.deserialize(record.get(table().VALUE).data());

                if (workerJobRunning.isLegacy()) {
                    // Written by a worker of a previous major version: every consumer reads fields this
                    // entry does not carry, and a failure here aborts the whole liveness sweep — including
                    // the vNode rebalance that runs after it, which silently stops all trigger scheduling.
                    // The worker holding this lease is gone, so drop it on the same connection that holds
                    // the row lock rather than hand it to a consumer that cannot use it.
                    log.warn(
                        "Discarding running entry '{}' of type '{}': it was written by a previous version and cannot be processed by this one.",
                        key,
                        workerJobRunning.getType()
                    );
                    deleteByKey(dslContext, key);
                    return;
                }

                consumer.accept(txContext, workerJobRunning);
            });
    }
}
