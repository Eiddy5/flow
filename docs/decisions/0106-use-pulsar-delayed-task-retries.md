# ADR 0106：Pulsar 专用延迟队列驱动任务重试

## 状态
Accepted（2026-09-20，用户明确要求使用 Pulsar 延迟队列）。替代 ADR0105 的到期扫描机制。

## 背景
到期扫描 TaskRun 再发送普通 UPDATED 把计时责任放在应用侧。用户要求专用重试延迟队列，现有 PulsarQueue.emitAt 与 Shared 消费者已具备所需能力。

## 备选
- 扫描到期任务：不符合本次要求，移除。
- 保存失败事实后直接发布延迟消息：数据库提交到发送之间宕机会丢失重试，拒绝。
- 与聚合原子写入 Outbox，再把未来消息提前交给 Pulsar：采用，不增加消息依赖或独立调度引擎。

## 决策
失败策略计算时间后，ExecutionRepository 的根 CAS、TaskRun 和 task_retry_messages 在同 SQL 提交。
Outbox 使用 company_id、execution_id、task_run_id、attempt_number 组成唯一键，保存 deliver_at 和可空 published_at。
只登记 RETRY_WAIT，重复保存同一计划不重置已发送状态。表不建立外键；运行事实仍由 Execution 拥有，消息表仅记录发布进度。

TaskRetryOutboxPublisher 按批查询 published_at 为空的记录，不按 deliver_at 是否到期筛选。通过 emitAt 发布到 flow-delay，并在 Broker 确认后标记已发送。
首次发布等待本机重试订阅完成注册，避免 PAAS 默认 Latest 初始游标跳过订阅建立前的消息。
该轮询只修复数据库到 Broker 的可靠发布，不承担重试计时。两个发布器或发送后标记前宕机可能产生重复消息，消费者必须幂等。

DefaultExecutor 为 TaskRetryEvent 注册独立 Shared 订阅。接收后重新读库，核对 companyId、executionId、taskRunId、下一次尝试编号、deliverAt 与等待或已接纳 CREATED 状态。
提前消息抛异常交给 PAAS NACK；已取消、结束、不匹配或不存在的运行直接忽略。只有匹配消息才能将 RETRY_WAIT 改为 CREATED，普通 UPDATED 不检查时钟恢复重试。
后续复用现有 Executor：实际领取时检查预算，保存 RUNNING 和新 Attempt，CAS 成功后执行 Worker。重复消息无法再次领取同一个 Attempt。

## 理由
重试策略仍决定间隔，Pulsar 负责等待，Executor 决定是否允许执行，Worker 只执行一次。消息持久化与领域存储各自承担已有职责。

## 后果
- 使用持久化 Topic 和 Shared 订阅，复用 PAAS ACK/NACK、连接及生命周期。
- 已发送 Outbox 行保留到所属 Execution 的显式清理，避免后续聚合保存重新登记同一次消息。
- Broker 默认非严格延迟可能提前一个 tick；推荐开启 isDelayedDeliveryDeliverAtTimeStrict，消费者仍保留时间检查。
- 等待消息在取消后不做 Broker 精确删除，消费者读库后忽略。
- 未承诺业务副作用 exactly-once，也未扩展 RUNNING Worker 失联恢复。
- 基线新增消息表并重新生成 JOOQ；已有开发库按既有重建流程处理。

参考：[Apache Pulsar delayed message delivery](https://pulsar.apache.org/docs/next/concepts-messaging/#delayed-message-delivery)。
