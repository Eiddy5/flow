# 代码评审报告

- 日期：2026-07-14
- 范围：当前工作区全部 staged + unstaged 变更（V2 工作流核心重写）
- 目标验证：`docs/verification/01-flow-start-and-node-progression.md`（VER-FLOW-001）
- 审查文件数：97
- 审查角色：独立 Reviewer，仅审查和验收；未修改生产代码、测试代码或既有验证规范

## 验证发现

### P0

无。

### P1

无。

### P2

无。

### P3

1. `docs/flowable-internal-execution-sequence.md:861` — staged 版本在文件末尾多出一个空行。
   - 对应场景/PASS：不对应 VER-FLOW-001；属于本次“全部 staged + unstaged 变更”的文档格式验收。
   - 产生条件：对待提交内容执行 `git diff --cached --check`。
   - 实际影响：命令以 exit code 2 失败；若提交门禁包含 `git diff --check`，会阻断提交或 CI 的 whitespace 检查。
   - 可复现证据：`docs/flowable-internal-execution-sequence.md:861: new blank line at EOF.`
   - 建议修改：提交前删除末尾额外空行；Reviewer 按职责不直接修改该文档。

除上述文档格式问题外，未发现违反 VER-FLOW-001 通过规范或 V2 核心架构边界的实现问题。

## 场景和 PASS 追踪结果

命令缩写：

- `C1`：`./gradlew :server:test --tests '*FlowStartAndNodeProgressionTest' --rerun-tasks`
- `C2`：`./gradlew :server:test --tests '*WorkflowArchitectureBoundaryTest' --rerun-tasks`
- `C3`：`./gradlew :server:test --tests '*CommandExecutionLifecycleTest' --rerun-tasks`
- `C4`：`./gradlew :server:test --tests '*InMemoryEngineSessionTest' --rerun-tasks`
- `C5`：`./gradlew test --rerun-tasks`
- `C6`：`./gradlew build --rerun-tasks`

### S1：全部是自动节点

验证方法：部署同 key 的两个版本，使用旧版本 id 调用 `FlowEngine.start`，查询 Process、根 Executor、全部 Activity/Task，并检查返回时队列。

| 通过规范 | 相关生产代码 | 相关测试方法/断言 | 执行命令 | 实际证据 | 结论 |
| --- | --- | --- | --- | --- | --- |
| PASS-S1-01 | `InMemoryDefinitionSession.resolveStartFlow` 17-22；`StartFlowCommand.execute` 21-22 | `shouldCompleteFlowContainingOnlyAutomaticNodes` 59-83 | C1、C5、C6 | 传入 versionOne.id，返回 Process 绑定 versionTwo.id；快照 version=2 | PASS |
| PASS-S1-02 | `Process` 构造器保存 Flow id/key/version；`StartFlowCommand` 23-26 | 同测试 83-84 | C1、C5、C6 | `process.flowId == versionTwo.id`，`flowVersion == versionTwo.version` | PASS |
| PASS-S1-03 | `Process.createRootExecutor`；`StartFlowCommand` 27-29 | `WorkflowArchitectureBoundaryTest#processOwnsThePublicRootExecutorCreationBoundary` 53-87 | C2、C5、C6 | Executor 构造器非 public/protected；FlowEngine API 不依赖 Executor；根 Executor 归入 Process | PASS |
| PASS-S1-04 | `EnterNodeOperation` 33-36；`LeaveNodeOperation` 34-35 | 自动节点测试 88-92 | C1、C5、C6 | Activity nodeId 精确为 start/action-a/action-b/end，共四条 | PASS |
| PASS-S1-05 | `TraverseEdgeOperation` 22-28；`LeaveNodeOperation` 34-50 | 自动节点测试 88-92 | C1、C5、C6 | Activity 顺序精确匹配，全部 COMPLETED；快照含 endedAt | PASS |
| PASS-S1-06 | `EnterNodeOperation` 38-43 每次进入只调用一次 Behavior | 自动节点测试 88-90；每个 ACTION 仅一条 Activity | C1、C5、C6 | action-a、action-b 在完整轨迹中各出现一次 | PASS |
| PASS-S1-07 | `EnterNodeOperation` 47-53 只为 Waiting 创建 Task | 自动节点测试 93 | C1、C5、C6 | RuntimeQuery 返回空 Task 列表，快照 `TASKS (empty)` | PASS |
| PASS-S1-08 | `EndProcessOperation` 19-22；`Process.completeIfPossible` | 自动节点测试 85-87 | C1、C5、C6 | Process、根 Executor 均 COMPLETED，currentNodeId=end | PASS |
| PASS-S1-09 | `ExecutionRunner` FIFO 消费至空；`CommandExecutor.execute` | 自动节点测试 72、94 | C1、C3、C5、C6 | 一次 start 返回最终完成态，最后 CommandContext 队列为空 | PASS |

