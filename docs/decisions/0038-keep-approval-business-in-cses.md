# ADR 0038：审批业务由 CSES 持有，Flow 只负责编排

## 状态

Accepted（Flow Gradle 模块形态由
[`ADR 0042`](0042-split-core-from-http-server.md) 修订；Approval 与 Execution、Todo 与
Pause TaskRun 的绑定关系由
[`ADR 0050`](0050-bind-durable-external-business-to-exact-executions.md) 修订）

## 决策

Approval、模板、Policy、共享 Todo、Decision、权限、数据库和可靠 Operation
全部属于 CSES。Flow 只拥有流程定义、Execution、TaskRun、Pause 和 Resume
状态机，不建立 Approval Gradle 模块、审批表或审批接口，也不反向依赖 CSES。

一个 CSES Approval 绑定一条完整 Flow Execution；该 Execution 每次到达配置了
审批 Policy 的 Pause TaskRun 时，CSES 为该精确节点 occurrence 创建一条共享 Todo。
例如请假中的 HR 后 Boss 由两个串行 Pause 表达，在同一 Approval 下形成两条 Todo。
CSES 通过 Flow 公开的
`ExecutionService.resume(session, executionId, taskRunId, outputs)` 恢复精确 Pause；
它不能提供下一 Task 或路由目标。

## 后果

- Flow 仓库保持浅层 `gen + core + server`，不为 Approval 新增模块。
- Approval 的事务和 Flow 的事务相互独立；CSES 使用持久 Operation 至少一次投递，
  Flow 对同一稳定 Execution 身份的创建，以及相同 TaskRun 和相同 outputs 的重复恢复
  必须保持幂等。
- Flow 的 `PAUSE`、`Execution Resume` 和 `External Business Capability` 仍保持
  通用，不出现审批人、表单或审批动作。
