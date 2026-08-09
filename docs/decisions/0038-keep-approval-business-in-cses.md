# ADR 0038：审批业务由 CSES 持有，Flow 只负责编排

## 状态

Accepted（Flow Gradle 模块形态由
[`ADR 0042`](0042-split-core-from-http-server.md) 修订）

## 决策

Approval 聚合、审批人、个人 WorkItem、Decision、权限、数据库和可靠 Resume
outbox 全部属于 CSES。Flow 只拥有流程定义、Execution、TaskRun、Pause 和 Resume
状态机，不建立 Approval Gradle 模块、审批表或审批接口，也不反向依赖 CSES。

一个实际 Pause TaskRun 对应一个 CSES Approval。请假中的 HR 后 Boss 由 Flow 的
两个串行 Pause 表达，并在 CSES 中形成两个独立 Approval。CSES 通过 Flow 公开的
`ExecutionService.resume(session, executionId, taskRunId, outputs)` 恢复精确 Pause；
它不能提供下一 Task 或路由目标。

## 后果

- Flow 仓库保持浅层 `gen + core + server`，不为 Approval 新增模块。
- Approval 的事务和 Flow 的事务相互独立；CSES 使用 outbox 至少一次投递，Flow
  对相同 TaskRun 和相同 outputs 的重复恢复必须保持幂等。
- Flow 的 `PAUSE`、`Execution Resume` 和 `External Business Capability` 仍保持
  通用，不出现审批人、表单或审批动作。
