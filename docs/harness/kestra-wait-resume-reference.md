# Kestra 等待与外部恢复机制参考

调研时间：2026-07-29。源码基线为 Kestra 稳定版
[`v1.3.30`](https://github.com/kestra-io/kestra/releases/tag/v1.3.30)
（2026-07-28）；官网 Core Plugin 页面当时仍展示 `1.3.29`，因此涉及内部行为时以
`v1.3.30` 标签源码为准。

## 结论

Kestra 的核心抽象是“暂停一个 Execution 及其 TaskRun，之后恢复该
Execution”，而不是独立的通用 `ExternalTask/WaitPoint` 聚合，也不是
`fetch-lock-complete` 式外部 Worker 协议：

- `Pause` 负责持久化等待；恢复身份是 `executionId`。
- 人、外部系统和另一个 Flow 最终都调用同一恢复能力；`onResume` 只是恢复时的
  结构化输入契约。
- Webhook、Flow、Polling、Realtime Trigger 的语义都是**创建新 Execution**，
  不是恢复已暂停的 Execution。需要把事件变成恢复动作时，要直接调用 Resume API，
  或让一个由事件启动的“桥接 Flow”执行 `Resume` task。
- 通知、HTTP 派发等业务动作由 Pause 前置任务或 `onPause` 任务完成，并未被抽象成
  一个统一外派队列。

## `Pause` 的持久化状态与恢复

`io.kestra.plugin.core.flow.Pause` 是 `FlowableTask`。首次解析时，它不给下游任务，
而把自身 TaskRun 置为 `PAUSED`；Executor 随之把 Execution 置为 `PAUSED`。
`onPause` 可运行一个普通 Task，适合发通知或发出外部请求；`onResume` 定义恢复时
需要校验和收集的输入，恢复后写入 Pause 输出，同时记录 `resumed.by/on/to`。
相关实现见
[`Pause.java`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/plugin/core/flow/Pause.java#L159-L208)、
[`Pause.resolveNexts/resolveState`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/plugin/core/flow/Pause.java#L266-L323)。

官方文档明确说明，`PAUSED` Execution 会持久化在数据库中，可跨服务重启等待数周；
它不是“占住 Worker 线程”的阻塞调用。参见
[Approval Processes](https://kestra.io/docs/use-cases/approval-processes#best-practices-for-long-running-workflows)。
相反，`Sleep` 的源码直接调用 `TimeUnit.MILLISECONDS.sleep`，适合短时节流，不是长时
外部等待的等价物，见
[`Sleep.java`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/plugin/core/flow/Sleep.java#L47-L63)。

恢复入口为：

```http
POST /api/v1/{tenant}/executions/{executionId}/resume
Content-Type: multipart/form-data
```

Controller 先要求 Execution 当前为 `PAUSED`，读取并校验 Pause 的 `onResume`
输入，再把更新后的 Execution 发回执行队列；非 `PAUSED` 返回冲突。见
[`ExecutionController`](https://github.com/kestra-io/kestra/blob/v1.3.30/webserver/src/main/java/io/kestra/webserver/controllers/api/ExecutionController.java#L1542-L1595)
和
[`ExecutionService.getExecutionIfPause`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/core/services/ExecutionService.java#L124-L131)。

对由 `Pause` 产生的等待，内部流程是：

1. 找到 Execution 中**第一个** `PAUSED` TaskRun；API 不接收 `taskRunId`。
2. 无子任务的 Pause TaskRun 在人工恢复时变为 `SUCCESS`，并写入
   `onResume/resumed` 输出。
3. 若仍有其他 `PAUSED` TaskRun（例如并行分支），Execution 继续保持
   `PAUSED`；否则源码先将 Execution 标为过渡态 `RESTARTED`，Executor 再继续调度。

证据见
[`Execution.findFirstByState`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/core/models/executions/Execution.java#L589-L598)、
[`ExecutionService.resume`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/core/services/ExecutionService.java#L699-L761)
和
[`ExecutionService.markAs`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/core/services/ExecutionService.java#L455-L525)。
官网把可见语义简化为 `PAUSED -> RUNNING`，并明确不存在 `RESUMING/RESUMED`
状态；`resumed` 是 Pause 输出元数据，不是 Execution 状态，见
[States](https://kestra.io/docs/workflow-components/states#paused)。

这带来一个重要并发边界：单 Pause 场景中，重复恢复会因 Execution 已非
`PAUSED` 而失败，不会再次推进；但并行存在多个 Pause 时，第一次恢复后 Execution
仍为 `PAUSED`，同一回调的重试可能恢复“下一个”暂停 TaskRun。因此该 API 是
Execution 级状态命令，不是带 `signalId`、目标 WaitPoint 和幂等回执的恢复协议。

## 定时、超时与轮询等待

`Pause.pauseDuration` 到期后的 `behavior` 可选 `RESUME`（默认）、`WARN`、
`FAIL`、`CANCEL`；在到期前从 UI/API 手动恢复时，`behavior` 不参与判断，Pause
按成功处理。未设置 `pauseDuration` 时可使用通用 Task `timeout`，否则会无限等待。
参见
[Pause Plugin](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.pause#properties)
和
[`ExecutorService.handlePausedDelay`](https://github.com/kestra-io/kestra/blob/v1.3.30/executor/src/main/java/io/kestra/executor/ExecutorService.java#L708-L755)。

定时恢复不是靠内存计时器：Executor 创建包含 `executionId`、`taskRunId`、到期时间和
目标状态的 `ExecutionDelay`；JDBC 后端持久化它，到期后用数据库锁取出，再在锁定
Execution 后恢复。见
[`ExecutionDelay`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/core/runners/ExecutionDelay.java)、
[`AbstractJdbcExecutionDelayStorage`](https://github.com/kestra-io/kestra/blob/v1.3.30/jdbc/src/main/java/io/kestra/jdbc/runner/AbstractJdbcExecutionDelayStorage.java#L23-L52)
和
[`JdbcExecutor.executionDelaySend`](https://github.com/kestra-io/kestra/blob/v1.3.30/jdbc/src/main/java/io/kestra/jdbc/runner/JdbcExecutor.java#L1422-L1485)。

旧资料中的 `WaitFor` 在当前版对应 `LoopUntil`：`LoopUntil` 仍声明
`io.kestra.plugin.core.flow.WaitFor` 为别名。它重复执行子任务并检查条件；条件未满足
时创建下一检查时间并暂时把 Flowable TaskRun/Execution 置为 `PAUSED`，到期后重建
下一轮子 TaskRun。它适合轮询外部 API/资源，不是由外部事件主动完成的回调等待。
参见
[`LoopUntil.java`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/plugin/core/flow/LoopUntil.java#L73-L224)
和
[Flowable Tasks](https://kestra.io/docs/workflow-components/tasks/flowable-tasks#loopuntil)。
从 0.23 起默认不再限制 `maxIterations/maxDuration`，默认检查间隔为一分钟，生产使用
应显式设上限，见
[0.23 migration guide](https://kestra.io/docs/migration-guide/v0.23.0/loop-until-defaults)。

## 人工处理、回调和消息如何接入

| 场景 | Kestra 原生组合 | 是否恢复原 Execution |
| --- | --- | --- |
| 普通人工确认 | `Pause` + `onResume`，用普通 Task/`onPause` 发 Slack、邮件等通知 | 是，由人从 UI 或 Resume API 恢复 |
| 指定人员审批 | Enterprise `HumanTask`，`assignment.users/groups` 限制谁可恢复 | 是；未授权用户被拒绝 |
| HTTP 回调 | 外部系统直接调用 Resume API；或 Webhook 启动桥接 Flow，再执行 Resume task | 只有显式 Resume 动作才会恢复 |
| Kafka/SQS 等消息 | Realtime/Polling Trigger 启动桥接 Flow，再按消息中的 `executionId` 恢复 | Trigger 自身不会恢复 |
| 轮询外部状态 | `LoopUntil`（旧名 `WaitFor`）定期运行查询 Task | 原 Execution 内部自动续跑 |
| 到时继续/失败 | `Pause.pauseDuration + behavior` | 由持久化 `ExecutionDelay` 自动处理 |

Enterprise `HumanTask` 从 1.1 起支持用户和 RBAC 组指派，只允许被指派者恢复；其
公开属性仍复用 `onPause/onResume/pauseDuration/behavior` 语义。参见
[HumanTask 官方说明](https://kestra.io/docs/use-cases/approval-processes#humantask-assign-specific-users-for-approval)
和
[HumanTask Plugin](https://kestra.io/plugins/core/flow/io.kestra.plugin.ee.flow.humantask)。

Webhook Controller 和 Plugin 都明确使用“Trigger a new execution”，实现也调用
`newExecution(...)` 后 `startExecution(...)`，见
[`ExecutionController webhook endpoints`](https://github.com/kestra-io/kestra/blob/v1.3.30/webserver/src/main/java/io/kestra/webserver/controllers/api/ExecutionController.java#L553-L603)
和
[`Webhook.evaluate`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/plugin/core/trigger/Webhook.java#L167-L190)。
Realtime Trigger 同样是一条事件创建一个独立 Execution，见
[Realtime Triggers](https://kestra.io/docs/workflow-components/triggers/realtime-trigger#how-realtime-triggers-work)。

因此“Webhook/消息回调恢复”的官方能力需要组合：

```text
外部事件
  -> Webhook / Realtime Trigger
  -> 新的桥接 Execution
  -> io.kestra.plugin.kestra.executions.Resume(executionId, inputs)
  -> 原 PAUSED Execution 继续
```

当前推荐的 Resume task 是
[`io.kestra.plugin.kestra.executions.Resume`](https://kestra.io/plugins/plugin-kestra/kestra-executions/io.kestra.plugin.kestra.executions.resume)，
它通过 Kestra API 恢复指定 Execution 并转发输入；Core 中旧的
`io.kestra.plugin.core.execution.Resume` 已自 1.2 标记废弃，见
[`core Resume.java`](https://github.com/kestra-io/kestra/blob/v1.3.30/core/src/main/java/io/kestra/plugin/core/execution/Resume.java#L29-L52)。

Flow Trigger 可监听上游 `PAUSED` 来启动通知/分派 Flow，但它仍创建新 Execution。
它无法监听不存在的 `RESUMED` 状态；需要下游解耦时，官方建议监听原 Execution 的
`SUCCESS`。参见
[Flow Trigger PAUSED 变更](https://kestra.io/docs/migration-guide/v0.23.0/flow-trigger-paused-state)
和
[Approval Processes](https://kestra.io/docs/use-cases/approval-processes#pause--manual-resume--flow-trigger-pattern)。

## 关联与幂等边界

Kestra Enterprise 为每个 Execution 提供不可变的 `system.correlationId`：
默认等于根 Execution ID，并向 Subflow 传播；创建 Execution 时可设置稳定业务键。
但官方明确指出它只负责关联与查询，**不会自动阻止重复处理**。Webhook 的幂等键在
Execution 创建后才可读，因此官方示例把它写入自定义 label，再查询历史执行去重；
该检查并非原子操作，严格 once-only 仍应由消息代理、数据库唯一约束或 API Gateway
保证。参见
[Idempotency with correlation IDs](https://kestra.io/docs/how-to-guides/idempotency#how-systemcorrelationid-works)。

对于恢复命令，公开协议没有独立 `signalId` 或幂等记录。外部集成至少应自行保存：
`externalEventId -> executionId`，在原子幂等存储中先占位，再调用 Resume API；若
流程允许并行 Pause，还需避免用同一个 Execution 级 Resume 入口区分多个等待目标。

## 对 Flow 项目的可借鉴点

- 可借鉴：把“等待”作为 Execution/TaskRun 的持久化状态；恢复输入按 Task 定义校验；
  到期动作使用持久化 Delay；通知/派发与等待状态解耦。
- 不宜照搬：只用 `executionId + first PAUSED TaskRun` 选择恢复目标。Flow 若要支持
  多并行等待、不同外部来源和安全重试，应保留明确的 `waitPointId` 与 `signalId`，
  让“恢复哪一个等待点”和“同一信号是否已消费”成为领域不变量。
