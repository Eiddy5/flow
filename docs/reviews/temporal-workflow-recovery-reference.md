# Temporal 流程管理与重启恢复机制参考

## 调研范围与版本基线

本文只参考 Temporal 官方文档、`temporalio/temporal` 官方仓库中的架构说明和源码入口，
用于回答两个问题：

- Temporal 如何推进、等待和管理一个长时间运行的流程；
- Worker 或 Temporal Server 重启后，未完成流程为什么能够自动恢复，以及恢复边界在哪里。

Temporal 概念文档以 2026-08-10 可访问的官方文档为准；Server 内部实现以当前官方稳定版
[`v1.31.2`](https://github.com/temporalio/temporal/releases/tag/v1.31.2) 为源码基线。
不同 SDK 的线程、协程和缓存实现不同，本文只描述跨 SDK 都成立的协议语义；具体默认值可能随
SDK 或 Server 版本变化。

## 结论摘要

Temporal 的“自动恢复”不是把 Worker 进程的线程栈或内存对象定期做快照，而是把恢复拆成
两层：

1. **Workflow 恢复**：Temporal Service 持久化每次流程状态变化形成的 Event History。
   Worker 收到新的 Workflow Task 后，从头重新执行确定性的 Workflow 代码；SDK 用历史事件
   立即返回已经发生过的 Activity、Timer 等结果，并校验重放时生成的 Command 与历史事件是否
   匹配，最后只从历史末端继续产生新 Command。
2. **任务重新派发**：Server 持久化 Workflow Mutable State、Event History、待派发任务和
   History Shard 内部 Timer/Transfer 队列。History 服务重启后从持久化 ack level 继续处理内部
   队列，Transfer Task 最终把 Workflow/Activity Task 放入 Matching Task Queue；Worker 通过
   long poll 取得任务。Workflow 与 Activity Task Queue 本身也持久化 backlog。

因此，Worker 可以是可替换的进程，Temporal Server 进程也可以重启；真正不能丢的是
Persistence 数据库，以及仍需能运行历史流程的兼容 Workflow 代码。Temporal 官方架构将这一
设计概括为：每个 Workflow Execution 保存 append-only History，所有所需流程状态都能通过
History 重放重建；Activity 则应按“可幂等重试”或“明确不重试”设计。
[Temporal 官方架构总览](https://github.com/temporalio/temporal/blob/v1.31.2/docs/architecture/README.md#L17-L35)

## 1. Temporal 管理的不是一个常驻线程

### 1.1 Workflow Definition 与 Workflow Execution

`Workflow Definition` 是用户用 SDK 编写的流程代码；`Workflow Execution` 是这段代码的一次
持久、可靠执行，并由 Namespace、Workflow ID 和 Run ID 唯一定位。一个 Execution 可以运行
数秒或数年，阻塞时并不要求占用某个 Worker 线程或进程。
[Workflow Execution 官方说明](https://docs.temporal.io/workflow-execution)

每个 Execution 的业务局部状态看起来像普通对象字段，但其可恢复来源不是 Worker 堆内存，
而是输入与 Event History。Server 另外持久化一份 `Mutable State` 投影，记录未完成 Activity、
Timer、Child Workflow 等摘要，以免服务端每次请求都重放完整历史；最近访问的 Mutable State
才会缓存在 History Service 内存中。
[History Service：Mutable State](https://github.com/temporalio/temporal/blob/v1.31.2/docs/architecture/history-service.md#L98-L109)

### 1.2 Event History 是流程事实日志

Event History 是 Temporal Service 为一次 Workflow Run 保存的 append-only 事件序列，记录
Workflow 启动、Workflow Task、Activity 调度与完成、Timer、Signal、Update、流程结束等事实。
它既是审计日志，也是崩溃后恢复流程状态的依据。
[Events and Event History](https://docs.temporal.io/workflow-execution/event)

官方 Server 架构进一步说明：仅凭某个 Execution 的 History Event 序列，就足以恢复该
Execution 的 Mutable State 和相关任务；Mutable State 是加速访问的持久化摘要，不是另一套
独立事实源。
[History Service：Workflow Execution History](https://github.com/temporalio/temporal/blob/v1.31.2/docs/architecture/history-service.md#L113-L130)

### 1.3 Workflow Task 是一次“计算下一步”

Temporal Service 在 Workflow 启动、收到 Signal/Update、Activity 完成、Timer 到期、Child
Workflow 完成，或前一次 Workflow Task 失败时调度新的 Workflow Task。一个 Workflow Task
打包自上次成功 Workflow Task 以来的新事件，让 Worker 把流程推进到下一次必须等待的位置。
[Tasks：Workflow Task 的触发条件](https://docs.temporal.io/tasks)

Worker 处理 Workflow Task 时：

1. 取得该 Execution 的 History；
2. SDK 从 Workflow 入口重放代码，重建内存状态；
3. 已完成 Activity、已触发 Timer 等调用直接读取历史结果，不再次执行外部动作；
4. 到达历史末端后执行新的分支，生成新的 Commands；
5. Worker 用 `RespondWorkflowTaskCompleted` 把 Commands 批量交回 Service；
6. History Service 持久化新事件、Mutable State 和后续内部任务。

[Tasks：Worker 如何处理 Workflow Task](https://docs.temporal.io/tasks)
[Workflow 生命周期时序](https://github.com/temporalio/temporal/blob/v1.31.2/docs/architecture/workflow-lifecycle.md#L94-L186)

可以把核心闭环概括为：

```text
外部请求 / Activity 结果 / Timer 到期
                  │
                  ▼
History Service：追加 Event，更新 Mutable State，创建 History Task
                  │
                  ▼
Transfer Queue ──► Matching Task Queue ◄── Worker long poll
                                               │
                                               ▼
                                  Replay History + 运行新逻辑
                                               │
                                               ▼
                                           Commands
                                               │
                                               └────► History Service
```

## 2. 确定性重放如何保证恢复到同一个位置

### 2.1 重放的是代码，核对的是 Command

Workflow API 调用会生成 Command，例如：

- `ScheduleActivityTask`；
- `StartTimer` / `CancelTimer`；
- 启动或取消 Child Workflow；
- 向外部 Workflow 发 Signal；
- 完成、失败、取消或 Continue-As-New。

Worker 重放时并不是再次向 Server 执行这些旧动作，而是按顺序重新生成 Command，并把它与
History 中相同位置的既有 Event 对照。例如重放出的 `ScheduleActivityTask` 必须对应已有的
`ActivityTaskScheduled`，且 Activity 类型等关键属性必须匹配；不匹配就产生 nondeterminism
错误。
[Workflow Definition：Command/Event matching](https://docs.temporal.io/workflow-definition)

这就是“从最新状态恢复”的准确含义：代码仍从入口执行，但历史让旧 awaitable 立即得到既有
结果；只有超过最后一个已记录 Event 后，流程才真正产生新动作。Temporal 对外呈现“一次流程
持续执行”的效果，底层 Workflow Function 实际可能因每次 Workflow Task、缓存淘汰或进程
重启而执行很多次。
[Workflow Execution：Replay](https://docs.temporal.io/workflow-execution)

### 2.2 为什么 Workflow 代码必须确定

相同输入与相同 History 必须让 Workflow 代码以相同顺序调用会产生 Command 的 API。直接读
系统时间、随机数、数据库、网络或文件，并据此改变 Command 顺序，会使重放无法与 History
匹配。非确定性 I/O 应放入 Activity；时间、随机数和 side effect 必须使用 SDK 提供的
replay-safe API。
[Workflow Definition：deterministic constraints](https://docs.temporal.io/workflow-definition)

已在运行的 Workflow 还要求代码升级兼容旧 History。改变 Activity/Timer/Child Workflow 的
Command 顺序，可能让旧流程永久卡在反复失败的 Workflow Task；应使用 Worker Versioning 或
SDK patching，并用历史重放测试验证。
[Workflow Definition：Versioning 与不确定性](https://docs.temporal.io/workflow-definition)

## 3. Activity 如何保存运行状态并恢复

### 3.1 Activity Execution 与 Activity Task attempt

一个逻辑 `Activity Execution` 可以包含多个 `Activity Task Execution`，每个 Task Execution
是一次 attempt。Workflow History/Mutable State 记录逻辑 Activity 已调度、是否仍待完成、重试
状态和最终结果；实际 Activity 函数在 Worker 中运行，不会像 Workflow 一样通过确定性重放恢复
任意局部变量。
[Activity Execution 官方说明](https://docs.temporal.io/activity-execution)

为避免每次重试都膨胀 History，Activity 正在运行和重试期间，History 通常只看到最初的
`ActivityTaskScheduled`；attempt count、下次重试时间等 pending 信息保存在 Mutable State，可用
Describe API 查看，`ActivityTaskStarted` 会和最终 Completed/Failed/TimedOut 等终态事件一起进入
History。
[Activity retry 的 History 记录方式](https://docs.temporal.io/encyclopedia/retry-policies)

Activity 默认带 Retry Policy；失败后 Temporal Service 按退避规则向 Activity Task Queue 放入
新的 attempt。Workflow 本身默认不带 Retry Policy，而 Workflow Task 不使用用户配置的 Retry
Policy，会由 Service 自动重试并保持 Execution 为 Open。
[Retry Policy 官方说明](https://docs.temporal.io/encyclopedia/retry-policies)

### 3.2 Worker 崩溃靠 Timeout 检测，不靠连接断开立即判死

Activity Task 交给 Worker 后，Server 不能仅凭网络断开准确判断 Activity 是否已经执行，因此
主要依赖：

- `Start-To-Close Timeout`：限制单次 attempt；常用于检测 Worker 在开始 Activity 后崩溃；
- `Schedule-To-Close Timeout`：限制包括所有重试在内的整个逻辑 Activity；
- `Schedule-To-Start Timeout`：限制一次 Task 在队列中等待 Worker 的时间，通常更适合作为容量
  监控指标；它超时后不会把任务重试回同一队列；
- `Heartbeat Timeout`：限制两次已送达 Server 的 heartbeat 之间的最大间隔，可比
  Start-To-Close 更快发现长任务停滞。

[Detecting Activity failures](https://docs.temporal.io/encyclopedia/detecting-activity-failures)

### 3.3 Heartbeat 是显式检查点，不是自动保存线程现场

长 Activity 可以在 heartbeat 中附带应用级 progress payload。下一次 retry 能读取 Server 最后
收到的 heartbeat details，并从业务检查点继续；若没有实现 checkpoint，新的 attempt 从 Activity
函数初始状态重新开始。Activity 的取消通知也通过 heartbeat 传递，不 heartbeat 的长 Activity
不能及时收到取消。
[Activity Heartbeat 官方说明](https://docs.temporal.io/encyclopedia/detecting-activity-failures)

Heartbeat 有两个重要限制：

- SDK 会节流 heartbeat，Server 不保证收到 Activity 代码产生的每一次 heartbeat；
- Worker 若在最新 heartbeat 发往 Server 前崩溃，下一次 attempt 只能取得更早的检查点。

因此 heartbeat payload 应表达可重复、可验证的业务进度，Activity 本身仍应幂等。
[Heartbeat throttling 与崩溃边界](https://docs.temporal.io/encyclopedia/detecting-activity-failures)

### 3.4 Activity 不是 exactly-once 外部副作用

典型歧义窗口是：Activity 已成功调用外部系统，但 Worker 在把完成结果持久化到 Temporal 之前
崩溃。Timeout 后 Temporal 会重试，这个外部动作可能再次发生。官方架构因此要求 Activity 要么
幂等并接受 at-least-once attempt，要么由业务显式禁用重试（例如 `MaximumAttempts=1`），以放弃
故障后的自动恢复换取 at-most-once attempt；后一种选择遇到 Worker 崩溃或 timeout 时会直接失败，
而且外部副作用是否已经发生仍可能未知。把某一种 Application Failure 声明为 non-retryable，
只会在 Worker 已把该错误报告给 Service 后阻止该错误的后续 retry，不能处理 Worker 来不及报告
任何结果的崩溃窗口。
[Temporal 官方架构的 Activity 语义](https://github.com/temporalio/temporal/blob/v1.31.2/docs/architecture/README.md#L30-L35)
[Activity 幂等建议](https://docs.temporal.io/activities)
[Retry Policy：Maximum Attempts 与 non-retryable errors](https://docs.temporal.io/encyclopedia/retry-policies)

## 4. Timer、Signal 与 Update 如何跨重启等待

### 4.1 Timer

Workflow Timer 是持久化的。Worker 等待 Timer 时不占用线程；即使到期时 Worker 或 Temporal
Service 正处于停机状态，二者恢复后 Timer 仍会触发并让 Workflow 继续。
[Timers and Start Delays](https://docs.temporal.io/workflow-execution/timers-delays)

Server 内部把 Timer 保存为 History Shard 的 Timer Task；触发时间到达后，Queue Processor
追加 `TimerFired`、调度新的 Workflow Task，并创建 Transfer Task 送往 Matching。
[History Service：Timer Task Queue](https://github.com/temporalio/temporal/blob/v1.31.2/docs/architecture/history-service.md#L227-L248)

### 4.2 Signal

Signal 是异步写消息。Service 接收它后把相应事实纳入 Workflow 状态/History，并调度 Workflow
Task；Worker 什么时候在线不影响 Signal 先被 Service 接受。发送方不能等待业务处理结果或处理
错误，因此需要结果时应改用 Update，或 Signal 后再 Query。
[Workflow message passing](https://docs.temporal.io/encyclopedia/workflow-message-passing)
[Workflow Task 的 Signal 触发](https://docs.temporal.io/tasks)

Signal 的业务 exactly-once 仍需应用设计：客户端 RPC 重试有 request ID 去重，但重复发送同一
业务 Signal 时，应把业务幂等键放进 Signal 参数并在 Workflow 状态中去重。
[Message ID 与幂等](https://docs.temporal.io/handling-messages)

### 4.3 Update

Update 是同步、可跟踪的写消息。调用方可以等待 `Accepted` 或 `Completed`：Accepted 表示 Worker
已经参与验证/接收且 Update 已持久化，Completed 表示 handler 已完成并返回结果。Update 到达也
会触发 Workflow Task，因此必须有兼容 Worker 才能推进。
[Sending Updates](https://docs.temporal.io/sending-messages)

Update ID 的 Server 去重范围是单个 Workflow Run；Continue-As-New 后若要求业务 exactly-once，
仍需把幂等状态带到新 Run。作为对照，Query 是不写 Event History 的只读请求，不能作为恢复后
继续流程的持久事实。
[Update/Signal 去重边界](https://docs.temporal.io/handling-messages)
[Query 不写 History](https://docs.temporal.io/encyclopedia/workflow-message-passing)

## 5. Sticky Execution 与 Workflow Cache

Worker 会把已经重建的 Workflow 内存状态放进 LRU cache。Temporal 默认使用 Sticky Execution，
让后续 Workflow Task 优先进入该 Worker 专属的 Sticky Queue，避免每次都下载并重放完整 History。
这是性能优化，不是正确性依赖。
[Workflow cache](https://docs.temporal.io/workflow-execution)
[Sticky Execution](https://docs.temporal.io/sticky-execution)

以下情况会回到完整重放：

- cache 因容量压力淘汰该 Execution；
- Workflow Task 失败，Worker 丢弃处于未知状态的缓存；
- Worker 进程重启，整个内存 cache 消失；
- Sticky Queue 中的 Task 默认约 5 秒内没有被原 Worker 开始，Service 禁用本次 stickiness，
  把 Task 重新调度到原始 Task Queue，让任意兼容 Worker 接手。

[Sticky Queue 失效与回退](https://docs.temporal.io/sticky-execution)

所以“必须回到同一台 Worker 才能恢复”是错误理解；同一 Worker 只减少 replay 延迟。History 很长
时 cache miss 或 Worker 批量重启会产生 replay 峰值，应通过合理 cache、Worker 容量、
Continue-As-New 和安全部署控制，而不能把本地 cache 当成持久存储。

## 6. Worker 重启时各类未完成工作的恢复路径

| 重启时刻 | Temporal 已持久化的事实 | 恢复方式 |
| --- | --- | --- |
| Workflow 正等待 Timer/Signal/Activity | History 与 Mutable State 中的等待事实 | Worker 无需常驻；新事件到来后 Service 调度 Workflow Task |
| Workflow Task 尚在普通队列 | 持久化 Workflow Task backlog | 新 Worker poll 同一 Task Queue 后取得任务 |
| Workflow Task 已被 Worker 取走但未完成 | `WorkflowTaskStarted` 与 Workflow Task timeout | 超时后 Service 自动重试，其他 Worker 重放 History |
| Task 在 Sticky Queue，但原 Worker 已重启 | Sticky Task 与短 schedule-to-start timeout | stickiness 失效，Task 回到普通队列，再完整 replay |
| Activity Task 尚在队列 | 持久化 Activity Task backlog | 新 Worker poll 后取得 attempt |
| Activity 已开始，Worker 崩溃 | Pending Activity、attempt、timeouts、最后送达的 heartbeat details | Heartbeat 或 Start-To-Close 超时后创建新 attempt；从检查点或函数开头重试 |
| Activity 外部副作用完成但结果未确认 | Temporal 可能只看到 attempt 仍未完成 | Timeout 后可能重复执行，靠幂等键或外部事务消除重复影响 |

Workflow Task Timeout 默认 10 秒，目的就是识别 Worker 是否失联，并让 Execution 能在另一个
Worker 上恢复；Workflow Task 失败不会直接关闭 Workflow Execution。
[Detecting Workflow failures：Workflow Task Timeout](https://docs.temporal.io/encyclopedia/detecting-workflow-failures)

Workflow/Activity Task Queue 通过同步 long-poll 拉取来做负载均衡；Worker 有空闲容量时才 poll。
官方文档明确说明两类 Task 会持久化在 Task Queue 中，Worker 下线时消息保留到 Worker 恢复或
其他 Worker 处理。
[Task Queues](https://docs.temporal.io/task-queue)

## 7. Temporal Server 重启为何也能恢复

### 7.1 Server 组件与持久化边界

Temporal Server 由四类可独立扩展的服务组成：

- Frontend：无状态网关、鉴权、限流与路由；
- History：管理 Workflow Mutable State、History、内部 queues 和 timers；
- Matching：托管面向 Worker 的 Task Queues 并匹配 Task 与 poller；
- Worker Service：运行 Temporal 自身的后台流程。

[Temporal Server 官方架构](https://docs.temporal.io/temporal-service/temporal-server)

Persistence 数据库存储待派发 Tasks、Workflow Execution Mutable State、append-only History 和
Namespace 元数据，是 Temporal Service 基本运行唯一必需的外部依赖。因此 Server 进程重启可
丢弃内存 cache，但不能丢弃 Persistence。
[Persistence 官方说明](https://docs.temporal.io/temporal-service/persistence)

### 7.2 History Shard 接管与内部队列续跑

Workflow Execution 按 Namespace/Workflow ID 哈希分配给固定的 History Shard；多个 History
Service 实例分别拥有一组 shard。所有权变化后，新 owner 负责该 shard 的请求、Timer 和任务
派发。Shard 持久状态包含 `RangeID` fencing generation，以及各内部队列已经处理/确认的位置，
从而避免旧 owner 继续合法写入并确定新 owner 从哪里续跑。
[History Shard 与队列状态](https://github.com/temporalio/temporal/blob/v1.31.2/docs/architecture/history-service.md#L77-L94)

History Service 启动时，会为每个已拥有 shard 启动 Transfer、Timer、Replication、Visibility、
Archival 等 Queue Processor。Processor 从 Persistence 读取已到执行时间的任务，交给执行器，
对结果 `Ack` 或 `Nack`，并周期性持久化 ack level。因为 ack 是周期 checkpoint，崩溃后可能
重做少量已经执行过的内部任务；Temporal 的内部任务处理和条件写必须容忍这种重做。
[History Queue Processing](https://github.com/temporalio/temporal/blob/v1.31.2/docs/architecture/history-service.md#L176-L206)

### 7.3 为什么不会出现“状态已提交但任务永远没发出”

History 的一次状态迁移会：追加新 History Event，并在数据库事务中更新 Mutable State、写入
后续 History Task。需要发送给 Worker 的工作先形成持久化 Transfer Task，再由 Queue Processor
调用 Matching 创建 Workflow/Activity Task。这相当于 transactional outbox：即使 History
进程在提交状态后、调用 Matching 前崩溃，Transfer Task 仍在 Persistence，重启后会再次处理，
最终把所需 Task 建到 Matching。
[State transition 与一致性保证](https://github.com/temporalio/temporal/blob/v1.31.2/docs/architecture/history-service.md#L252-L322)

Matching 自身把 Task Queue 分区；分区所有权可以重新分配，分区元数据与 backlog 能从存储加载
或卸载。Worker 的 long poll 被路由到当前负责该分区的 Matching 实例。
[Matching Service 架构](https://github.com/temporalio/temporal/blob/v1.31.2/docs/architecture/matching-service.md#L8-L18)

因此 Server 重启时并不是扫描全部 Open Workflow 并猜测“下一节点”，而是：

```text
重新取得 History Shard 所有权
        │
        ▼
加载持久化 Mutable State、queue state / ack level
        │
        ▼
继续处理到期 Timer Task 与未 ack Transfer Task
        │
        ▼
Matching 加载 Task Queue backlog，等待 Worker poll
        │
        ▼
Worker replay Event History 后继续生成新 Command
```

这是根据官方 queue processor、持久化与 Matching 分区机制得到的直接实现推论。Timer 到期时
整个 Service 都停机也不会丢失；Service 恢复后，持久 Timer Task 会被处理。
[持久化 Timer 的停机恢复语义](https://docs.temporal.io/workflow-execution/timers-delays)

## 8. 故障语义与限制

| 故障或边界 | Temporal 的语义 | 应用责任 |
| --- | --- | --- |
| Workflow Task 异常、超时、Worker 崩溃 | Task 自动重试，Execution 保持 Open | 修复阻塞、nondeterminism 或不兼容代码 |
| Workflow 业务失败 | 当前 Run 关闭为 Failed；Workflow 默认不整体重试 | 在 Workflow 中处理失败，或显式配置 Workflow Retry Policy |
| Activity 抛 retryable failure/attempt timeout | 按 Activity Retry Policy 创建新 attempt | Activity 幂等，合理设置 timeout/backoff |
| Activity non-retryable failure 或重试耗尽 | Failure 返回 Workflow 代码 | Workflow 决定补偿、失败或人工介入 |
| Worker 全部离线 | Task/等待状态保留，但流程不产生新的用户代码进展 | 恢复至少一个注册兼容类型并 poll 正确队列的 Worker |
| Temporal Server 进程离线 | 已提交状态保留；恢复后内部队列、Timer 和 Task Queue 续跑 | 部署可用的 Persistence，并正确运维 Server |
| Persistence 不可用 | Server 无法可靠提交新状态，流程暂停进展 | 恢复数据库可用性 |
| Persistence 数据丢失/损坏 | 无 History、Mutable State 和 Tasks 可供重建 | 数据库备份、复制和灾备；Temporal 进程内存不能替代它 |

Workflow Task failure 与 Workflow Execution failure 是不同层级：前者通常是 Worker、代码版本或
执行环境无法完成一次决策，Service 自动重试；后者是流程业务逻辑最终失败并关闭 Run。
[Tasks：两类 Failure 的区别](https://docs.temporal.io/tasks)

还需要注意以下限制：

- **不是任意 Java 代码都能恢复**：只有 Workflow SDK 管理的确定性状态和已经记录的结果可重放；
  本地线程、静态变量、文件、连接和未写入 History 的内存事实不会恢复。
- **不是 Activity exactly-once**：外部系统写操作必须带业务幂等键、幂等 API、唯一约束或可补偿
  协议；heartbeat 只能缩短检测并提供检查点，不能消除完成确认歧义。
- **Timeout 是恢复正确性的一部分**：尤其要设置 Activity Start-To-Close；否则 Server 无法及时
  判断已开始的 Activity attempt 是否因 Worker 崩溃而丢失。
- **兼容 Workflow 代码必须仍可部署**：History 重放遇到 Command/Event 不匹配会持续失败；需要
  Worker Versioning、patching 和 replay test。
- **History 有大小限制**：单个 Run 的 History 过大既会触发平台限制，也增加 cache miss 和重启
  后 replay 成本；长期循环流程应使用 Continue-As-New。
  [Workflow Execution limits](https://docs.temporal.io/workflow-execution/limits)
- **自建集群恢复以数据库仍然可靠为前提**：Server 的水平扩展与重启容错不等于数据库灾难恢复；
  跨集群复制、备份、RPO/RTO 仍需单独设计。

## 9. 对当前 Flow 项目的可借鉴点

Temporal 最值得借鉴的不是某个具体表名，而是以下完整链路：

1. **不可变运行事实与可变投影分离**：Event History 决定发生过什么，Mutable State 加速判断当前
   未完成工作；投影可从事实恢复，不能反过来让 Worker 内存成为事实源。
2. **状态提交与任务投递之间使用持久 outbox**：一次事务保存 Execution/TaskRun 状态及待投递
   task；后台 dispatcher 从持久队列投递并维护 ack/retry，避免“数据库已推进、WorkerTask 丢失”。
3. **Worker 无权直接推进流程**：Worker 只返回带 Execution/TaskRun identity 的结果；唯一
   Executor 在事务内校验当前状态、应用结果、计算 next 并生成下一批待投递 task。
4. **恢复协议必须覆盖三个窗口**：尚未投递、已投递未开始、已开始但结果未确认；每个窗口分别
   需要持久队列、lease/timeout 和幂等结果应用。
5. **RunnableTask 必须显式定义执行语义**：短任务至少需要 attempt timeout 与幂等键；长任务还
   需要 heartbeat/checkpoint；不能声称重启会恢复任意方法局部状态。
6. **缓存只做优化**：若未来缓存 Execution/Flow/TaskRun，应保证 cache miss、淘汰或进程重启都能
   从 Repository 事实重新装载并继续。

Temporal 的 deterministic replay 是一项系统级协议，依赖 History schema、SDK runtime、
Command/Event matching、版本管理和 replay 测试。当前 Flow 若没有这整套协议，不应只复制“从头
再跑一遍代码”的表面做法；更现实的恢复基础是持久化 Execution/TaskRun 状态机、原子 outbox、
超时租约、幂等 Worker 结果和明确的人工恢复入口。

## 最终判断

Temporal 能在重启后自动恢复流程，根因不是 Worker 足够稳定，而是 **Worker 本身可丢弃**：
Workflow 的持久事实由 Service 保存，Worker 只通过 poll 获取一次计算任务，并用 deterministic
replay 重建内存状态；Activity 通过持久 pending state、Retry Policy、Timeout 和可选 heartbeat
检查点重做。

Temporal Server 也不是靠内存续跑：Workflow History、Mutable State、Timer/Transfer Tasks、
Matching backlog 和 queue ack level 都落在 Persistence。重启后的 shard owner 与 queue
processor 从这些持久位置继续处理，最终重新派发未完成工作。这个保证的硬边界是：Persistence
必须可靠、历史 Workflow 代码必须可重放、Activity 外部副作用必须幂等；若业务显式禁用重试，
则必须接受 Worker 崩溃或 timeout 后不再自动恢复的 at-most-once attempt 取舍。