### S2：只有一个人工节点

验证方法：启动 START→WAIT_A→END，查询等待稳定态；随后通过仅含外部字段的 CompleteTaskRequest 完成 Task，再查询最终状态和队列。

| 通过规范 | 相关生产代码 | 相关测试方法/断言 | 执行命令 | 实际证据 | 结论 |
| --- | --- | --- | --- | --- | --- |
| PASS-S2-01 | `EnterNodeOperation` 47-53；WAIT 不完成 Process | `shouldWaitForExternalCompleteAtManualNode` 105-109 | C1、C5、C6 | start 返回后 Process=RUNNING | PASS |
| PASS-S2-02 | `Executor.waitForExternalInput`；`EnterNodeOperation` 51 | 同测试 110-111 | C1、C5、C6 | 根 Executor=WAITING，currentNodeId=wait-a | PASS |
| PASS-S2-03 | `EnterNodeOperation` 33-53；`LeaveNodeOperation` | 同测试 112-114 | C1、C5、C6 | Activity 只有 start、wait-a；START=COMPLETED、WAIT_A=RUNNING，无 END | PASS |
| PASS-S2-04 | `WaitActivityBehavior.execute`；`EnterNodeOperation` 47-52 | 同测试 106、115-116 | C1、C5、C6 | `onlyTask` 断言数量 1，Task=CREATED/MANUAL | PASS |
| PASS-S2-05 | `Task` 构造器；`InMemoryRuntimeSession.loadResumeTarget` | 同测试 117-120 | C1、C5、C6 | processId、executorId、activityId、nodeId 四项逐一相等 | PASS |
| PASS-S2-06 | WAIT 分支不安排 Operation（`EnterNodeOperation` 47-54） | 同测试 112-114、121 | C1、C5、C6 | complete 前轨迹止于 WAIT_A，END 不存在，队列为空 | PASS |
| PASS-S2-07 | `ResumeNodeOperation` 55-60；`LeaveNodeOperation` 34-35 | 同测试 123-129 | C1、C5、C6 | complete 后 Task、WAIT_A Activity 均 COMPLETED | PASS |
| PASS-S2-08 | `TraverseEdgeOperation`；`EnterNodeOperation`；`EndProcessOperation` | 同测试 130-136 | C1、C5、C6 | 轨迹新增 end；END Activity、Executor 均 COMPLETED，位置=end | PASS |
| PASS-S2-09 | `EndProcessOperation` 20-22 | 同测试 134 | C1、C5、C6 | complete 返回 Process=COMPLETED | PASS |
| PASS-S2-10 | `CompleteTaskRequest`、`TaskCompletedSignal` 均仅四字段；`TaskService.complete` | `WorkflowArchitectureBoundaryTest#taskCompletionContractsExposeExactlyTheExternalCompletionFields` 90-97 | C2、C5、C6 | 反射断言字段精确为 taskId/result/operatorId/idempotencyKey，无目标 Node/Edge | PASS |
| PASS-S2-11 | `ExecutionRunner`；WAIT 不续排；完成链消费至空 | 人工节点测试 121、137 | C1、C3、C5、C6 | start 后和 complete 后分别检查最近 CommandContext，均 empty=true | PASS |

### S3：自动节点和人工节点混合

