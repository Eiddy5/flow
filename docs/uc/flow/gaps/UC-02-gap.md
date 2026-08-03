# UC-02 项目能力缺口

## 对应 UC

- UC：`UC-02 用户启动、查询与取消 Flow`
- 文档：[UC-02 用户启动、查询与取消 Flow.md](../UC-02%20用户启动、查询与取消%20Flow.md)
- 涉及场景：S7 完成与取消竞争

## UC 目的

保护 Execution 的版本绑定、实例隔离和取消边界，确保同一运行实例上的 complete
与 cancel 竞争不会形成互相矛盾的终态。

## 目标业务与安全场景

当外部完成和取消几乎同时作用于同一个 PAUSE Execution 时，只允许一个修改基于
最新状态提交，另一个必须看到冲突或终态并停止。

## 项目现有能力

- `Execution` 保存 lockVersion，修改已有实例前由 `beginModification` 增加一次。
- 领域方法拒绝修改终态 Execution 和不符合当前动作前置状态的 TaskRun。
- 取消会委托 Worker 取消等待触发器，再取消运行中的 TaskRun。
- companyId 参与 Execution、Flow 和 ExternalTask 查询。

## 原不满足项与证据（已修复）

- `ExecutionRepository.save` 不接收 expected lockVersion。
- `InMemoryExecutionRepository.save` 对聚合副本执行无条件 `put`，没有
  compare-and-set。
- `InMemoryExternalTaskRepository.save` 同样无条件覆盖，ExternalTask.lockVersion
  没有参与保存冲突判断。
- complete 与 cancel 分别读取副本后可以各自在本地通过领域校验，Repository
  无法阻止后保存的旧快照覆盖先提交结果。
- `Uc02ExecutionLifecycleTest` 已迁移到公开 Service 对应包并覆盖 S1～S6，但尚未
  覆盖需要并发协议的 S7。

## 风险

- Execution 已取消但 ExternalTask 显示完成，或反之。
- 竞争操作可能重复推进后续 Task，或覆盖已经提交的终态。
- lockVersion 字段存在但不参与存储冲突判断，容易产生虚假的并发安全感。

## 已满足的最小能力

- Repository 基于加载时版本原子保存 Execution 和 ExternalTask。
- complete 与 cancel 共享可回滚的命令事务。
- 冲突必须以稳定异常或结果暴露，不能静默覆盖。
- 对应主测试类覆盖两种调用顺序和真实并发竞争。

## 当前状态与后续角色

- 状态：`RESOLVED`
- 处理结论：内存模式的整个 CommandExecutor 写操作使用公平写锁串行执行，并在
  异常时恢复 Execution、ExternalTask 和 Flow Repository 快照；竞争失败方读取到
  最新终态后由领域规则拒绝。
- 复验证据：
  `Uc02ExecutionLifecycleTest#s7CompleteAndCancelRaceCommitsOneConsistentTerminalState`。
- UC Agent：保留竞争场景和一致性要求。
- 开发角色：已实现跨聚合内存事务与竞争协议。
- Test Agent：已执行 S7 并记录最终三个领域对象的一致状态，复验结果见
  [UC-02-2026-07-27-1948.md](../../../test-reports/flow/UC-02-2026-07-27-1948.md)。
