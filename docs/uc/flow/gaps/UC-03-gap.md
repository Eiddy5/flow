# UC-03 项目能力缺口

## 对应 UC

- UC：`UC-03 用户运行自动流程`
- 文档：[UC-03 用户运行自动流程.md](../UC-03%20用户运行自动流程.md)
- 涉及历史场景：Worker 派发抛出未处理异常

## UC 目的

保护 AUTO 顺序调度、可恢复运行历史和命令事务，确保 Worker 异常不会留下本次
执行产生的部分 Execution 或 TaskRun。

## 目标业务与安全场景

ExecutionRunner 会在 Worker 调用前中间保存 Execution。若 Worker 抛出异常，
数据库实现应回滚这些中间保存；测试使用的内存实现也必须提供等价可观察语义，
否则无法验证框架承诺。

## 项目现有能力

- `CommandExecutor` 通过 JOOQ `runReturn` 建立命令事务边界。
- `ExecutorService.handleNext/onNexts` 分离计划与应用，ExecutionRunner 严格
  执行保存、派发、应用结果和继续调度。
- ExecutorService 按稳定 Task id 的值比较重建可运行集合，能够适配持久化后
  对象引用变化。
- Worker 明确返回 TERMINATED 并携带 error 时，领域状态可以正常提交为
  TERMINATED。

## 原不满足项与证据（已修复）

- 改造前的 `ExecutionHandler` 在调用 Worker 前通过
  `ExecutionRepository.save` 中间保存运行状态；当前职责已迁入
  ExecutionRunner。
- `InMemoryExecutionRepository` 使用独立 `ConcurrentHashMap`，不感知 JOOQ
  事务提交或回滚。
- Worker 抛出异常时，JOOQ 事务可以回滚数据库操作，但已写入内存 Map 的聚合副本
  不会自动恢复。
- `Uc03AutomaticTaskFlowTest` 已覆盖 S1、S2，但 S3～S6 和异常回滚尚未覆盖。

## 风险

- 失败命令可能留下调用方认为已经回滚的 Execution 或 ACTIVE TaskRun。
- 后续查询或重试会基于幽灵中间状态继续，导致重复、卡死或错误终态。
- 内存测试通过或失败的事务语义可能与未来 PostgreSQL Adapter 不一致。

## 已满足的最小能力

- 内存 Adapter 在命令失败时提供与事务回滚等价的状态恢复，或使用真正参与事务的
  测试 Repository 验证该场景。
- 保留 Worker 调用前可恢复中间保存，同时确保只有命令提交后才对外可见。
- 在现有 UC-03 主测试类中补齐 S3～S6 和全部 PASS 映射。

## 当前状态与后续角色

- 状态：`RESOLVED`
- 2026-08-12 修订：Execution 启动已改为 Queue 异步受理。当前 UC-03 S3 要求首次
  启动中的意外任务异常形成可查询的 FAILED Execution/TaskRun；同步恢复中的异常
  仍沿用事务回滚语义。本文件以下内容只保留为旧实现的历史差距记录。
- 处理结论：内存事务在命令开始时保存全部 Repository 隔离快照，Worker 未处理
  异常时逆序恢复；中间保存只在命令成功返回后对其他命令可见。
- 复验证据：
  `Uc03AutomaticTaskFlowTest#s4UnhandledWorkerFailureRollsBackCreatedExecution`。
- UC Agent：已移除过期的 String 引用比较缺口，只保留有当前代码证据的事务缺口。
- 开发角色：已实现内存模式事务一致性策略。
- Test Agent：已执行 S3～S6；本轮范围为内存 Adapter，复验结果见
  [UC-03-2026-07-27-1948.md](../../../test-reports/flow/UC-03-2026-07-27-1948.md)。
