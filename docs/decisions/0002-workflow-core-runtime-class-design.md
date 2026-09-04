# ADR 0002：Execution 运行域与 Executor 状态机

> 运行提交与内部状态交接的当前方案由 ADR 0059 修订：`ExecutionRunner` 已删除，
> 外部 Command 由 `ExecutionCommandEventHandler` 处理，内部周期由
> `ExecutorEventMessageHandler` 通过 `ExecutorEvent` Queue 交接。本文保留领域模型和历史
> 推导背景，涉及提交边界的旧描述以 ADR 0059 为准。

## 状态

Accepted（生产内存实现部分由 ADR 0007 修订；Execution 的 Flow 引用名称及
定义类型由 ADR 0008 修订；领域对象创建方式由 ADR 0010 修订；
Executor/Worker 包边界由 ADR 0012 修订；PAUSE 与外部业务能力边界由
ADR 0016 补充；运行状态类型与状态机由 ADR 0017 修订；单轮上下文、nexts
两阶段应用与提交协调边界由 ADR 0020 修订；RunnableTask、BranchTask、Worker
调用方式及 ExecutorService 状态推进循环由 ADR 0024 与后续确认模型修订）

## 背景

Flow 负责保存已部署的 Task 编排定义，运行时需要保存一次启动产生的真实
执行事实，并在普通同步任务、外部卡点、恢复和取消之间保持统一生命周期。

早期方案把 Execution 设计成移动游标，并通过 Main/Child Execution 表达
分支。该方案已经废止：Execution 是一次完整启动实例，真实路径由其有序
TaskRun 集合表达。

## 决策

### 领域边界

- `Execution` 是独立聚合根，代表一次完整的 Flow 启动实例。
- Execution 保存 `flowId + flowReversion`，启动时绑定当前 `DEPLOYED`
  Flow Reversion。
- Execution 不持有当前位置；实际执行路径由有序 `TaskRun` 列表表达。
- `TaskRun.taskId` 关联 Task 的稳定领域 id。
- `TaskRun.parentId` 是真实运行实例中的父 TaskRun id，不是前驱关系。
- 同一个 Task 可以产生多个 TaskRun，例如未来 Loop 的每次迭代。
- TaskRun 独立存表，但由 `ExecutionRepository` 随聚合统一加载和保存。

### Task 身份

- YAML 只声明 Task `key`，不暴露数据库 id。
- 首次成功部署该业务 Task 时，系统为 Task 分配稳定 `taskId`。
- 新 Flow Reversion 中相同 key 的 Task 复用原 taskId。
- Task 定义快照由 `(flow_id, flow_reversion, task_id)` 联合定位。
- `Task.parentId` 保存稳定父 taskId，并只在当前 Flow Reversion 中解析。
- Task key 重命名的身份迁移规则留待后续设计。

### 状态

工作流运行统一使用 Flow 定义域的 `State`，其状态类型固定为：

```text
State.Type: CREATED, RUNNING, WAITING, COMPLETED, TERMINATED
```

Execution 与 TaskRun 各自持有包含 `current + history` 的完整 State；Worker
结果只报告目标 `State.Type`。通用迁移与历史追加由 State 集中管理，聚合自己的
具体路线和业务前置条件仍由 Execution、TaskRun 管理。完整决策见 ADR 0017。

`PAUSE` 是 Task 类型，不是状态。等待外部触发时：

```text
Execution = WAITING
TaskRun   = WAITING
```

### 调用链

```text
ExecutionService
  -> ExecutionCommand Queue
  -> ExecutionCommandEventHandler
  -> Execution.create(...) + ExecutorEvent Queue
  -> ExecutorEventHandler
  -> ExecutorService.process
  -> WorkerDispatcher
  -> WorkerTaskHandler
```

- Controller 位于 Core 外。
- `ExecutionService` 在受理时选择最新已部署 Flow、规范化 inputs 并生成稳定 Execution
  id；`ExecutionCommandEventHandler` 按 Command 的精确 Flow 引用调用
  `Execution.create(...)`，原子保存 CREATED Execution 并发布首个内部 Event。具体
  单一启动 Interface 由 ADR 0068 定义。
- `ExecutorContext` 只保存精确 Flow、Execution 和本轮增量；
  `ExecutorEventMessageHandler` 管理单个 Event 周期内的中间聚合保存和 Worker 调用；
  `DefaultExecutor` 的当前 Queue 路由职责由 ADR 0059 定义。
