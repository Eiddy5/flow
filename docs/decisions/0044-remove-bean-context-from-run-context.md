# ADR 0044：移除 RunContext 的通用 BeanContext 查找

## 状态

Accepted

## 背景

`RunContext` 是一次 `RunnableTask` 调用的最小运行接口。它原本除了
`Session`、`DSLContext`、`executionId` 和只读 `inputs` 外，还持有宿主的
Micronaut `BeanContext`，并允许 Task 通过 `bean(Class<T>)` 查找任意宿主 Bean。

该做法可以让 Jackson 物化的 Task 调用宿主业务 Service，但也把通用 IoC 容器暴露给
Task，形成隐式依赖和 Service Locator；Task 的依赖无法从其接口看出，Core 的运行接口
也被具体 DI 框架污染。当前需求只要求 Task 获取当前 Flow 实例身份，不要求 Task
直接访问通用容器。

## 备选方案

### 方案一：继续暴露 BeanContext

改动最小，但任意 Task 都可以查找任意 Bean，继续扩大运行接口和宿主耦合。

### 方案二：把 BeanContext 包装成通用 Resolver

可以隐藏 Micronaut 类型，但仍然保留任意类型查找和隐式依赖，只是改变了外层名称。

### 方案三：移除通用查找，后续按业务能力建立明确扩展接口

`RunContext` 只保留当前调用必需的运行身份、Session、DSLContext 和只读输入。需要
宿主业务能力时，由具体扩展定义明确的接口和 Adapter，不把宿主容器放入通用运行上下文。

## 决策

采用方案三：

- 从 `RunContext` 移除 `BeanContext` 字段、构造参数和 `bean(Class<T>)` 方法。
- 从 `WorkerDispatcher` 移除 `BeanContext` 依赖及其注入构造器。
- `RunContext` 继续提供 `executionId`，用于 Task 将业务操作关联到当前 Flow Execution；
  当前 Execution 通过 `variables` 的 `$flow.execution` 保留键传入，实际 inputs 通过
  `$flow.inputs` 传入。
- Task 不得通过通用容器查找宿主 Bean，也不得直接访问 Flow 的 Execution/TaskRun
  Repository 或修改传入的 Execution。
- 宿主业务能力若确有需要，必须通过后续明确的扩展接口或 Adapter 接入；该接口应
  表达具体业务行为，而不是暴露通用 Bean 查找。

## 理由

- 收窄 `RunContext` 的公开接口，保持 Worker 到 RunnableTask 的调用契约简单且可测试。
- 执行身份仍然可以传入 Task；Execution 只作为 `variables` 中的运行时值传递，不提供
  TaskRun 或 Executor 的状态推进入口。
- 移除 Core 运行接口中的 Micronaut 容器概念，降低框架耦合。
- 让宿主业务依赖显式化，避免 Task 在运行时任意解析隐藏依赖。

## 后果

- 现有依赖 `context.bean(...)` 的 Task 必须迁移到明确的扩展接口或 Adapter；当前仓库
  没有生产 Task 调用该方法。
- Worker 入口通过不可变 `variables` 同时传入当前 Execution 和实际 inputs；`executionId()`
  从 `$flow.execution` 解析，`inputs()` 从 `$flow.inputs` 解析。
- 本 ADR 不改变 Task 的状态推进、Worker 投递或 Execution 持久化协议。
