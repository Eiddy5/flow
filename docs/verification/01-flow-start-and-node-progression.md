# 流程启动与自动、人工节点推进验证

## 验证标识

```text
VER-FLOW-001
```

## 前置条件

1. 每个场景开始前清空所有内存 Repository。
2. START、ACTION、WAIT 和 END 已注册对应的 ActivityBehavior。
3. ACTION 使用无副作用的 `noop` 测试行为，执行后立即返回 completed。
4. WAIT 使用 `config.completion.mode = manual`，进入后创建 manual Task。
5. 每个 Flow 都必须先通过 FlowValidator 校验，再部署为 deployed。
6. 验证过程不依赖数据库、消息中间件或前端。

## 验证场景

本验证用于确认已部署 Flow 启动后，运行核心能够根据自动节点和人工节点的不同组合，把流程推进到正确的稳定状态。

### 场景 S1：全部是自动节点

Flow 结构：

```text
START -> ACTION_A -> ACTION_B -> END
```

该场景验证只调用一次 `FlowEngine.start(flowId)` 时，流程是否能够在同一次运行调用中自动执行所有节点并完成 Process。

### 场景 S2：只有一个人工节点

Flow 结构：

```text
START -> WAIT_A -> END
```

该场景验证流程是否会在 WAIT_A 创建 Task 并稳定等待，以及外部 complete 后是否能够恢复并运行到 END。

### 场景 S3：自动节点和人工节点混合

Flow 结构：

```text
START -> ACTION_A -> WAIT_A -> ACTION_B -> END
```

该场景验证自动节点能否在人工节点前正常运行，人工节点能否暂停流程，以及 complete 后剩余自动节点能否继续运行。

### 场景 S4：多个不同人工节点依次等待

Flow 结构：

```text
START -> WAIT_A -> ACTION_A -> WAIT_B -> END
```

该场景验证一个 Process 能否先后创建两个相互独立的 Task，并在每次 complete 后推进到下一个稳定状态。

## 验证方式

### 场景 S1 的验证方式

1. 创建 `START -> ACTION_A -> ACTION_B -> END` Flow。
2. ACTION_A 和 ACTION_B 的 `config.executor` 都设置为 `noop`。
3. 部署 Flow，记录 deployed `flowId` 和 `version`。
4. 调用 `FlowEngine.start(flowId)`。
5. 根据返回的 processId 查询 Process。
6. 查询该 Process 的全部 Executor、Activity 和 Task。
7. 记录 Activity 的 nodeId、state、startedAt 和 endedAt。
8. 检查本次 FlowContext 的 ExecutionQueue 是否已经清空。

### 场景 S2 的验证方式

1. 创建并部署 `START -> WAIT_A -> END` Flow。
2. WAIT_A 设置 `config.completion.mode = manual`。
3. 调用 `FlowEngine.start(flowId)`。
4. 查询第一次稳定状态下的 Process、Executor、Activity 和 Task。
5. 记录 WAIT_A 创建的 taskId 和 activityId。
6. 使用 taskId、operatorId、result 和唯一 idempotencyKey 调用 `TaskService.complete(...)`。
7. 查询 complete 后的 Process、Executor、Activity 和 Task。
8. 再次查询全部 Activity，确认实际经过的 Node 顺序。
9. 检查 start 和 complete 两次运行调用结束后的 ExecutionQueue。

### 场景 S3 的验证方式

1. 创建并部署 `START -> ACTION_A -> WAIT_A -> ACTION_B -> END` Flow。
2. ACTION_A 和 ACTION_B 使用 `noop`，WAIT_A 使用 manual completion。
3. 调用 `FlowEngine.start(flowId)`。
4. 查询第一次稳定状态，并记录已经产生的 Activity 和 Task。
5. 完成 WAIT_A 创建的 Task。
6. 查询第二次稳定状态。
7. 按 startedAt 检查 Activity 的执行顺序。
8. 检查 ACTION_A 和 ACTION_B 的执行次数。
9. 检查 complete 返回时 ExecutionQueue 是否为空。

### 场景 S4 的验证方式

1. 创建并部署 `START -> WAIT_A -> ACTION_A -> WAIT_B -> END` Flow。
2. WAIT_A 和 WAIT_B 都使用 manual completion，ACTION_A 使用 `noop`。
3. 启动 Process，查询 WAIT_A 创建的 Task_A。
4. complete Task_A，随后查询 Process 的第二个稳定状态。
5. 确认流程到达 WAIT_B，并查询 WAIT_B 创建的 Task_B。
6. 比较 Task_A 和 Task_B 的 id、activityId 和 nodeId。
7. complete Task_B，随后查询最终状态。
8. 查询完整 Activity 历史和 Task 历史。
9. 检查每次调用结束后的 ExecutionQueue。

## 通过规范

### 场景 S1 的通过规范