- `ExecutorService` 只管理状态与编排，不访问 Repository，不执行 Task。
- `handleNext()` 只暂存下一批 TaskRun 计划；`onNexts()` 才启动首次 Execution、
  把批次并入聚合并形成 WorkerTask。
- TaskRun 创建时为 CREATED，派发 Worker 前进入 RUNNING；两者属于同一个运行
  大类，但保留两个具体状态及各自 History。

### Worker

- Worker 在第一阶段与 Executor 同进程同步调用。
- Worker 只执行一个 Task，不读取或修改 Execution/TaskRun。
- Worker 接收不可变 `WorkerTask`，通过 `WorkerContext` 使用当前命令的
  Session 和 DSLContext，返回 `WorkerTaskResult`。
- `DefaultTaskHandler` 处理普通 `AUTO` Task。
- `PauseTaskHandler` 处理 `PAUSE` Task，并返回 `WAITING` 使原 TaskRun 成为
  持久化等待事实。
- Worker 的明确失败结果使 TaskRun 和 Execution 进入 `TERMINATED` 并正常提交；
  框架异常继续抛出并回滚命令。

### PAUSE 与恢复

- PAUSE Worker 返回 `WAITING`；当无其他 CREATED/RUNNING 工作后当前命令生命
  周期停止，WAITING TaskRun 自身
  表达持久化等待事实。
- 审批、表单、工单等外部能力保存 executionId 和 taskRunId，并通过
  `ExecutionService.resume(...)` 提交结果。
- `ExecutionCommandEventHandler` 在 Command Queue 消费事务内校验并投递内部 Event；
  `ExecutorEventMessageHandler` 在 Event 消费事务内加载绑定的 Execution 与确定 Flow Reversion，
  校验 PauseTask 契约后调用 `ExecutorService.resume(...)`。
- resume 直接完成原 WAITING PAUSE TaskRun，不能再次执行 PAUSE Worker。
- TaskRun 完成后，`ExecutorService` 自动调用下一轮 `handleNext()`。
- 外部能力不能直接修改 Execution、TaskRun、路由或下一 Task。

### 取消

- 取消 Execution 时，所有当前 `CREATED/RUNNING/WAITING` TaskRun 一并进入
  `TERMINATED`。
- Task 自己拥有的等待资源由对应 WorkerTaskHandler 取消。
- 任一取消动作失败时，整个取消命令回滚。

### 事务与并发

- JOOQ DSLContext 由 `CommandExecutor` 建立，命令是事务边界。
- 一个命令可以连续执行多个同步 Task，直到遇到 PAUSE、失败或终态。
- `ExecutorEventMessageHandler` 可以在同一 Event 事务内中间保存 Execution/TaskRun，使
  Worker 保存带外键的 Task 业务记录。
- 中间保存不等于提交。
- Execution 聚合只持有一个 `lockVersion`；修改已有 Execution 的命令最多
  增加一次版本。
- TaskRun 不独立持有乐观锁版本。

## 第一阶段范围

已实现：

- 顶层 Task 严格顺序执行。
- AUTO 同步 Worker。
- PAUSE、ExecutionService 统一 resume 与自动恢复。
- Execution 取消。
- 测试源码中的内存 Repository 和 Micronaut 自动装配。

后续扩展：

- Parallel、条件路由、嵌套 Task 和第一阶段 `dependOn` 汇合规则已由
  ADR 0006 补充并实现。
- Loop 的 `handleNext` 规则。
- 远程 Worker、异步消息队列和取消确认状态。
- PostgreSQL Repository、数据库顺序列和完整 CAS 实现。
- 超时、重试与更多 PAUSE 触发器。

## 后果

- 不再使用 Main Execution、Child Execution、ExecutionTree 或“Execution
  是线路游标”等概念。
- 恢复状态机只需要加载完整 Flow、Execution 和有序 TaskRun。
- 新 Task 类型通过外部扩展单元和 WorkerTaskHandler 注册，不修改
  Executor 的状态编排职责。
- Execution、TaskRun 和 Executor 的后续修订由 ADR 0017、ADR 0020、ADR 0021
  和 ADR 0024 继续约束；当前决策链见 [`README.md`](README.md)。
