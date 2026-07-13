# 工作流测试完整快照输出设计

## 目标

在执行 `FlowStartAndNodeProgressionTest` 时，将每个场景稳定阶段的 Flow、Process、Executor、Activity、Task 和 ExecutionQueue 数据直接输出到测试控制台，便于人工观察流程推进结果。

快照只用于测试验收，不进入生产运行核心，不改变工作流状态和推进逻辑。

## 实现位置

在 `FlowStartAndNodeProgressionTest` 的 `Fixture` 中增加测试专用快照打印能力。Fixture 从现有内存 Repository 和最近一次 FlowContext 读取数据，并负责统一格式化。

`server/build.gradle` 为 Test 任务开启标准输出展示，使 `System.out` 内容在普通 Gradle 测试命令中可见。

## 输出内容

每份快照包含以下部分：

1. 场景编号和阶段名称。
2. Flow：id、key、version、state、Node 列表、Edge 列表。
3. Process：id、flowId、flowKey、flowVersion、state、startedAt、endedAt。
4. Executor：id、processId、parentId、currentNodeId、state、createdAt、updatedAt。
5. Activity：id、nodeId、nodeType、state、startedAt、endedAt。
6. Task：id、processId、executorId、activityId、nodeId、type、state、createdAt、completedAt、completedBy、result。
7. ExecutionQueue：是否为空。

没有 Activity 或 Task 时打印空列表，不省略对应部分。

## 打印时机

- S1：start 返回后。
- S2：start 返回后、Task complete 返回后。
- S3：start 返回后、Task complete 返回后。
- S4：start 返回后、Task_A complete 返回后、Task_B complete 返回后。

所有快照都在 API 调用返回后打印，代表该次运行调用已经到达稳定状态。

## 输出约束

- 使用固定分区名称和字段顺序，便于不同阶段对比。
- Activity 和 Task 使用 Repository 的插入顺序输出，直观体现执行历史。
- 不输出对象默认 `toString()`，避免内存地址和不稳定格式。
- 不修改断言，不允许打印逻辑影响验证结论。
- 不引入日志框架或 JSON 序列化依赖。

## 验证方式

运行：

```bash
./gradlew :server:test --tests '*FlowStartAndNodeProgressionTest' --rerun-tasks --console=plain
```

通过条件：

1. 控制台按预定时机出现 7 份完整快照。
2. 每份快照包含全部七个数据部分。
3. 快照状态与对应测试断言一致。
4. 四个场景测试全部通过。
5. `./gradlew test` 全量测试通过。
