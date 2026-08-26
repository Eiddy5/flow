# ADR 0068：移除两阶段 Execution 启动入口

## 状态

Accepted（2026-08-24）

本决策取代 ADR 0050 的 pending Execution 物化方案，并修订 ADR 0002、0020、0051、
0052、0060 和 0061 中关于 `createPending`、`continueExecution`、
`CreateExecutionCommand` 与 `ContinueExecutionCommand` 的条款。

## 背景

`ExecutionService` 同时提供两条启动路径：普通 `create` 通过 Executor Command
Queue 异步创建并推进 Execution；可信宿主先调用 `createPending` 持久化一个
`CREATED` Execution，再调用 `continueExecution` 绑定 inputs 并发布首个
`ExecutorEvent`。

两阶段路径把一次“启动 Flow”拆成两个需要调用方排序的公开操作。调用方必须理解
`CREATED` 状态、输入绑定时机和 Queue 发布顺序；第二步未发生时会留下没有推进保证
的 Execution。该路径还复制了普通 `Create` Consumer 已经拥有的 Flow 选择、
Execution 创建、输入绑定和事件发布职责。当前已确认 UC 只包含一次完整启动操作，
生产调用方也未使用两阶段入口。

## 备选方案

### 方案一：保留两阶段公开入口

可以继续让宿主在两个调用之间绑定外部业务，但 Flow 无法保证第二个调用发生，公开
Interface 继续泄漏内部生命周期与事务顺序。

### 方案二：把两阶段入口改为包内实现

能够缩小公开 Interface，但仍保留两套 Execution 创建和首个 Event 发布链路，规则、
幂等和测试会继续分叉。

### 方案三：只保留一次完整的 `create`

Service 在 Queue 受理前完成 Flow 选择、输入校验和稳定身份生成；Consumer 使用
Command 中的精确事实原子创建 Execution 并发布首个内部 Event。采用此方案。

## 决策

- `ExecutionService` 只通过 `create(session, flowKey)` 和
  `create(session, flowKey, inputs)` 启动新 Execution；调用方不能单独物化待启动
  Execution，也不能再次调用“继续启动”。
- 普通启动只选择当前最新、未删除的 Flow Reversion。公开的指定历史版本启动重载
  删除，调用方不能把内部精确版本恢复能力当作产品级版本选择器。
- Service 在发布 Queue Command 前规范化完整 Flow inputs，生成稳定 Execution id，
  并把租户、发起人、精确 Flow 引用和不可变 inputs 放入 `Create`。
- `ExecutionCommandEventHandler` 使用 `Create.executionId` 物化 Execution，并在同一
  事务保存聚合和发布首个 `ExecutorEvent`。重复的同一 `Create` 返回幂等空操作；
  同一 id 携带不同 Flow 引用或 inputs 时拒绝冲突。
- `CREATED` 继续是 Execution 的合法内部初始状态，但公开 Interface 不再允许产生一个
  与启动 Command/Event 脱离的持久化 `CREATED` Execution。
- 删除 `CreateExecutionCommand`、`ContinueExecutionCommand` 及对应 Handler；
  `ExecutionService` 不再依赖 `CommandExecutor` 或直接发布 `ExecutorEvent`。
- ADR 0050 原先描述的可信宿主精确物化契约不再受支持。如果未来出现已确认的外部可靠
  启动需求，应设计一次调用即可完成的幂等启动 Command，并通过新的 UC 和 ADR 确认，
  不重新暴露两阶段 Execution 生命周期。

## 理由

一次完整启动使 Flow Module 独占 Execution 创建、输入确认和首次推进的不变量。调用方
只需理解 Queue 受理回执和后续查询语义，不需要维护调用顺序或清理半启动实例。
同一条 `Create` 消费链集中处理精确版本、身份、幂等、持久化和内部 Event 发布，减少
Interface 的同时提高实现的局部性。

## 后果

- `createPending`、`continueExecution` 及指定版本的公开 `create` 是不兼容删除；仓库内
  没有生产调用点需要迁移。
- `create` 返回的 `Create` 是 Queue 受理回执，其中的 Execution id 在消费前已经稳定；
  紧接着查询该 id 仍可能暂时找不到持久化 Execution。
- 无效 inputs 在 Queue 受理前失败，不会留下 Execution，也不会形成持续重试的无效
  Command。
- Execution 表、TaskRun 表和 Queue 表结构不变，本决策不需要数据库基线迁移。
