package org.cses.flow.scheduler.repositories;

import org.cses.flow.scheduler.ScheduleData;
import org.jooq.DSLContext;

import java.util.Optional;
import java.util.Set;

/** 调度计划持久化契约；调用方拥有领取与推进的短事务。 */
public interface ScheduleRepository {
    /**
     * 在调用方短事务内串行化同租户、同流程的计划配置同步。
     * @param context 非空且已开启事务的数据库上下文
     * @param companyId 非空白租户身份
     * @param flowKey 非空流程业务键
     */
    void lockFlow(DSLContext context, String companyId, String flowKey);

    /**
     * 排他领取一条到期计划并跳过其他节点持锁的行。
     * @param context 非空且处于 Flow 短事务的上下文
     * @param now 当前 UTC 毫秒时间
     * @return 持锁计划；没有到期计划时为空
     */
    Optional<ScheduleData> lockNext(DSLContext context, long now);

    /**
     * 在当前短事务内按完整身份读取并锁住计划。
     * @param context 非空事务上下文
     * @param companyId 非空白租户身份
     * @param triggerId 非空白触发器身份
     * @return 持锁计划；不存在时为空
     */
    Optional<ScheduleData> find(DSLContext context, String companyId, String triggerId);

    /**
     * 保存新计划，重复同步不覆盖已有命中身份。
     * @param context 非空调用方数据库上下文
     * @param data 非空新计划，不修改输入
     */
    void insert(DSLContext context, ScheduleData data);

    /**
     * 按当前命中身份推进或移除已持锁计划。
     * @param context 非空且持有计划行锁的上下文
     * @param current 非空当前计划快照
     * @param next 下一计划；null 表示耗尽或取消
     * @throws IllegalStateException 当前命中身份已经失效时抛出
     */
    void advance(DSLContext context, ScheduleData current, ScheduleData next);

    /**
     * 原事务回滚后延后同一命中，已推进或取消的计划不回写。
     * @param context 回滚后可用的非空数据库上下文
     * @param data 非空失败命中快照
     * @param now 当前 UTC 毫秒时间
     * @param retryAt 下次可领取的 UTC 毫秒时间
     * @param error 非空失败描述
     */
    void failed(DSLContext context, ScheduleData data, long now, long retryAt, String error);

    /**
     * 删除该租户和流程中已不再启用的计划。
     * @param context 非空配置同步事务上下文
     * @param companyId 非空白租户身份
     * @param flowKey 非空流程业务键
     * @param active 非空当前启用身份集合，可为空集合，不修改输入
     */
    void removeObsolete(DSLContext context, String companyId, String flowKey, Set<String> active);
}
