# ADR 0045：通过 RunContext variables 传递 Execution 与 TaskRun inputs

## 状态

Accepted

## 背景

RunnableTask 需要把业务操作关联到当前 Flow Execution。此前 `RunContext` 单独保存
`executionId` 和 `inputs`，Worker 投递协议因此同时维护运行身份字段和输入字段。
随着宿主 Task 需要更多调用期变量，继续为每个值扩展 `RunContext` 的固定字段会扩大
接口并分散运行时变量的装配逻辑。

## 备选方案

### 方案一：继续为每个上下文值增加独立字段

调用方可以直接获得类型明确的字段，但每增加一种运行时值都要同时修改
`RunContext`、Worker 投递和所有测试，变量装配逻辑分散。

### 方案二：把 Execution 和 inputs 合并到 TaskRun.inputs

可以复用现有输入传递链路，但会把不可持久化的 Execution 聚合放入 TaskRun 持久化
数据，破坏 inputs 的 JSON 数据边界。

### 方案三：使用不可变 variables 作为调用期运行时变量载体

WorkerTask 携带统一的 `Map<String, Object>`。系统保留键保存当前 Execution 和实际
inputs，RunContext 对常用值提供类型化便捷方法。

## 决策

采用方案三：

- `RunContext` 只保存不可变 `variables`，不再保存独立的 `executionId` 或 `inputs`
  字段。
- `$flow.execution` 是系统保留键，值必须是当前 `Execution`；`executionId()` 从该
  值返回 `Execution.id()`。
- `$flow.inputs` 是系统保留键，值是当前 TaskRun 的实际输入 Map；`inputs()` 从该
  值返回不可变输入视图。
- `ExecutorService` 创建 WorkerTask 时负责放入当前 Execution；WorkerTask 负责把
  inputs 放入 `$flow.inputs`，并将 variables 传给 WorkerDispatcher。
- `WorkerTask.executionId` 暂时保留为 Executor/Worker 结果关联字段。生产创建时它必须
  与 `$flow.execution` 中 Execution 的 id 一致。
- variables 只属于一次 RunnableTask 调用，不写入 Flow、Execution 或 TaskRun 持久化
  数据。Map 结构不可变；Task 不得修改变量中的 Execution 或通过它推进流程状态。
- `RunContext` 继续只提供 `executionId()`、`inputs()` 等小范围便捷接口；Task 不获得
  TaskRun、Executor 或状态推进入口。

## 理由

- 运行时变量的装配集中在 Worker 投递链路，新增调用期值不需要复制一组固定字段。
- inputs 仍保持 JSON 可持久化边界，不会因为传递 Execution 而污染 TaskRun 数据。
- `executionId()` 继续为 Task 提供稳定且简单的使用接口，业务代码不需要依赖
  Execution 聚合的生命周期方法。
- 不可变 Map 保证调用方不能替换变量绑定；Executor 仍是 Execution 状态变化的唯一
  所有者。

## 后果

- 当前同步、同 JVM Worker 可以传递真实 Execution 对象；未来跨进程 Worker 不能直接
  序列化该对象，需要改为传递 executionId 或其他可序列化运行时值。
- 任何使用 `RunContext.create` 的测试都必须按 `$flow.inputs` 组装 variables；需要
  `executionId()` 的测试还必须提供 `$flow.execution`。
- 该决策修订 ADR 0024 和 ADR 0044 中关于 RunContext 不携带 Execution 的局部描述，
  但不改变 BeanContext 已移除、Task 不推进状态和 Worker 不处理编排的决定。