验证方法：启动 START→ACTION_A→WAIT_A→ACTION_B→END，检查首次稳定态；完成 WAIT_A 后检查余下自动链、次数、完整轨迹和队列。

| 通过规范 | 相关生产代码 | 相关测试方法/断言 | 执行命令 | 实际证据 | 结论 |
| --- | --- | --- | --- | --- | --- |
| PASS-S3-01 | Enter/Leave/Traverse Operation 链 | `shouldContinueAutomaticNodesAfterManualTaskCompleted` 150-157 | C1、C5、C6 | 首次轨迹为 start/action-a/wait-a；前两项 COMPLETED | PASS |
| PASS-S3-02 | `EnterNodeOperation` WAIT 分支 47-53 | 同测试 158-159 | C1、C5、C6 | WAIT_A Activity=RUNNING，Executor=WAITING | PASS |
| PASS-S3-03 | WAIT 不安排后续 Operation | 同测试 153-161 | C1、C5、C6 | 首次 Activity 精确三条，ACTION_B、END 均不存在 | PASS |
| PASS-S3-04 | `WaitActivityBehavior`；`EnterNodeOperation` | 同测试 163-165（`onlyTask` 数量断言） | C1、C5、C6 | 仅一个 CREATED/MANUAL Task | PASS |
| PASS-S3-05 | `ResumeNodeOperation` 55-60；`LeaveNodeOperation` 34-35 | 同测试 166-172 | C1、C5、C6 | complete 后 WAIT_A Task、Activity 均 COMPLETED | PASS |
| PASS-S3-06 | Resume→Leave→Traverse→Enter 同一队列链 | 同测试 173-177 | C1、C5、C6 | complete 返回前 ACTION_B 已出现且 activityCount=1 | PASS |
| PASS-S3-07 | `TraverseEdgeOperation` FIFO 安排 Enter | 同测试 173-175 | C1、C5、C6 | 完整顺序精确为 start/action-a/wait-a/action-b/end | PASS |
| PASS-S3-08 | `EndProcessOperation` 20-22 | 同测试 178-179 | C1、C5、C6 | complete 返回 Executor、Process 均 COMPLETED | PASS |
| PASS-S3-09 | `ExecutionRunner` 消费至空 | 同测试 166-180 | C1、C3、C5、C6 | 一次 complete 已达到最终态，队列为空，无额外推进调用 | PASS |

### S4：多个不同人工节点依次等待

验证方法：启动 START→WAIT_A→ACTION_A→WAIT_B→END；分别完成 Task_A、Task_B，在三个稳定态查询完整 Activity/Task 历史和队列。

| 通过规范 | 相关生产代码 | 相关测试方法/断言 | 执行命令 | 实际证据 | 结论 |
| --- | --- | --- | --- | --- | --- |
| PASS-S4-01 | `EnterNodeOperation` WAIT 分支 | `shouldStopAtEachManualNodeInSequence` 193-201 | C1、C5、C6 | Executor 等待 wait-a；Task 总数=1，且唯一 CREATED Task 节点=wait-a | PASS |
| PASS-S4-02 | `ResumeNodeOperation`；`LeaveNodeOperation` | 同测试 203-215 | C1、C5、C6 | Task_A、WAIT_A Activity 均 COMPLETED | PASS |
| PASS-S4-03 | Resume 后共用 Leave/Traverse/Enter 链 | 同测试 216、218 | C1、C5、C6 | ACTION_A=COMPLETED 且 activityCount=1 | PASS |
| PASS-S4-04 | 第二个 WAIT 分支不续排 | 同测试 219-224 | C1、C5、C6 | Executor=WAITING/currentNodeId=wait-b，Process=RUNNING | PASS |
| PASS-S4-05 | `EnterNodeOperation` 为 WAIT_B 建 Activity/Task | 同测试 207、212、214、217 | C1、C5、C6 | WAIT_B Activity=RUNNING，Task_B=CREATED | PASS |
| PASS-S4-06 | IdGenerator 每次创建新 Activity/Task；Task 绑定 Node | 同测试 209-212 | C1、C5、C6 | Task_A/Task_B 的 id、activityId、nodeId 均不相同 | PASS |
| PASS-S4-07 | `loadResumeTarget(taskId)` 精确恢复；完成链只更新 target.task | 同测试 213-214 | C1、C5、C6 | 完成 A 后 Task_A=COMPLETED，Task_B 仍 CREATED | PASS |
| PASS-S4-08 | `ResumeNodeOperation`；`LeaveNodeOperation` | 同测试 227-233 | C1、C5、C6 | 完成 B 后 Task_B、WAIT_B Activity 均 COMPLETED | PASS |
| PASS-S4-09 | Traverse→Enter END→Leave→EndProcess | 同测试 234-240 | C1、C5、C6 | 最终轨迹含 end，Executor=COMPLETED/currentNodeId=end，Process=COMPLETED | PASS |
| PASS-S4-10 | `ExecutionRunner`；两个 WAIT 稳定点和最终点均消费至空 | 同测试 201、225、241 | C1、C3、C5、C6 | start、complete A、complete B 返回后均 empty=true | PASS |

