# ADR 0002：Execution 运行域与 Executor 状态机

## 状态

Accepted（生产内存实现部分由 ADR 0007 修订；Execution 的 Flow 引用名称及
定义类型由 ADR 0008 修订；领域对象创建方式由 ADR 0010 修订；
Executor/Worker 包边界由 ADR 0012 修订）

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

第一阶段状态固定为：

```text
ExecutionStatus: CREATED, RUNNING, COMPLETED, FAILED, CANCELED
TaskRunStatus:   CREATED, RUNNING, COMPLETED, FAILED, CANCELED
```

`PAUSE` 是 Task 类型，不是状态。等待外部触发时：

```text
Execution = RUNNING
TaskRun   = RUNNING
ExternalTask = WAITING
```

### 调用链

```text
ExecutionService
  -> CommandExecutor
  -> CreateExecutionHandler
  -> Execution.create(...)
  -> ExecutionHandler
  -> ExecutorService
  -> WorkerDispatcher
  -> WorkerTaskHandler
```

- Controller 位于 Core 外。
- `CreateExecutionHandler` 加载最新已部署 Flow，并调用
  `Execution.create(...)` 创建 `CREATED` Execution。
- `ExecutionHandler` 建立可恢复的 `ExecutorContext`，管理中间聚合保存和
  Worker 调用。
- `ExecutorService` 只管理状态与编排，不访问 Repository，不执行 Task。
- 只有 `ExecutorService.handleNext()` 可以创建 TaskRun。
- TaskRun 先创建为 `CREATED`，派发 Worker 前通过领域方法进入 `RUNNING`。

### Worker

- Worker 在第一阶段与 Executor 同进程同步调用。
- Worker 只执行一个 Task，不读取或修改 Execution/TaskRun。
- Worker 接收不可变 `WorkerTask`，通过 `WorkerContext` 使用当前命令的
  Session 和 DSLContext，返回 `WorkerTaskResult`。
- `DefaultTaskHandler` 处理普通 `AUTO` Task。
- `PauseTaskHandler` 处理 `PAUSE` Task，并直接保存 Task 自己拥有的
  ExternalTask 记录。
- Worker 的明确失败结果使 TaskRun 和 Execution 进入 `FAILED` 并正常提交；
  框架异常继续抛出并回滚命令。

### PAUSE 与恢复

- PAUSE Worker 创建 ExternalTask 后返回 `RUNNING`，当前命令生命周期停止。
- ExternalTask 可以代表用户处理、服务回调、定时器或信号；第一阶段实现
  ExternalTask 一对一触发器。
- 触发器完成自己的记录后，在同一命令事务内调用
  `ExecutionService.resume(...)`。
- resume 直接完成原 RUNNING PAUSE TaskRun，不能再次执行 PAUSE Worker。
- TaskRun 完成后，`ExecutorService` 自动调用下一轮 `handleNext()`。

### 取消

- 取消 Execution 时，所有当前 `CREATED/RUNNING` TaskRun 一并取消。
- Task 自己拥有的等待资源由对应 WorkerTaskHandler 取消。
- 任一取消动作失败时，整个取消命令回滚。

### 事务与并发

- JOOQ DSLContext 由 `CommandExecutor` 建立，命令是事务边界。
- 一个命令可以连续执行多个同步 Task，直到遇到 PAUSE、失败或终态。
- `ExecutionHandler` 可以在同一事务内中间保存 Execution/TaskRun，使
  Worker 保存带外键的 Task 业务记录。
- 中间保存不等于提交。
- Execution 聚合只持有一个 `lockVersion`；修改已有 Execution 的命令最多
  增加一次版本。
- TaskRun 不独立持有乐观锁版本。

## 第一阶段范围

已实现：

- 顶层 Task 严格顺序执行。
- AUTO 同步 Worker。
- PAUSE、ExternalTask 完成与自动恢复。
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
- Execution、TaskRun 的目标字段、方法、状态转换和迁移差距由
  [`execution-domain-model.md`](../standards/execution-domain-model.md)
  统一规定。
