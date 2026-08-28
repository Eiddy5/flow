# ADR 0024：区分 RunnableTask 与 BranchTask 能力

## 状态

Accepted（Task 能力分类继续有效；Task 不可变构造、TaskExtension 注册和外部插件
相关条款由 [ADR 0026](0026-use-task-class-as-in-project-plugin.md) 取代）

## 背景

现有运行链路把每个 Task 都包装成 WorkerTask，再由 WorkerTaskHandler 按具体
Task 类型选择执行实现。AUTO 的确需要执行具体逻辑，但 PAUSE、PARALLEL 只改变
Execution 的编排状态，同样经过 Worker 会产生以下问题：

- Worker 被迫理解等待、并行展开和 TaskRun 状态推进。
- 一个具体 Task 的执行逻辑分散在 Task 与独立 Handler 两处。
- Runnable 实现能够通过 WorkerContext 看到 WorkerTask，进而接触本应由
  Executor 管理的 taskRunId、Execution 调度信息和取消流程。
- 外部 Task 插件必须同时注册 TaskExtension 与 WorkerTaskHandler，定义扩展和
  运行能力无法由类型本身形成完整约束。

已确认 Task 仍是所有具体步骤定义的共同基类，但“能够执行”和“能够控制流程”是
两种互斥能力。Worker 只执行前者，Executor 直接处理后者。

## 备选方案

### 方案一：继续使用 WorkerTaskHandler

所有 Task 仍进入 Worker，PAUSE 和 PARALLEL 通过返回 WAITING 或 COMPLETED
模拟流程控制。该方案改动最小，但保留了 Worker 对编排状态的控制。

### 方案二：在 Task 基类增加统一 execute 方法

所有 Task 都实现同一个执行入口，结构节点在方法内返回特殊结果。该方案把两类
能力重新混合在基类中，也让 BranchTask 看起来具有实际执行逻辑。

### 方案三：Task 子类型显式实现一种运行能力

具体 Task 继续继承 Task，同时恰好实现 RunnableTask 或 BranchTask。RunnableTask
在自身 `run(RunContext)` 中实现具体工作；BranchTask 只声明流程控制特征，由
Executor 根据 Execution 和 TaskRun 事实完成状态推进。

## 决策

采用方案三。

- `Task` 继续是 Flow 聚合内的不可变抽象实体，不提供统一 `run` 方法，也不提供
  Worker 或 Executor 状态。
- 每个能够进入运行链路的具体 Task 必须恰好实现以下一个接口：
  - `RunnableTask`：声明 `RunResult run(RunContext)`。
  - `BranchTask`：只声明 Executor 所需的流程控制特征，不声明 `run` 方法。
- `RunnableTask` 与 `BranchTask` 都是 Task 的能力，而不是 Worker 或 Executor
  的运行时协议；它们离开 Task 后没有独立业务意义。因此两个接口必须与 `Task`
  一起位于 `core/domains/tasks`。`RunContext`、`RunResult` 是 RunnableTask 的
  直接调用契约，也归入该包。Worker 和 Executor 只消费、不能拥有这些能力。
- `RunContext` 每次只服务一次 RunnableTask 调用。它提供当前 Session 和不可变运行时
  `variables`；变量保留键 `$flow.execution`、`$flow.inputs` 与 `$flow.taskInputs` 分别
  携带当前 Execution、Execution 级 Flow inputs 和当前 TaskRun inputs，并通过
  `executionId()`、`inputs()`、`taskInputs()` 提供便捷访问。它不提供 Task、WorkerTask、
  TaskRun、nexts 或状态推进入口。
- `RunResult` 只允许表达 COMPLETED 或 TERMINATED。WAITING 属于 BranchTask 的
  编排结果，不能由 RunnableTask 或 Worker 返回。
- Executor 在把计划并入 Execution 前校验能力互斥性。没有能力或同时实现两种
  能力的 Task 都不能被调度。
- RunnableTask 才会形成 WorkerTask。WorkerDispatcher 是通用调用边界，直接
  调用具体 Task 的 `run`，不再发现、选择或调用 WorkerTaskHandler。
- BranchTask 仍产生由 Execution 管理的 TaskRun，以保留真实运行历史和稳定暂停
  身份，但绝不形成 WorkerTask：
  - PAUSE 由 Executor 使 TaskRun 进入 WAITING。
  - PARALLEL 结构节点由 Executor 完成自身 TaskRun，再把匹配的直接子 Task 组成
    并行批次。
- `ExecutorService.handle(...)` 是 Task 能力分支的状态推进循环：每批 TaskRun
  并入 Execution 后，RunnableTask 暂存 WorkerTask 并返回提交边界；BranchTask
  直接完成或等待，非等待分支随后立即继续推导下一批 TaskRun。BranchTask 状态推进
  由 `ExecutorEventHandler` 在 `ExecutorContext` 周期内完成，后续周期通过
  `ExecutorEvent` Queue 交接；DefaultExecutor 的当前 Queue 路由职责由 ADR 0059 定义。
- TaskExtension 与通用 Plugin 只负责类型注册、创建、重建和类型专有 properties，
  与 RunnableTask/BranchTask 的运行能力正交。外部 Runnable Task 插件把执行逻辑
  写在具体 Task 类的 `run` 方法中，只需通过 Plugin SPI 注册 TaskExtension。
- ExternalTask 兼容入口已按 ADR 0016 删除。PAUSE 等待只由 Execution 中的
  TaskRun 表达，不得在 Worker 或 RunnableTask 中建立第二套等待资源。

## 理由

- Worker 的职责收敛为执行一个 RunnableTask，Executor 保持对 Execution、
  TaskRun、等待、并行和后续路线的唯一控制。
- 按能力所属业务概念拆包，Task 的类型约束不再因当前由哪个运行时组件消费而
  分散；未来替换 Worker 或 Executor 实现不会改变 Task 能力的归属。
- RunnableTask 只面对一次调用的最小上下文，无法直接改变调度事实。
- 具体执行逻辑和具体 Task 类型放在一起，插件不再需要第二套 Handler 发现协议。
- PAUSE、PARALLEL 仍保留真实 TaskRun，运行历史、恢复身份和并行父子关系不丢失。
- 新能力通过 Java 接口可见，避免根据类型字符串把所有 Task 隐式送入 Worker。

## 后果

- 删除 WorkerContext、WorkerTaskHandler 以及 `extensions/workers` 中的执行实现。
- WorkerTaskResult 不再接受 WAITING。
- Log 实现 RunnableTask；Pause 和 Parallel 实现 OrchestrationTask。
- RunnableTask、BranchTask、RunContext 和 RunResult 统一归入 Task 领域包；
  Worker 与 Executor 包只保留运行时组件和投递/结果信封。
- ParallelTask 的并行子节点语义从 Task 基类迁移到 BranchTask 能力。
- 测试专用执行逻辑必须由测试专用 RunnableTask/TaskExtension 提供，不能替换通用
  Worker Handler。
- ADR 0002、0012、0020、0021 和 0023 中关于所有 Task 形成 WorkerTask、通过
  WorkerTaskHandler 执行 PAUSE/PARALLEL、以及外部插件注册 WorkerTaskHandler 的
  内容由本决策替代；其余领域、事务和插件物化决策继续有效。
