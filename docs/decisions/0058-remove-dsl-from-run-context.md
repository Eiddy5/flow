# ADR 0058：移除 RunContext 的数据库上下文

## 状态

Accepted

## 背景

`RunContext` 原本同时向 `RunnableTask` 暴露当前 `Session`、
`DSLContext` 和调用期变量。Worker 只为构造 `RunContext` 转发 `DSLContext`，
当前生产 Task 没有使用 `context.dsl()`；这使 RunnableTask 的通用调用契约隐含了
jOOQ 和数据库访问能力。

执行事务由外部 `ExecutionCommandEventHandler` 和内部
`handlers.ExecutorEventHandler` 分别建立；内部处理器使用同一个 `DSLContext` 在
一个 Executor Event 周期内保存 Execution 并投递后续 Event。该事务资源属于运行提交
边界，不属于每个 Task 都应当直接拥有的通用调用上下文。

## 备选方案

### 方案一：继续保留 `RunContext.dsl()`

可以让任意 RunnableTask 直接执行 SQL，但会把数据库基础设施和隐式事务依赖扩散到
所有 Task 插件。

### 方案二：把 `DSLContext` 包装成通用数据库 Resolver

可以隐藏 jOOQ 类型，但仍然向 Task 暴露任意数据库访问能力，未解决依赖不透明和职责
边界过宽的问题。

### 方案三：从 RunContext 移除数据库上下文

RunContext 只提供 Session 和本次调用所需的不可变 variables；需要数据库能力的插件
通过明确的业务 Service 或 Adapter 接入，并自行声明事务语义。

## 决策

采用方案三：

- 从 `RunContext` 移除 `DSLContext` 字段、`dsl()` 方法和 `create(...)` 参数。
- `WorkerDispatcher.dispatch(...)` 只接收 Session 和 WorkerTask，不再转发 DSL。
- `ExecutorEventHandler` 保留自己的 `DSLContext` 参数，用于 Execution 持久化、Worker
  调用和后续 Event 投递，不把事务资源放入 `ExecutorContext`。
- RunnableTask 不得通过 RunContext 直接访问数据库；宿主业务能力通过明确的
  Service/Adapter 接口接入。

## 理由

- 让 RunnableTask 的公开依赖与实际能力保持一致，当前内置 Task 不再携带无用资源。
- 避免把 jOOQ 和通用 SQL 能力泄漏到所有 Task 插件。
- 保留 Executor 对事务和 Execution 状态提交的唯一控制权。

## 后果

- 依赖 `context.dsl()` 的外部 Task 必须迁移到明确的 Service 或 Adapter；当前仓库
  没有生产 Task 使用该方法。
- Worker 调用契约变为 `Session + WorkerTask -> WorkerTaskResult`。
- 本决策不改变 Executor/Repository 使用 `DSLContext` 的事务协议，也不改变
  Execution、TaskRun 的持久化边界。
