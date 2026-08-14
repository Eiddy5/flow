# ADR 0016：PAUSE 通过 Execution 生命周期统一恢复

## 状态

Accepted（ExternalTask 迁移遗留已于 2026-08-12 删除；运行状态细节由
ADR 0029 与 ADR 0031 修订）

## 背景

Flow 运行到 PAUSE 后需要停止同步推进，等待审批、表单、工单或外部服务等能力
产生结果。早期实现为每个 PAUSE TaskRun 创建一个 `ExternalTask` 聚合，再由
`ExternalTaskService.complete(...)` 间接恢复 Execution。

现已确认：

- PAUSE 是流程编排中的通用外部等待点，不是审批任务或用户待办。
- 等待事实已经由 Execution 中处于 `WAITING` 的 PAUSE TaskRun 完整表达。
- `resume` 是 Execution 的生命周期用例，应由 `ExecutionService` 统一提供。
- 外部能力只提交暂停点引用和 outputs；Flow Core 继续拥有状态机、路由、并行
  汇合和下一 Task 的决定权。

因此，不能再把 ExternalTask 作为 PAUSE 恢复的目标领域聚合或公开入口。

## 备选方案

### 方案一：ExternalTask 作为 Flow Core 的等待聚合

PAUSE 创建 ExternalTask，外部能力完成 ExternalTask 后恢复 Execution。该方案
可以提供独立等待对象，但与已经持久化的 WAITING PAUSE TaskRun 重复表达同一
事实，并迫使所有外部能力依赖额外身份和生命周期。

### 方案二：外部能力直接修改 Execution

审批等外部系统直接改变 TaskRun、计算路由或指定下一 Task。该方案破坏 Flow
Core 对统一 State 状态机的唯一控制权。

### 方案三：ExecutionService 提供统一 resume 用例

PAUSE TaskRun 进入 WAITING。外部能力保存 `executionId + taskRunId`，完成自身
业务后调用 `ExecutionService.resume(...)`。Core 在一个命令事务内校验并完成
原 TaskRun，再继续推进同一 Execution。

## 决策

采用方案三。

- PAUSE 等待期间的持久化组合固定为：

  ```text
  Execution = WAITING
  TaskRun   = WAITING
  Task type = PAUSE
  ```

- `ExecutionService.resume(session, executionId, taskRunId, outputs)` 是外部能力
  恢复流程的唯一公开 Core 用例。
- Service 校验并规范化 Resume 数据后构造 Executor `Resume` Command，投递到持久化
  `ExecutionCommand` Queue；`ExecutionCommandEventHandler` 消费消息后校验并投递
  `ExecutorEvent`，由 `ExecutorEventHandler` 加载 Execution 及其绑定的 Flow Reversion。
- Resume Handler 必须校验：
  - 调用租户与 Execution 一致。
  - Execution 与目标 TaskRun 存在。
  - Execution 和目标 TaskRun 的 `state.current()` 均为 `WAITING`。
  - TaskRun 对应的 Task 类型是 PAUSE。
  - outputs 满足该 PAUSE Task 的输出契约。
- 校验通过后，`ExecutorEventHandler` 通过 `ExecutorContext` 调用
  `ExecutorService.resume(...)` 完成原 TaskRun，并推进到下一稳定态或终态。
- 外部能力不能提交 Flow Reversion、路由结果、下一 Task 或目标 Execution
  状态，也不能直接调用 Handler、Repository 或 Executor。
- 审批、表单、工单等能力独立拥有业务身份、生命周期、受派、权限和审计。它们
  只保存暂停点引用并调用 ExecutionService。
- PAUSE 取消时，Core 只保证 Execution 及活动 TaskRun 的取消事实；通知外部
  业务关闭待办属于后续对接协议。
- 跨 Server 恢复只依赖持久化的 Flow、Execution 和 TaskRun，不依赖启动
  Execution 的原进程或额外等待聚合。

本决策不规定外部传输协议、服务身份认证、回调幂等键、Outbox、超时或重试。这些
能力确认后必须继续复用同一个 `ExecutionService.resume(...)` 用例，不能建立第二
套流程恢复逻辑。

## 理由

- Execution 已经是运行聚合根，TaskRun 已经是实际步骤运行事实，无需重复等待
  聚合。
- 外部调用只进入一个稳定 Service，随后自然落入现有命令事务和 Execution
  生命周期。
- Flow Core 保持对状态、路由、并行汇合和后续任务的唯一控制。
- 审批等业务可以独立演进权限与生命周期，不污染 PAUSE 和 Execution。
- `executionId + taskRunId` 能精确定位并行或多阶段流程中的一个暂停点，并支持
  从 PostgreSQL 跨进程恢复。

## 后果

- 新代码不得以 ExternalTask id 作为 PAUSE 的公开恢复身份，也不得把
  ExternalTask 作为目标领域聚合继续扩展。
- `externaltasks` 包、Repository、Entry、表、生成代码和兼容测试已经删除；Executor
  不再创建、完成或取消重复等待记录。
- PAUSE 的核心测试应改为通过 `ExecutionService.resume(...)` 恢复确定
  `executionId + taskRunId`。
- 面向用户的审批待办、受派和权限由审批能力的集成 UC 验证，Flow Core 的 resume
  测试只验证运行生命周期与隔离。
- ADR 0002、ADR 0009、ADR 0011 中以 ExternalTask 为恢复入口或目标持久化边界的
  内容由本决策替代。
- Flow Core 的运行状态词汇和迁移规则由 ADR 0017 的统一 State 约束；PAUSE 使用
  通用 WAITING 类型，但不把审批待审、通过或驳回等外部业务状态带入 Core。
- PAUSE、WAITING、Resume 和 BranchTask 的后续选择由本 ADR、ADR 0017 与
  ADR 0024 共同约束；当前决策链见 [`README.md`](README.md)。
