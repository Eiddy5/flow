# 内存工作流核心规范

## 适用范围

本规范适用于工作流核心第一阶段的纯内存实现。它用于验证 Flow 定义、Process 运行、Executor 推进、Activity 记录、人工 Task 恢复和事务边界，不提供应用重启后的数据恢复能力。

核心架构以 `docs/decisions/2026-07-14-workflow-core-architecture-v2.md` 为准。

## 定义边界

- `FlowRepository` 只服务于 Flow 草稿、部署和版本生命周期。
- `DefinitionSession` 是运行主链读取 Flow 的唯一入口。
- `resolveStartFlow(flowId)` 根据传入 Flow 的 key 返回最新 `DEPLOYED` 版本。
- `loadBoundFlow(flowId)` 按 Process 已绑定的 flowId 精确加载原版本。
- Flow 加载后一次装配 `Node.incoming`、`Node.outgoing`、`Edge.source` 和 `Edge.target`。
- Process 运行不能修改 Flow、Node 或 Edge。

## 运行会话

- 运行主链只依赖 `RuntimeSession`，不依赖 Process、Activity 或 Task Repository。
- `RuntimeSession` 的 `insert` 和 `update` 在对应 Operation 中立即执行，不能延迟到队列结束后批量猜测变更。
- 同一个 `CommandContext` 的 DefinitionSession 和 RuntimeSession 必须绑定同一个 EngineTransaction。
- `RuntimeQuery` 只用于调用结束后的查询和测试，不参与 `FlowEngine.start` 或 `TaskService.complete` 主链。
- 后续数据库实现替换 Session、Transaction 和 Query Adapter，不修改 Command、Operation 或 Behavior 语义。

## 内存事务

- `InMemoryEngineTransaction` 打开时创建已提交运行态的隔离快照。
- RuntimeSession 读写当前事务快照。
- `commit` 原子发布快照，`rollback` 丢弃快照。
- 每个外部 `start` 或 `complete` 调用最多提交一次或回滚一次。
- 每个测试场景创建独立的 InMemoryRuntimeState，禁止静态共享运行数据。
- 当前内存 Adapter 用于单进程功能验证，不承诺跨 JVM 持久化或并发事务合并。

## 执行边界

- FlowEngine 只把外部动作转换为 Command。
- CommandExecutor 创建 CommandContext，将 Command 包装为第一个 CommandOperation，并负责最终提交、回滚和关闭。
- ExecutionRunner 只按 FIFO 消费 CommandContext 中的 ExecutionQueue。
- FlowOperation 只能通过 OperationContext 使用 FlowContext、RuntimeSession、OperationScheduler 和只读 EngineConfiguration。
- ActivityBehavior 只能返回 `Completed` 或 `Waiting`，不能写 Session、移动 Executor 或安排 Operation。
- WAIT 不安排后续 Operation；队列为空表示本次同步推进达到稳定状态，不代表 Process 必然完成。

## 运行状态

- Process 创建并管理自己的 Executor。
- 每次进入 Node 都创建新的 Activity。
- 人工等待时 Process 为 `RUNNING`、Executor 为 `WAITING`、Activity 为 `RUNNING`、Task 为 `CREATED` 或 `CLAIMED`。
- 外部 complete 只提交 `taskId`、`result`、`operatorId` 和 `idempotencyKey`，不能指定 Process、Executor、Activity、目标 Node 或 Edge。
- 同一 idempotencyKey 的重复 complete 返回已完成 Process，不重复创建 Activity 或 Task。
- 节点执行异常必须回滚本次调用产生的全部 Process、Activity 和 Task 变更。

## 第一阶段路由限制

- START、ACTION、WAIT 等非 END 节点必须恰好有一条 outgoing Edge。
- END 节点不能有 outgoing Edge。
- 第一阶段不执行 Edge condition，不创建并行 Executor，也不处理分支合并。

## 验证命令

```bash
./gradlew :server:test --tests '*FlowStartAndNodeProgressionTest'
./gradlew :server:test --tests '*CommandExecutionLifecycleTest'
./gradlew :server:test --tests '*InMemoryEngineSessionTest'
./gradlew test
./gradlew build
```