## 核心架构边界静态检查

| 边界 | 静态/测试证据 | 结论 |
| --- | --- | --- |
| Flow、Node、Edge 不保存运行状态 | 定义模型只含定义字段和对象关系；运行状态集中在 Process/Executor/Activity/Task | 符合 |
| deployed Flow 不被运行过程修改 | Command/Operation 只读取 Flow/Node/Edge；无运行态 setter 调用 | 符合 |
| Process 创建和管理 Executor | Executor 构造器包可见；`Process.createRootExecutor` 唯一公开创建边界；C2 有反射断言 | 符合 |
| FlowEngine 只依赖 CommandExecutor | `FlowEngine` 仅一个字段；C2 反射断言 | 符合 |
| CommandExecutor 创建首个 CommandOperation | `CommandExecutor.execute` 先 plan `new CommandOperation<>(command)` 再运行 | 符合 |
| ExecutionRunner 只按 FIFO 执行 EngineOperation | 无字段；循环 poll 队首；C3 事件顺序为 command/first/second | 符合 |
| 队列为空时本次运行结束 | Runner 循环条件只检查 queue；四场景所有稳定态均 empty=true | 符合 |
| ExecutionQueue 属于 CommandContext，FlowContext 不持有队列/事务 | Queue 在 CommandContext 内创建；FlowContext 仅 Flow/Process/ResumeTarget | 符合 |
| DefinitionSession、RuntimeSession 绑定同一个事务 | `CommandContextFactory.open` 将同一 transaction 传入两次；Memory factory 校验 transaction 类型 | 符合 |
| Operation 分阶段立即写入，CommandContext 最终一次提交 | Enter/Resume/Leave/Traverse/End 均先 insert/update 再 plan；C3 断言 commitCount=1 | 符合 |
| 人工等待不阻塞线程 | WAIT 分支不 plan 后续 Operation，队列自然清空 | 符合 |
| 等待时 Activity=RUNNING、Executor=WAITING | S2/S3/S4 首次及第二次稳定态断言和快照 | 符合 |
| complete 不接受目标 Node/Edge | Request/Signal 精确四字段；C2 反射断言 | 符合 |
| Process 绑定实际 flowId/flowVersion | Process 构造器从 resolve 后 Flow 绑定；PASS-S1-01/02 运行证明 | 符合 |
| RuntimeQuery 不进入 start/complete 主链 | FlowEngine、TaskService、Command、Operation 无 RuntimeQuery 依赖；Query 仅测试 Fixture 使用 | 符合 |
| 回滚后无 Process/Activity/Task 残留 | `shouldRollbackAllRuntimeWritesWhenNodeExecutionFails` 断言三种 count=0、commit count=0；C4 另证 rollback 不发布 | 符合 |

补充检查：FlowEngine/TaskService 无运行 Repository 或内存 Adapter；ActivityBehavior 不访问 Session/Scheduler；自动执行与人工恢复都通过 LeaveNodeOperation；complete 仅按 taskId 调用 RuntimeSession 恢复对象；所有检查均符合 V2 文档。

## 执行过的命令及结果摘要

