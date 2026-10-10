package org.cses.flow.core.repositories.triggers;

import org.cses.flow.scheduler.events.SchedulerEvent;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 触发命中与内部事件持久化契约；调用方决定投递与事务边界。 */
public interface TriggerRepository {
    /**
     * 在调用方事务内串行化同一租户、流程、触发器和来源的命中创建。
     * @param context 非空且已开启事务的数据库上下文
     * @param companyId 非空白租户身份
     * @param flowKey 非空流程键
     * @param triggerKey 非空触发器键
     * @param sourceKey 非空、稳定且已校验的来源幂等键
     */
    void lockSource(DSLContext context, String companyId, String flowKey, String triggerKey, String sourceKey);

    /**
     * 读取同一租户与来源已经固定的命中，不重新选择版本或输入。
     * @param context 非空数据库上下文
     * @param companyId 非空白租户身份
     * @param flowKey 非空流程键
     * @param triggerKey 非空触发器键
     * @param sourceKey 非空、稳定且已校验的来源幂等键
     * @return 首次命中快照；不存在时为空
     */
    Optional<SchedulerEvent> findBySource(DSLContext context, String companyId,
            String flowKey, String triggerKey, String sourceKey);

    /**
     * 原子保存命中与 outbox，并发重复命中返回首次持久化快照。
     * @param context 非空调用方数据库上下文
     * @param event 非空且已校验输入的命中事件，不修改输入
     * @param now 当前 UTC 毫秒时间，用于内部事件创建和重试时间
     * @return 原始命中快照，不重新选择版本或输入
     */
    SchedulerEvent stage(DSLContext context, SchedulerEvent event, long now);

    /**
     * 按完整身份读取命中与队列受理时间。
     * @param context 非空数据库上下文
     * @param companyId 非空白租户身份
     * @param id 非空白命中身份
     * @return 持久化命中；不存在时为空
     */
    Optional<Occurrence> find(DSLContext context, String companyId, String id);

    /**
     * 在队列受理后记录同一命中的受理时间。
     * @param context 非空数据库上下文
     * @param event 非空且已核验的持久化命中事件
     * @param now 当前 UTC 毫秒时间
     */
    void accepted(DSLContext context, SchedulerEvent event, long now);

    /**
     * 系统级有限批次读取已到重试时间的未发布事件。
     * @param context 非空数据库上下文
     * @param now 当前 UTC 毫秒时间
     * @param limit 正数批量上限
     * @return 按创建时间排列的持久化事件集合，可能跨租户；没有事件时为空
     */
    List<StoredEvent> pending(DSLContext context, long now, int limit);

    /**
     * Broker 确认后按完整身份记录发布成功并清空错误。
     * @param context 非空数据库上下文
     * @param event 非空已发布事件
     * @param now 当前 UTC 毫秒时间
     */
    void published(DSLContext context, SchedulerEvent event, long now);

    /**
     * 按原事件身份增加失败次数并设置下次可投递时间。
     * @param context 非空数据库上下文
     * @param stored 非空待发布快照，携带读取时失败次数
     * @param retryAt 下次可投递的 UTC 毫秒时间
     * @param error 非空失败描述
     */
    void failed(DSLContext context, StoredEvent stored, long retryAt, String error);

    /**
     * 在调用方短事务中锁住指定内部事件。
     * @param context 非空事务上下文
     * @param companyId 非空白租户身份
     * @param id 非空白事件身份
     * @return 已锁定事件与处理标记；不存在时为空
     */
    Optional<StoredEvent> lock(DSLContext context, String companyId, String id);

    /**
     * 在匹配集合持久化后标记原事件已处理。
     * @param context 非空且持有原事件行锁的事务上下文
     * @param event 非空原事件
     * @param now 当前 UTC 毫秒时间
     */
    void processed(DSLContext context, SchedulerEvent event, long now);

    /**
     * 读取同租户触发链已经经过的流程键，用于阻止重新进入祖先。
     * @param context 非空数据库快照上下文
     * @param event 非空上游终态事件
     * @return 独立的流程键集合，包含当前上游流程
     */
    Set<String> ancestors(DSLContext context, SchedulerEvent event);

    /**
     * 读取指定租户中具有正式版本的候选流程键。
     * @param context 非空数据库上下文
     * @param companyId 非空白租户身份
     * @return 去重的流程键集合，没有候选时为空
     */
    List<String> flowKeys(DSLContext context, String companyId);

    /** 命中的不可变持久化回执，不暴露数据库 Entry。 */
    record Occurrence(SchedulerEvent event, Long acceptedAt) {
        /**
         * 从持久化事实形成命中回执。
         * @param event 非空且不可变的命中事件
         * @param acceptedAt UTC 毫秒受理时间；null 表示未受理
         * @return 新回执，共享不可变事件
         */
        public static Occurrence from(SchedulerEvent event, Long acceptedAt) {
            return new Occurrence(event, acceptedAt);
        }
    }

    /** 内部事件的不可变投递与处理元数据，不暴露数据库 Entry。 */
    record StoredEvent(SchedulerEvent event, Long processedAt, int attempts) {
        /**
         * 从持久化事实形成事件快照。
         * @param event 非空且不可变的事件
         * @param processedAt UTC 毫秒处理时间；null 表示未处理
         * @param attempts 非负失败次数
         * @return 新快照，共享不可变事件
         */
        public static StoredEvent from(SchedulerEvent event, Long processedAt, int attempts) {
            return new StoredEvent(event, processedAt, attempts);
        }
    }
}
