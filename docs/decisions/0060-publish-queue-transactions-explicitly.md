# ADR 0060：通过 Queue 发布 API 显式传递调用方事务

## 状态

Accepted（保留 Event.dsl 兼容入口的条款由 ADR 0066 修订；显式事务发布 Interface
继续有效）

## 背景

原 Queue 契约要求每个 `Event` 暴露可空的 `DSLContext dsl()`，由 Queue 从 Event
对象中读取调用方事务。这个设计仍然适合已有的内部 `ExecutorEvent`，因为它需要在
消费事务内把状态写入和下一条 Event 一起提交；但 Executor 的 `Create`、`Resume`、
`Cancel` Command 是需要持久化和恢复的数据，不应携带生产端事务对象。

普通 Executor Command 在消费端才开始事务：`ExecutionCommandEventHandler` 收到
Command 后开启 JOOQ 事务，物化 Execution 并投递内部 `ExecutorEvent`。只有可信的
pending continuation 需要在生产端把 Execution 行和 Queue 行原子提交。

## 备选方案

### 方案一：所有 Event 继续由 Event 提供 `dsl()`

改动最小，能够保留已有 Event 的事务语义，但 record Command 需要额外的隐藏状态，
无法保持纯数据形态。

### 方案二：把 `DSLContext` 作为 record 组件

可以保留现有 Queue API，但会让事务对象进入 record 值语义，并增加序列化、相等性和
生命周期风险。

### 方案三：对纯 payload 增加由 Queue 发布 API 显式接收事务

保留 `Event.dsl()` 作为兼容的同步发布入口，同时增加
`emitInTransaction(event, dsl)` 或对应的批量方法。纯 payload（例如
`ExecutionCommand`）使用显式入口；采用此方案。

## 决策

- `Event` 保留可空的 `dsl()`；普通同步 `emit` 继续读取它，异步 `emitAsync` 忽略它。
- `DispatchQueue` 在现有普通单条/批量发布之外，增加显式接收 `DSLContext` 的
  `emitInTransaction` 单条/批量发布。
- `ExecutionCommand` 提供固定返回 `null` 的默认 `dsl()`，因此 `Create`、`Resume`、
  `Cancel` 实现不再持有或设置事务；普通命令发布使用 Queue 自有事务。
- 显式事务发布只在调用方已经持有事务时使用，Queue 不负责提交或回滚该事务。
- Queue Entry 只序列化 Event 业务数据，不序列化调用方事务。
- `ExecutionService.continueExecution` 使用显式事务发布，保留 pending Execution 与
  Create Queue 消息的原子提交语义。
- `ExecutionCommandEventHandler` 和 `ExecutorEventHandler` 在已有处理事务内通过
  `ExecutorEvent.inTransaction(dsl)` 发布内部 Event，保持聚合写入和下一周期消息的
  原子性。
- `Create` 不再继承 `SerializableObject`，也不携带 DSL，使用 Java `record` 表达不可变
  Command payload；其 JSON 往返必须通过 Queue Command 测试验证。

## 理由

事务属于一次发布操作的基础设施元数据，不属于 Command 的业务事实。对 Command 使用
`ExecutionCommand.dsl()` 的空默认值，可以保持命令对象纯粹、支持 record；同时保留
Event 现有的同步事务语义，并通过 Queue 显式入口覆盖纯 payload 与 pending continuation
的原子提交需求。

## 后果

- Queue 契约继续依赖 JOOQ `DSLContext`；Event 的同步事务语义保持不变，纯 payload
  的调用方需要选择 `emitInTransaction`。
- ADR 0046、0047 中“由 Event.dsl 提供同步事务来源”的条款继续适用于实现了该能力的
  Event；本 ADR 补充纯 payload 的显式发布路径。Queue 的竞争消费、JSONB 统一消息表
  和失败重试语义保持不变。