| 顺序 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `./gradlew :server:testClasses` | BUILD SUCCESSFUL；目标模块生产代码和测试代码编译成功 |
| 2 | C1 | BUILD SUCCESSFUL；6 tests，0 failures，0 errors，0 skipped；打印 S1-S4 七个稳定态快照 |
| 3 | C2 | BUILD SUCCESSFUL；4 tests，0 failures，0 errors，0 skipped |
| 4 | C3 | BUILD SUCCESSFUL；2 tests，0 failures，0 errors，0 skipped |
| 5 | C4 | BUILD SUCCESSFUL；1 test，0 failures，0 errors，0 skipped |
| 6 | C5 | BUILD SUCCESSFUL；server 共 13 tests，0 failures，0 errors，0 skipped；gen 无测试 |
| 7 | C6 | BUILD SUCCESSFUL；42 actionable tasks 全部执行，包含 assemble、shadowJar、AOT 准备和完整测试 |
| 8 | `git diff --check` | 通过；unstaged 差异无 whitespace error |
| 9 | `git diff --cached --check` | 失败（exit code 2）；命中 P3：`docs/flowable-internal-execution-sequence.md:861` 文件末尾多一个空行 |

编译、目标测试、专项测试、完整测试和 build 均无失败用例，因此不存在需要单独重跑确认的测试对象。唯一失败命令是稳定复现的 staged 文档 whitespace 检查，已作为 P3 报告。

## 新增或复验的 CE 反例

- 新增 CE：无。没有观察到稳定 FAIL，不追加假设性反例。
- 已有 `CE-001`：复验通过。`WaitActivityBehavior` 读取 `config.completion.mode`；S2/S3/S4 测试 Fixture 也统一构造 `completion.mode=manual`，目标测试全部通过。
- 未修改 `docs/verification/01-flow-start-and-node-progression.md` 的既有场景、PASS 或 CE。

## 数量汇总

| 状态 | 数量 |
| --- | ---: |
| PASS | 39 |
| FAIL | 0 |
| NOT_COVERED | 0 |
| BLOCKED | 0 |

问题严重度汇总：P0=0、P1=0、P2=0、P3=1。

## 仍然存在的测试空白或环境限制

1. VER-FLOW-001 的 39 条 PASS 均有运行或静态证据；目标范围内无测试空白。
2. V2 文档提到的“已完成 Task 使用不同 idempotencyKey 必须拒绝”不属于本 verification 的 PASS；当前代码有拒绝路径，但尚无对应自动化测试。现有测试只覆盖相同 key 重试成功。
3. `RuntimeSession.loadResumeTarget` 的负向关联校验（错误 process/executor/activity/node 组合）没有专项测试；当前正向关联由 S2 覆盖，静态实现会校验 Task、Process、Activity、Executor 的主要关联。
4. 内存 Adapter 按规范仅用于单进程功能验证，不承诺并发事务合并或跨 JVM 持久化；本轮未进行并发压力或重启恢复验证。
5. 构建成功但输出既有环境/构建警告：Gradle deprecated feature（未来 Gradle 10 不兼容）、classpath 中重复 `logback.xml`、外部配置源 YAML 重复键及 Netty native-access 警告；均未导致本轮测试或构建失败，也不属于 VER-FLOW-001 结论。
6. V2 ADR 当前状态仍写为“提议中”，实施计划清单仍为未勾选；代码、标准与测试已按 V2 落地。合入时应由架构负责人决定是否同步更新文档状态，避免后续 Agent 对 V2 是否正式采纳产生歧义。

## 🟢 做得好的地方

1. V2 把 CommandContext、Session、Operation、Behavior 与只读 Query 边界分开，并用架构测试锁定关键入口依赖。
2. 四个验证场景不只检查最终 Process 状态，还检查等待前后 Executor、Activity、Task、路径、次数和队列稳定态。
3. 内存事务使用隔离快照，异常路径测试证明运行数据整体回滚。

## 📝 建议追加到 LESSONS.md

仓库当前没有 `LESSONS.md`，本轮未发现需要新增项目级踩坑规则的稳定实现缺陷。
