# ADR 0096：删除旧 PostgreSQL 队列

## 状态

Accepted（2026-09-10）。用户在完成 Pulsar 默认链路替换后要求清理旧实现，修订
ADR 0094 暂时保留独立 PostgreSQL Adapter 的阶段性决定。

## 背景与选项

默认两条 Executor 队列已由注解驱动 PAAS Pulsar，旧轮询器、SQL 消息存储及订阅
生命周期没有生产调用方。继续保留会留下另一套不可用的运行入口和无效验证任务。
不保留兼容包装或回退模式，直接删除已退出链路的实现。

## 决策

- 删除 DefaultDispatchQueue、PostgresQueueStore、PollingQueueSubscription、QueueMessageEntry。
- 删除 DispatchQueue、QueueSubscription、DispatchEvent；两种生产消息直接使用仍有效的
  Event.key 契约，公共 Queue 和 Pulsar 声明、发送、消费行为不变。
- 删除旧适配器/事务/Entry/压测专属测试和 Gradle 压测任务；命令与内部事件的
  序列化测试迁移到 PAAS Schema，保留身份、具体类型和载荷断言。
- 从开发期建表入口删除 queues.sql，在隔离空库执行新基线并重新生成 JOOQ，移除
  Queues 的表、Record、Pojo 及生成入口引用。测试清理不再访问旧表。
- 当前关系图仍是 PostgreSQL harness 中的 flows、flow_tasks、executions、task_runs
  四表，领域关系和约束不变。Pulsar 消息不另建数据库表。
- 删除旧压测手册；历史 ADR 和测试报告保留为历史证据，当前目录图和接入文档同步更新。

## 理由与后果

运行传输只保留 PAAS Pulsar 一条实现路径，不继续维护已退出运行的事务与轮询 API。
旧代码调用方需要改用 Queue 发布和注解监听。本次只清理仓库源码与开发期基线，
不连接现有业务数据库执行 DROP 或清空消息。旧部署切换前仍需按 Pulsar harness
排空已接受消息；新基线不是旧库自动升级脚本。

## 验证

验证无旧类型引用、新四表基线可重复执行、JOOQ 来自真实数据库重新生成；运行
消息序列化、Pulsar 回调/ACK 和相关运行回归。实际结果以本次测试报告为准。