1. `PASS-S1-01`：FlowEngine 根据传入 flowId 找到 key，并启动该 key 下最新的 deployed Flow。
2. `PASS-S1-02`：Process 绑定实际启动的 flowId 和 flowVersion。
3. `PASS-S1-03`：根 Executor 由 Process 创建，FlowEngine 不直接创建 Executor。
4. `PASS-S1-04`：START、ACTION_A、ACTION_B、END 各产生一条 Activity。
5. `PASS-S1-05`：Activity 顺序为 START、ACTION_A、ACTION_B、END，状态全部为 completed。
6. `PASS-S1-06`：ACTION_A 和 ACTION_B 各执行一次。
7. `PASS-S1-07`：不产生 Task。
8. `PASS-S1-08`：根 Executor 和 Process 最终都为 completed。
9. `PASS-S1-09`：start 返回时 ExecutionQueue 为空，不需要额外调用推进方法。

### 场景 S2 的通过规范

1. `PASS-S2-01`：start 返回后 Process 为 running。
2. `PASS-S2-02`：根 Executor 当前节点为 WAIT_A，状态为 waiting。
3. `PASS-S2-03`：START Activity 为 completed，WAIT_A Activity 为 running，END Activity 尚不存在。
4. `PASS-S2-04`：WAIT_A 只创建一个 state 为 created 的 manual Task。
5. `PASS-S2-05`：Task 的 processId、executorId、activityId 和 nodeId 关联正确。
6. `PASS-S2-06`：complete 前流程不能越过 WAIT_A。
7. `PASS-S2-07`：complete 后 Task 和 WAIT_A Activity 都为 completed。
8. `PASS-S2-08`：complete 后 Executor 自动进入 END 并完成，END Activity 为 completed。
9. `PASS-S2-09`：Process 最终为 completed。
10. `PASS-S2-10`：外部 complete 不接受 targetNodeId 或 targetEdgeId。
11. `PASS-S2-11`：start 和 complete 返回时各自的 ExecutionQueue 都为空。

### 场景 S3 的通过规范

1. `PASS-S3-01`：第一次稳定时 START 和 ACTION_A Activity 为 completed。
2. `PASS-S3-02`：第一次稳定时 WAIT_A Activity 为 running，Executor 为 waiting。
3. `PASS-S3-03`：第一次稳定时 ACTION_B 和 END Activity 尚不存在。
4. `PASS-S3-04`：WAIT_A 只创建一个有效 Task。
5. `PASS-S3-05`：complete 后 WAIT_A Task 和 Activity 为 completed。
6. `PASS-S3-06`：ACTION_B 在 complete 发起的同一次运行调用中自动执行一次。
7. `PASS-S3-07`：Activity 顺序为 START、ACTION_A、WAIT_A、ACTION_B、END。
8. `PASS-S3-08`：complete 返回时 Executor 和 Process 都为 completed。
9. `PASS-S3-09`：complete 后不需要额外调用推进方法。

### 场景 S4 的通过规范

1. `PASS-S4-01`：第一次启动后 Executor 等待在 WAIT_A，只存在 Task_A。
2. `PASS-S4-02`：complete Task_A 后 Task_A 和 WAIT_A Activity 为 completed。
3. `PASS-S4-03`：complete Task_A 后 ACTION_A 自动执行一次。
4. `PASS-S4-04`：ACTION_A 完成后 Executor 停在 WAIT_B，Process 仍为 running。
5. `PASS-S4-05`：WAIT_B Activity 为 running，并创建 Task_B。
6. `PASS-S4-06`：Task_A 和 Task_B 拥有不同 id、activityId 和 nodeId。
7. `PASS-S4-07`：complete Task_A 不能同时完成 Task_B。
8. `PASS-S4-08`：complete Task_B 后 WAIT_B Activity 和 Task_B 为 completed。
9. `PASS-S4-09`：complete Task_B 后 Executor 到达 END，Process 为 completed。
10. `PASS-S4-10`：每次 start 或 complete 返回时 ExecutionQueue 都为空。

## 反例

### 反例 CE-001：人工节点 completion 配置路径不一致

- 发现日期：2026-07-13。
- 发现阶段：编写本验证文档时进行核心设计一致性检查。
- 对应场景：S2、S3、S4。
- 验证操作：比较核心设计中的人工节点说明、Node JSON 示例和本验证使用的 WAIT 配置。
- 实际问题：核心设计的人工节点运行说明曾使用 `config.mode = manual`，Node JSON 示例使用 `config.completion.mode = manual`，同一含义存在两个配置路径。
- 预期结果：人工节点必须只有一个明确且统一的配置路径。
- 影响：如果实现分别读取不同路径，同一个 WAIT Node 可能在校验阶段被认为合法，但在运行时无法识别为人工节点，或者无法创建 Task。
- 处理结论：统一使用 `config.completion.mode = manual`，并同步修正核心设计和验证文档。
- 复验结果：核心设计的人工节点说明、Node JSON 示例和本验证文档现已使用相同配置路径。

