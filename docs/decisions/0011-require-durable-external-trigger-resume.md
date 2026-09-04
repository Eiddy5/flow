# ADR 0011：外部触发必须跨 Server 生命周期持久化恢复

## 状态

Accepted（恢复身份与公开入口由 ADR 0016 修订）

## 背景

PAUSE 会把 Execution 和对应 TaskRun 置为 WAITING。外部审批、人工操作、服务
回调或定时信号可能在原 Server 结束之后才产生结果，因此恢复不能依赖启动
Execution 时的 ApplicationContext、Service 实例或内存对象。

早期验证通过 ExternalTask id 和 `ExternalTaskService.complete(...)` 恢复流程。
ADR 0016 已确认 ExternalTask 不再是目标等待聚合或公开入口；持久化恢复应使用
Execution 与 TaskRun 自身身份，并通过 `ExecutionService.resume(...)` 进入运行
生命周期。

## 备选方案

### 方案一：只在原 ApplicationContext 内恢复

实现简单，但只能证明进程内续跑，不能证明 PostgreSQL 状态足以在重启后恢复。

### 方案二：外部系统保存 Flow 内存对象

外部调用方持有原 Execution、TaskRun 或 Flow 对象。该方案跨进程不可用，也泄漏
Flow Core 内部状态。

### 方案三：新 Server 通过持久化身份调用 ExecutionService

外部能力只保存 companyId、executionId 和 taskRunId。新 Server 收到结果后调用
公开 `ExecutionService.resume(...)`，由 Repository 从 PostgreSQL 重建确定
Execution、TaskRun 和绑定的 Flow Reversion。

## 决策

采用方案三。

- 外部触发不得复用启动 Server 的 Service、Repository、领域对象或内存上下文。
- 阶段之间只传递外部能力持有的 companyId、executionId、taskRunId 和业务结果。
- 新 Server 通过 `ExecutionService.resume(...)` 提交结果。
- `ExecutionCommandEventHandler` 消费 `Resume` 后在一个 PostgreSQL 领取事务中校验
  并投递 `ExecutorEvent`；随后 `ExecutorEventMessageHandler` 在自己的 PostgreSQL Event
  事务中：
  1. 按 companyId 和 executionId 加载 Execution。
  2. 加载 Execution 永久绑定的 Flow Reversion。
  3. 定位 taskRunId 并校验它是 WAITING PAUSE TaskRun。
  4. 校验 outputs，交给内部 Event 完成原 TaskRun。
  5. 通过 `ExecutorContext` 继续推进同一 Execution 到下一稳定态或终态。
- `Resume` 消息只保存 companyId、actorId、executionId、taskRunId 和业务结果；不保存
  Flow、Session 设备信息或内存对象。
- 恢复不能重新执行原 PAUSE Worker，也不能创建重复 PAUSE TaskRun。
- 正常线路最终为 COMPLETED；取消和失败场景保持 TERMINATED，不伪装为正常
  完成，明确失败原因由 TaskRun error 保留。
- 测试应关闭原 ApplicationContext，再使用新 ApplicationContext 和公开
  ExecutionService 完成恢复。
- 外部传输协议、认证、幂等键和消息可靠性不由本 ADR 规定。

## 理由

Execution 与 TaskRun 已经完整持久化暂停运行事实。使用它们的稳定身份即可证明
跨 Server 恢复，同时保持外部调用只经过公开 Service、命令事务和 Core 状态机。
不需要为等待再建立重复聚合或依赖原进程状态。

## 后果

- `executions`、`task_runs` 和 Flow 定义必须完整支持持久化重建。
- 外部能力必须保存准确的 executionId 和 taskRunId；不能只保存页面对象或内存
  回调。
- 恢复测试应通过 TaskRun 历史复核等待点和最终结果。
- 跨 Server 测试直接调用 `ExecutionService.resume(...)`，不再创建或查询额外
  等待对象。
- 新增可外部恢复的 Task 类型时必须明确是否复用 Execution Resume；不得复制
  Execution 加载、校验和推进逻辑。
