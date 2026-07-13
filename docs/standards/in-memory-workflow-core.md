# 内存工作流核心规范

## 适用范围

本规范适用于工作流核心 MVP 的内存实现。内存实现用于验证 Flow 定义、Process 运行、Executor 推进、Activity 记录和人工 Task complete，不提供应用重启后的数据恢复能力。

## Repository 边界

- 定义层和运行层只依赖 Repository 接口。
- 内存实现统一放在 `org.cses.flow.infrastructure.memory`。
- Repository 不向调用方暴露内部 Map 或可修改集合。
- 每个测试场景创建独立 Repository 实例，禁止测试间共享静态状态。
- 后续数据库实现必须替换 Repository 实现，而不是修改 FlowEngine 的运行语义。

## Flow 定义

- draft Flow 可以进入部署流程。
- deployed Flow 的 nodes、edges 和 config 对外不可修改。
- Node config 中的嵌套 Map 和 List 必须执行不可变复制。
- Flow 加载后一次性装配 Node.incoming、Node.outgoing、Edge.source 和 Edge.target。
- Process 启动后绑定最终选择的 flowId 和 flowVersion。
- 已启动 Process 恢复时按照 process.flowId 精确读取 Flow。

## 运行状态

- Process 创建和管理自己的 Executor。
- Executor 只保存运行路径状态，不执行节点行为。
- 每次进入 Node 都创建新的 Activity。
- 人工 WAIT 期间 Process 为 running、Executor 为 waiting、Activity 为 running、Task 为 created 或 claimed。
- FlowContext 和 ExecutionQueue 只在一次 start 或 complete 调用中存在。
- 调用结束时 ExecutionQueue 必须为空，运行状态由内存 Repository 保存。

## 执行边界

- FlowEngine 负责建立 FlowContext 和 Command。
- CommandExecutor 把 Command 包装为第一个 CommandOperation。
- ExecutionRunner 只循环取出并执行 ExecutionOperation。
- ActivityBehavior 决定节点立即完成还是进入等待。
- 外部 complete 只能提交 Task 结果，不能指定目标 Node 或 Edge。

## 并发限制

- 当前 MVP 在单 JVM 中运行。
- Task complete 对同一个 Task 使用对象级同步，避免同一进程内并发重复完成。
- idempotencyKey 用于识别同一次 complete 重试。
- 跨 JVM 并发、分布式锁和持久化事务不属于当前实现范围。

## 验证命令

```bash
./gradlew :server:test --tests '*FlowStartAndNodeProgressionTest'
./gradlew test
./gradlew build
```

