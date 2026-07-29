# UC-05 项目能力缺口

## 对应 UC

- UC：`UC-05 用户处理并行外派任务`
- 文档：[UC-05 用户处理并行外派任务.md](../UC-05%20用户处理并行外派任务.md)
- 涉及场景：全部场景

## UC 目的

验证单个 Execution 内并行分支的身份、隔离、完成顺序和唯一汇合，防止重新引入
废弃的 Child Execution 模型或产生提前、重复和部分汇合。

## 目标业务与安全场景

两个独立检查可以同时等待外部结果，任意一个先完成都不能提前汇合；只有两者全部
完成后才能创建一次汇合 TaskRun，并继续执行后续 Task。

## 项目现有能力

- `Execution` 代表一次完整启动，并保存有序 TaskRun。
- `TaskRun.parentId` 已建模为真实父 TaskRun id。
- Task 定义可以递归保存子 tasks，并把扩展 YAML 字段保存到 properties。
- PAUSE 和 ExternalTask 已能表达单个外部等待分支。
- ADR 0001、0002 已明确未来 Parallel 不得使用 Child Execution。

## 原不满足项与证据（已修复）

- `Execution.createTaskRun` 在已有 CREATED/RUNNING TaskRun 时直接拒绝新增运行记录。
- `Execution.currentTaskRun` 和当前状态机按单一当前 TaskRun 设计。
- `ExecutorService.handleNext` 只遍历 `flow.tasks(version)` 顶层列表，不调度
  `Task.tasks()`。
- `Task` 没有结构化 dependOn 字段或依赖到达状态；当前只能把未知 YAML 字段保留
  在 properties。
- 没有分叉、分支完成聚合、唯一汇合和部分取消算法。
- 没有 `Uc05ParallelTaskJoinTest`。

## 风险

- 直接实现时可能重新创建 Child Execution，违反已接受 ADR。
- 缺少唯一汇合约束会导致后续业务执行多次。
- 部分分支完成或取消时可能留下无法恢复的混合状态。
- 未确认 dependOn 定义与多并发 TaskRun 语义前编写代码，容易固化错误模型。

## 已满足的最小能力

- 确认并记录单 Execution 内并行 TaskRun、parentId、dependOn 和汇合的状态协议。
- 支持多个合法并行运行 TaskRun，同时保持分支身份和输出隔离。
- 原子记录依赖到达并只创建一次汇合 TaskRun。
- 支持并行分支的取消、重复完成防护和可查询稳定态。

## 当前状态与后续角色

- 状态：`RESOLVED`
- 处理结论：ADR 0006 确认单 Execution 多活动 TaskRun、真实 parentId、
  结构化 dependOn 和基于事实重建的唯一汇合协议；调度器与取消链路已实现。
- 复验证据：`Uc05ParallelTaskJoinTest` S1～S6。
- UC Agent：保留目标 UC，不使用 Main/Child Execution、USER_TASK 或 BLOCKED
  Task 类型替代当前模型。
- 架构角色：已通过 ADR 0006 确认最小并行与 dependOn 协议。
- 开发角色：已实现调度、恢复和取消能力。
- Test Agent：已创建唯一测试类并执行全部场景，结果见
  [UC-05-2026-07-27-1948.md](../../../test-reports/flow/UC-05-2026-07-27-1948.md)。
