# Task 重试

## 配置
仅在 RunnableTask 上配置，固定间隔示例：
```yaml
retry:
  type: constant
  maxAttempts: 3
  intervalMillis: 1000
  maxDurationMillis: 60000
  warningOnRetry: false
```
指数：`type: exponential`，配置 `intervalMillis`、`maxIntervalMillis`、`delayFactor`（默认 2）。
随机：`type: random`，配置 `minIntervalMillis`、`maxIntervalMillis`（左闭右开）。
次数包含首次调用；未配置只调用一次。次数必填；预算是从首次调用开始的正毫秒时间，限制后续调度，不中断正在执行的任务。
成功后的 warningOnRetry 与失败耗尽不同：耗尽保留失败原因、结束实例，不自动执行补偿或允许下游继续。

## 扩展
新增 AbstractRetry 子类，实现 `delayMillis(int attempts)`，返回非负毫秒；使用受控构造校验全部配置。
通过 AbstractRetry 的 Jackson 具名子类型注册（与内置三类相同）接入 YAML/持久化和 Schema，Executor 无类型分支。
同一任务不能修改实例字段记录运行状态；通过 RunContext 读取 `taskRun.attemptNumber`，从 1 开始。
外部业务可以用稳定 `taskRun.id` 作为幂等键；编号用于诊断，不能换掉业务幂等键。

## 运行与恢复
专用持久化 Topic 为 `flow-delay`，复用 PAAS Pulsar 的 `sendAtTime`；Shared 订阅接收延迟消息。
Broker 需开启 `delayedDeliveryEnabled`，建议设置 `isDelayedDeliveryDeliverAtTimeStrict=true`；默认非严格模式可能提前一个 tick 投递，消费者会拒绝提前消息并通过 NACK 重新投递，不会提前执行任务。
`flow.executor.retry.outbox.poll-interval` 默认 1s，只负责补发未确认的消息，未来计划也立即发布。实际到期等待完全由 Pulsar 承担。
`flow.executor.retry.outbox.enabled=false` 仅停用 Outbox 发布；已经进入 Broker 的消息仍会执行，不能用它取消任务。
TaskRun 的 `attempts`、`retry_variables`、`next_retry_at` 与 `task_retry_messages` 使用同一根 CAS/SQL 提交。
Outbox 发送失败保留未确认行；Broker 接受后只标记 `published_at`，消费计划或取消失效后物理删除。已接纳尝试与聚合状态继续拒绝重复消息，见 [ADR 0113](../decisions/0113-submit-worker-tasks-and-clean-consumed-outbox.md)。
消费者核对租户、运行、TaskRun、下一次 Attempt 编号和计划时间，取消、结束或旧消息直接忽略。普通 UPDATED 不触发等待中的重试。
重启可恢复未发送计划与 Broker 中的等待消息；已领取 RUNNING 后失联属于 Worker 恢复，不在本能力内。
查询返回 attemptNumber、attempts、nextRetryAt；业务变量快照不进入 HTTP 响应。

## 数据库
修改基线需在空库执行 `gen/sql/flow/001_create_flow_tables.sql`，重新生成 JOOQ；不对已有数据库自动升级或清理。
验证使用独立临时 PostgreSQL 和 Pulsar，命令见 postgresql-repositories.md 与 pulsar-queues.md；Pulsar 延迟队列验证见 [重试验证报告索引](../test-reports/README.md)。
