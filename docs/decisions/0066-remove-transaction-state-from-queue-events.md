# ADR 0066：从 Queue Event 移除事务状态

## 状态

Accepted（2026-08-20）

## 背景

ADR 0060 已确认事务是一次 Queue 发布的基础设施元数据，不是 Event 业务事实，并为
`DispatchQueue` 增加了 `emitInTransaction(event, dsl)` 单条与批量发布 Interface。
当时为了兼容内部 `ExecutorEvent`，仍在 `Event` Interface 保留可空的
`DSLContext dsl()`，普通 `emit(...)` 继续从 Event 读取事务。

该兼容入口形成了两套事务来源：调用方既可以把 `DSLContext` 放入 Event，也可以在发布
调用中显式传入。结果是所有 Event 都被迫依赖 JOOQ；纯 payload 的
`ExecutionCommand` 需要提供固定返回 `null` 的空实现；`ExecutorEvent` 还要维护不会
持久化的瞬时事务字段。事务选择也分散在 Event 构造和 Queue 调用两个位置。

## 备选方案

### 方案一：继续保留两套事务来源

不需要修改现有调用方，但 Event Interface 继续泄漏 JOOQ，调用者仍需理解事务字段与
显式发布方法的优先关系。

### 方案二：增加 TransactionalEvent 子 Interface

可以让部分 Event 携带事务，但事务仍然进入消息类型层次；同一个业务 Event 是否加入
调用方事务本质上取决于本次发布操作，而不是 Event 类型。

### 方案三：事务只通过 Queue 发布 Interface 显式传入

从 Event 删除 `dsl()`，普通发布固定使用 Queue 自有事务；只有调用方已经持有事务且
需要原子提交时，才使用 `emitInTransaction(...)`。采用此方案。

## 决策

- `Event` 只要求业务提供 `String key()`，不依赖 JOOQ，也不保存或返回事务对象。
- `DispatchQueue.emit(...)` 的单条与批量同步发布始终使用 Queue 自有事务；
  `emitAsync(...)` 继续使用 Queue 自有事务。
- `DispatchQueue.emitInTransaction(...)` 是加入调用方事务的唯一入口。调用方必须显式
  传入非空 `DSLContext`；Queue 只在该事务中暂存消息，不负责提交或回滚。
- `ExecutionCommand` 删除空的 `dsl()` 默认实现；`Create`、`Resume` 和 `Cancel` 继续
  作为可持久化纯 payload。
- `ExecutorEvent` 删除瞬时 `DSLContext` 字段、`dsl()` 和 `inTransaction(...)`；
  `ExecutionCommandEventHandler`、`ExecutorEventHandler` 通过
  `emitInTransaction(event, dsl)` 保持聚合写入与下一条 Event 原子提交。
- `DefaultDispatchQueue` 不再检查 Event 事务或比较批量 Event 的事务实例。普通发布
  直接进入 Queue 自有事务，显式事务发布直接使用调用参数。
- Queue Entry 只序列化 Event 业务数据，不再需要识别或排除 Event 事务字段。

## 理由

- Event Interface 只保留消息业务事实，调用方不再为未使用的事务能力提供空实现。
- 事务选择集中在 Queue 发布调用点，接口更小，事务语义更容易审查和测试。
- 同一个 Event 可以根据调用场景选择普通发布或显式事务发布，无需改变其类型或内容。
- `queues/event` 不再依赖 JOOQ；只有确实表达事务发布能力的 `DispatchQueue` Interface
  暴露 `DSLContext`。

## 后果

- 依赖 `Event.dsl()` 的调用方必须改为 `emitInTransaction(event, dsl)`；普通
  `emit(event)` 不再隐式加入调用方事务。
- Event 实现应删除 `dsl()` 和瞬时事务字段。保留同名业务外方法不会被 Queue 使用。
- 原有“同一批 Event 必须携带同一个 DSL”及“混合 DSL 批次拒绝”规则删除；批量事务
  由一次 `emitInTransaction(events, dsl)` 调用天然确定。
- 普通发布、异步发布、显式事务发布、回滚和 JSONB 往返继续由 Queue Interface 层测试；
  Execution 内部状态交接的原子性保持不变。

本决策修订 ADR 0046、0047 和 0060 中 Event 直接提供事务、Default Queue 从 Event
解析事务及 JSONB 特殊排除 DSL 的条款，也修订 ADR 0051 中 `Create.dsl()` 的描述。
