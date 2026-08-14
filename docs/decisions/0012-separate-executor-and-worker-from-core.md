# ADR 0012：Executor 与 Worker 作为 Core 同级运行组件

## 状态

Accepted（Worker 结果状态类型由 ADR 0017 修订；单轮上下文与 DefaultExecutor
提交边界由 ADR 0020 修订；WorkerTaskHandler 与 WorkerContext 由 ADR 0024
替代）

DefaultExecutor 的提交职责已由 ADR 0051 迁移到 Executor Event Queue；外部 Command
由 `ExecutionCommandEventHandler` 处理，内部周期由 `ExecutorEventHandler` 负责。
DefaultExecutor 当前只负责两条 Queue 路由。

## 背景

现有运行链路已经把 Execution 领域状态、下一任务计算和具体 Task 执行划分为
不同职责，但 Java 包仍把 Executor 状态机和 Worker 调度协议放在
`org.cses.flow.core` 内。该目录结构会把运行组件误解为 Core 的内部技术目录，
也无法直接表达 Executor、Worker 与 Core 是三个同级组件的边界。

Executor 与 Worker 的职责和变化原因不同：

- Core 保存领域事实、用例、事务内 Handler 和 Repository 端口。
- Executor 根据 Flow 定义和 TaskRun 事实推进 Execution 状态机。
- Worker 派发并执行单个 Task，返回运行结果事实。

## 备选方案

### 方案一：继续放在 Core 技术子目录

保留 `core/executors`、`core/workers` 和
`core/services/executions/ExecutorService`。移动最少，但目录继续隐藏三个组件
之间已经存在的边界。

### 方案二：只移动上下文和协议类型

把现有 `core/executors` 与 `core/workers` 机械移动到顶层，但仍把
`ExecutorService` 留在 Core Service。目录变化较小，却会把同一个 Executor
职责拆在两个顶层包中。

### 方案三：按完整职责建立顶层 Executor 与 Worker

将 Executor 状态机、上下文和下一任务模型统一放入顶层 `executor`，将 Worker
调度协议、输入和结果模型统一放入顶层 `worker`。Core Handler 保留命令事务和
Repository 协调，具体 Task 与 WorkerTaskHandler 实现继续作为扩展。

## 决策

采用方案三。

- `org.cses.flow.executor` 与 `org.cses.flow.core` 平级，包含
  `ExecutorContext`、`ExecutorService` 和 `DefaultExecutor`；ADR 0020 移除了
  只包装 Task 与 TaskRun 的浅层 NextTask，Context.nexts 直接保存 TaskRun。
- `org.cses.flow.worker` 与 `org.cses.flow.core` 平级，包含
  `WorkerContext`、`WorkerDispatcher`、`WorkerTask`、`WorkerTaskHandler`、
  `WorkerTaskResult`；结果使用 Flow 定义域统一的 `State.Type targetState`，
  完整 State 与 History 由 Execution 聚合中的运行对象持有，不再定义 Worker
  专属 outcome 枚举。
- `ExecutionCommandEventHandler` 负责外部 Command 的校验、Execution 物化/锁定和
  内部 Event 投递；`ExecutorEventHandler` 负责当前 Event 事务内的聚合中间保存、
  Executor 驱动和 Worker 派发。Core CommandHandler 不直接进入 Executor 状态机。
- AUTO、PAUSE 等具体 Task 类型及 WorkerTaskHandler 实现继续放在
  `extensions`，顶层运行组件不依赖具体扩展实现。
- `core` 不再建立 `executors` 或 `workers` 技术目录，也不保留
  `ExecutorService` 的重复入口。
- ADR 0020 历史上把原 ExecutionHandler 职责迁入 DefaultExecutor；ADR 0051 又将
  该提交职责迁入 Executor Event Queue，并将 DefaultExecutor 收敛为两条 Queue 路由。原有
  handleNext/onNexts 的处理分为计划和应用两阶段；Execution、TaskRun、Worker 结果、
  事务和恢复的领域语义保持不变。

## 理由

- 顶层目录直接表达 Core、Executor 和 Worker 的组件边界。
- Executor 的状态机类型集中在一个包中，避免状态推进逻辑伪装成通用 Core
  Service。
- Worker 的调用协议与领域模型分开，同时保留具体 Task 行为的扩展能力。
- ExecutorEventHandler 拥有一个 Event 周期内的 Repository 中间保存，可以维持既有
  稳定态事务和 Worker 外键记录要求。

## 后果

- Core 的 Execution CommandHandler 和 ExecutionService 通过顶层 Executor
  类型建立运行上下文。
- ExecutorEventHandler 同时依赖 Core Repository 端口与 Worker，但具体扩展仍由
  Micronaut 按 WorkerDispatcher 协议注入。
- 生产代码、测试和文档中的旧包导入必须一次迁移，不能保留兼容包装或重复类型。
- 架构测试必须阻止 Executor 或 Worker 源码重新进入 Core。
- ADR 0001 与 ADR 0002 的运行语义继续有效，其包边界由本 ADR 修订。
