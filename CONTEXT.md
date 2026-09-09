# Flow Workflow

本上下文定义可发布、可运行并可恢复的工作流。它统一描述流程定义、一次运行及单个步骤运行事实之间的关系。

## Language

**Entity ID**:
一个领域实体创建时产生并在保存、恢复和状态变化中保持不变的非空字符串标识；对象自身称为 `id`，被其他对象引用时使用 `executionId`、`taskId`、`taskRunId` 等明确名称。Flow 不使用跨版本稳定的 Entity ID：它的 `id` 只标识一次保存形成的记录，业务身份由租户、key 和 version 组成。
_Avoid_: recordId, identifier, entity-id wrapper, business key as entity id

**Flow**:
可保存、可部署和可恢复的工作流定义，由具体聚合 `Flow` 统一表达。它拥有稳定 key、原始 YAML `source` 和明确的 `draft` 状态；每次保存都形成带正整数 version 的 Flow，`companyId` 和 Audit Status 由领域维护而不属于 `source`。Flow 不保存某次 Execution 的运行 State。
_Avoid_: separate source aggregate, recordId, Process

**Flow Creation**:
从外部 YAML 正向形成一个尚未补齐运行身份的 Flow 定义；草稿和部署都可以物化同一份 YAML，之后由所属会话补充租户、操作者、审计和生命周期事实。
_Avoid_: source-only draft, parser-specific aggregate

**Flow Recovery**:
从持久化的 Flow 事实恢复同一个聚合及其身份、审计、状态、版本和 Task 快照；原始 `source` 用于回显，不因为恢复而重新生成身份或反向解析 YAML。
_Avoid_: recreate, redeploy, source reparse

**Flow ID**:
用于 Flow Repository 查询的业务选择器，包含 `companyId`、`key` 和可空 `version`。`companyId + key + version` 唯一标识一次已保存的 Flow 状态；省略 version 时选择相应查询语义下的当前状态。它不等于 `Flow.id`，不作为数据库行标识。
_Avoid_: Flow entity id, recordId, FlowDraft id

**Flow Draft**:
`Flow` 的可编辑状态，由 `draft=true` 明确表达并默认创建于该状态。每次成功保存草稿都会形成新的 Flow Version，当前草稿是同一租户和 key 下最新的草稿状态；`source` 原样保存定义，完整部署校验留到发布正式版本时执行。
_Avoid_: separate FlowDraft entity, generic Draft, Draft status enum, recordId

**Flow Version**:
同一租户和稳定 key 下每次成功保存 Flow（草稿、发布或删除）形成的正整数内部版本，沿同一序列递增。`companyId + key + version` 唯一确定该次保存，version 不由 `source` 声明或覆盖。
_Avoid_: source version, draftless version, mutable version

**Flow Reversion**:
同一逻辑 Flow 的一次成功部署所形成的、具有正式 Flow Version 且可独立读取的不可变 Flow 快照。
_Avoid_: Revision, Draft, mutable Flow

**Flow Variable**:
由 Flow Reversion 持有的、供该版本所有 Execution 共享读取的流程级键值；它属于流程定义配置，不是某次 Execution 产生的运行结果，也不能在 Task 之间隐式累积或写回。
_Avoid_: Execution Input, Task Output, Writable Shared State

**Current Flow Reversion**:
某个租户和 Flow key 下 version 最大的 Flow Reversion；只有该最大 Reversion 尚未删除时，它才供普通的新 Execution 启动绑定。最大 Reversion 已删除时不存在可启动的当前 Flow，不能回退到旧 Reversion；已经由外部业务持久承诺的精确 Execution Binding 按其原 Flow Reversion 物化，不属于普通新启动。
_Avoid_: Current Flow Version, Only version, mutable version

**Audit Status**:
可审计领域对象当前的通用记录状态；新对象从 Open 开始，Delete 是带完整删除操作者和时间的不可逆终态，其他记录状态可以为宿主兼容而保留。它不是工作流运行状态，也不是完整操作日志。
_Avoid_: Workflow Runtime State, deleted flag, audit log

**Flow Deletion**:
一个 Flow 定义状态被用户删除后的不可用事实，由 Audit Status 的 Delete 及同一次删除审计共同表达。删除目标由 Flow 自身的 `draft` 状态明确选择：删除草稿不改变已部署版本，删除最新 Flow Reversion 也不改变草稿或更早的 Reversion。被删除的草稿不能继续编辑或部署；最新 Reversion 被删除后不能回退到旧 Reversion 启动新的 Execution，但已经启动的 Execution 继续绑定原 Flow Reversion。
_Avoid_: CLOSED status, FlowDefinitionStatus, physical deletion, version replacement

**Workflow Runtime State**:
由 Flow 定义域的 `State` 统一定义、贯穿一次工作流运行的状态语言；
State 对象以 `current` 表达当前状态，以 `history` 保存从创建开始的真实变化
轨迹。`State.Type` 统一包含
`CREATED/RUNNING/PAUSED/RESTARTED/SUCCESS/SKIPPED/WARNING/FAILED/KILLING/KILLED`。
Execution 使用除 SKIPPED 外的生命周期状态；RESTARTED 和 KILLING 只属于
Execution。TaskRun 不使用 RESTARTED 或 KILLING；SKIPPED 只属于已实际进入判断、
但条件未命中的 Route TaskRun，它是终态且没有 outputs 或 error。
Execution、TaskRun 各自持有完整 State 并限制自己的合法路线；RunnableTask 只
报告完成或失败事实，OrchestrationTask 的暂停和作用域收敛由 Executor 直接
应用，全部历史仍由聚合产生。审批、表单或工单仍拥有自己的外部业务状态。
_Avoid_: ExecutionStatus, TaskRunStatus, WorkerTaskOutcome, approval status

**Data**:
Flow 或 Task 中以唯一业务 `key` 标识、并声明基础值类型的数据定义；它是 Input 与 Output 的共同基础，但不保存某次运行产生的实际值。
_Avoid_: Runtime value, arbitrary Map, database column

**Data Type**:
Data 对实际值种类的稳定契约，由独立的有限类型 `DataType` 表达；Input 与 Output 的基础值类型具有相同含义，并决定一个已提供运行值能否被该数据定义
接受。Data Type 不属于 Data 内部，也不表达输入输出方向、Task 类型或业务字段
用途。
_Avoid_: Data.Type, Task Type, Java class name, database column type, implicit String

**Input**:
由 Flow 或 Task 持有的单字段输入定义，描述字段标识、名称、必填性、默认值和接受规则。
每个 Input 自行解释其字段的提交：缺失时使用默认值，显式空值不使用默认值，并负责
转换和检查字段值。业务可以提供自己的 Input 定义类型，复用基础值类型并持有自己的规则。
Input 定义建立时即有效，建立后不再修改。所属定义管理字段之间的关系。实际启动值属于 Execution，任务调用值
属于 TaskRun。
_Avoid_: Concrete Input, Input value, request DTO, untyped input Map

**Output**:
直接实现 Data、由 Flow 或 Task 持有的具体输出定义对象，以 `key` 和独立
Data Type 描述所属定义可以产生并交给下游的数据；实际输出值属于 TaskRun。
_Avoid_: Output interface, Output value, response DTO

**Task**:
Flow 定义中不可分割的流程步骤定义；其领域字段 `id` 在正式 reversion 间保持稳定，
被引用时称为 `taskId`。抽象 Task 只保存所有 Task 共有的 `id`、`key`、
`displayName`、`inputs` 和 `outputs`，不保存 route、dependOn 或 tasks，也不保存实际
运行结果。定义树的 parentId 是持久化 Adapter 从结构关系派生的关系字段，不是 Task
公共领域字段。YAML 只声明业务 `key`，创建时可先物化，正式部署时才确认完整约束和
跨 reversion 的身份稳定性。
RunnableTask 与 OrchestrationTask 是具体 Task 可拥有的两种互斥能力，离开 Task 后没有
独立业务意义；Worker 和 Executor 分别调用或解释这些能力，但不拥有它们。
_Avoid_: Node, Activity

**Runnable Task**:
继承 Task 并实现 RunnableTask 能力的具体步骤定义；它把实际工作写在
`run(RunContext)` 中，只有这种 Task 才能形成 WorkerTask 并交给 Worker。它不能
改变 Execution、TaskRun、nexts 或分支编排状态；只能读取本次调用所需的
运行输入和技术身份。
_Avoid_: WorkerTaskHandler, executable OrchestrationTask, generic Task execution

**Orchestration Task**:
继承 Task 并实现 OrchestrationTask 能力的流程控制步骤定义，例如 PAUSE、
ROUTE、SEQUENCE、PARALLEL、LOOP 和 LOOP UNTIL。需要拥有有序子 Task 的结构类型
继承抽象 Branch；PAUSE 直接继承 Task 并拥有自己的 pause 关系。Orchestration Task
没有实际工作和
`run` 方法，不形成 WorkerTask；Executor 直接根据其编排特征管理 TaskRun、暂停、
作用域收敛和后续路线。
_Avoid_: tasks on every Task, runnable orchestration, structural WorkerTask

**Branch**:
所有结构型流程 Task 的抽象基类，继承 Task 并独占有序 `tasks` 定义。Route、Sequence、
Parallel、Loop 和 Loop Until 通过继承 Branch 获得子树；Runnable Task 和 Pause 不
拥有该字段。Branch 只表达结构关系，不自动获得 route、condition 或 DAG dependency。
_Avoid_: tasks on Task, route on Branch, condition on Branch, concrete generic branch plugin

**Run Context**:
每次只服务一个 RunnableTask 调用的临时不可变上下文，提供当前 Session、命令
DSLContext、本次实际 inputs，以及 `executionId()`、`taskRunId()` 和可选的
`parentTaskRunId()` 调用期技术身份。`taskRunId` 标识当前 RunnableTask 的
TaskRun，`parentId` 只标识其直接父 TaskRun；两者都不暴露 TaskRun 聚合
或状态修改能力。variables 中的 Execution 也只是本次调用的运行时引用，
Task 不得通过它推进状态。Run Context 不持久化，也不跨 Task 复用。
_Avoid_: WorkerContext, Persisted Execution Context, TaskRun Snapshot, State Mutation Handle

**Ordered Task Children**:
Branch 按定义顺序拥有的直接子 Task；前一个已选择子 Task 的完整子树收敛后才能进入
下一个。Route 条件不成立时，其已创建并开始的 TaskRun 进入 SKIPPED，但该 Route 的
子 Task 不产生 TaskRun。它是 Sequence、Route 和循环体的串行结构语义，不属于普通
Runnable Task，也不表示同级分支同时运行。
_Avoid_: Implicit parallel branches, sibling batch, unordered children

**PARALLEL Task**:
显式声明多个直接子分支可以同时开始的编排作用域。它进入后保持 RUNNING，把所有
直接子 Task 作为同一 Execution 内的并行分支运行，只在全部实际分支子树正常收敛后
完成。条件分支通过显式 Route 子 Task 表达；Route 不能读取尚未完成的并行兄弟输出。
并行不由普通同级 Task 或 Child Execution 隐式推断。各分支共享同一进入
上下文快照但独立演进，outputs 不自动合并；`concurrent` 只声明后续 Worker 队列
消费者并发上限。
_Avoid_: Implicit parallel, Parallel Execution, Child Execution, parallel flag

**LOOP Task**:
按必填正整数 times 串行重复完整子 Task 列表的编排作用域；每轮从 1 开始编号，
只有当前轮完整收敛后才能创建下一轮，同一 Task 定义的每轮运行形成独立 TaskRun。
Loop 的进度从精确 Flow Reversion 和 TaskRun 事实恢复，不保存运行游标。
_Avoid_: ForEach, recursive Task graph, in-memory counter, retry

**LOOP UNTIL Task**:
至少执行一轮完整子 Task 列表，并在每轮收敛后使用受限条件读取本轮 Task outputs；
条件成立时完成，达到必填 maxIterations 仍不成立时失败。它不是外部恢复等待、
定时轮询器或无限循环。
_Avoid_: WaitFor, Pause, arbitrary script, unbounded loop

**Loop Iteration**:
一个 Loop 或 Loop Until TaskRun 内从 1 开始编号的一轮真实执行范围；循环体直接子
TaskRun 以 iteration 保存轮次，更深后代通过 parentId 链归属该轮。它没有独立
Repository 或生命周期。
_Avoid_: Loop cursor, retry attempt, Task definition version

**Task Extension**:
为一个稳定 Task `type` 提供具体 Task 定义及其物化、重建和专有 properties 规则的
Plugin 扩展点；注册后 Flow 可以用该 `type` 部署 Task。运行能力由具体 Task 自身
实现 RunnableTask 或 OrchestrationTask，不由 Plugin 注册机制执行。Plugin 不分配
Task 身份、不拥有 Flow 聚合或 TaskRun 状态。
_Avoid_: Task instance, WorkerTaskHandler, Task type catalog, generic Task

**Route Rule**:
Route 定义持有的必填条件字符串，以 `route` 表达并保留用户原始定义；
只在正式发布校验或实际判断 Route 时形成 Condition。它不属于 Branch 公共
结构，也不是已解析 Condition Tree 的持久化快照。
_Avoid_: Branch route, Route condition field, persisted Condition tree

**Condition**:
用于一次只读判断的不可变递归值对象；Loop Until 直接持有，Route 只在使用
Route Rule 时形成。比较节点由
左侧 Condition Reference、基础 Comparison 和右侧 Condition Constant 组成；逻辑节点
用 AND 或 OR 组合至少两个子 Condition。外部字符串支持
`&&`、`||` 和括号并解析为树，运行时不执行源文本。Route 与 Loop Until 分别构造
Condition Context 并保护自己的 outputs 可见范围；缺值和类型不兼容返回 false。
_Avoid_: TaskRoute, arbitrary script, right-side reference,
Task Template Expression

**Condition Reference**:
Condition 比较左侧对运行时 variables、inputs 或 outputs 的只读引用；只有完整
`{{ scope.path }}` 才表示引用，花括号是整值引用标记而不是文本插值。未包裹的点分文本
不解析为引用。
_Avoid_: bare path, template interpolation, right-side reference

**Condition Constant**:
Condition 比较右侧在定义时写入且求值时不再解析的数据；未包裹的 `true`、`false` 和
十进制数分别形成 Boolean、Number，其余文本形成 String，需要保留类型化外观或语法
保留字符的 String 使用双引号。常量永不从 Condition Context 取值。
_Avoid_: preset, implicit reference, runtime value, null literal

**Condition Context**:
一次 Condition 求值使用的临时只读 variables、inputs 和 outputs 映射；不保存 Flow、
Execution、TaskRun、Repository 或上次求值结果。不同消费方只放入自身允许读取的
outputs。
_Avoid_: persisted context, mutable shared variables, expression engine scope

**Task Template Expression**:
由 Task 定义持有、在一次 Runnable Task 调用中从只读运行输入提取值并插入固定文本
的消息模板；它属于受限表达式领域，只允许点分路径读取，不能执行脚本、调用方法
或改变运行上下文，也不承担 Condition 的 boolean 判断。
_Avoid_: Condition, arbitrary script, mutable runtime context

**Log**:
在流程运行时解析 message 模板并把结果写入应用日志的步骤；它不负责审计留痕、
不产生流程输出，也不改变 Execution 或 TaskRun 的推进规则。对于只需要无业务副作用
地记录或推进流程的步骤，Log 是当前内置的可执行步骤；它不代表通用业务计算能力。
_Avoid_: LogTask, Audit Log, logging service

**Execution**:
Flow Reversion 被启动后形成的一次完整运行实例；它拥有稳定字符串 `id`，永久绑定启动时的 `companyId + flowKey + flowVersion`，保存生命周期状态和有序 TaskRun 历史，并且可以同时拥有多个活动 TaskRun。Execution 不是移动游标，实际执行路径由 TaskRun 事实表达。
_Avoid_: Process, workflow instance

**Durable Execution Materialization**:
可信外部业务把已经持久承诺的 `executionId + companyId + flowKey + flowVersion` 精确、幂等地物化为待运行 Execution；它不重新选择 Current Flow Reversion，也不允许普通调用方任意启动历史 Reversion。
_Avoid_: Historical Flow Start, Latest Flow Fallback, Execution Retry Copy

**Executor Scheduling Cycle**:
以一个 Execution 及其精确 Flow Reversion 为输入、从已有 TaskRun 事实重建下一
批工作并推进到下一个可提交点的一次可恢复调度循环。循环内的 ExecutorContext
只暂存 nexts、Runnable WorkerTask、暂停效果、编排作用域完成和状态变化增量；它不是持久化游标，也不持有
草稿状态 Flow、Session 或 DSLContext。
_Avoid_: Execution cursor, transaction context, persisted next queue

**Queue Event**:
由所属业务 Module 定义、可交给 Flow Queue 异步传输的类型化内容；业务自身拥有其
字段、key 和内部分类，并直接提供只供同步发布使用的可空 `DSLContext`。DSL 不是
业务事实，不进入持久化 Event；Queue 也不把 Event 转换为统一业务 Message 或结果信封。
数据库 Adapter 将不同传输类别的 Event payload 统一保存在 `queues`，类别和
逻辑 Queue 分别由 `queue_type` 与 `queue_name` 表达。
_Avoid_: generic Message, Queue Record, transport envelope

**Dispatch Queue**:
把每个 Queue Event 交给一个竞争 Consumer 的异步传输能力；多个 Consumer 只增加
并发度，不形成广播，也不负责判断接收端业务是否完成。
_Avoid_: Broadcast Queue, Command Bus, business retry manager

**Queue Subscription**:
一个 Consumer 在 Dispatch Queue 上的活动注册；它独立拥有暂停、恢复和关闭生命
周期，同一注册内的 Consumer 串行接收 Queue Event。
_Avoid_: Queue Consumer, ACK handle, business subscription

**Default Dispatch Queue**:
Dispatch Queue 当前基于 PostgreSQL 的默认 Adapter；它把 Event 的业务 JSON object
作为 JSONB 写入统一 `queues`，并固定 `queue_type = 'DISPATCH'`；Subscription
按 `queue_type + queue_name` 周期轮询并使用 `FOR UPDATE SKIP LOCKED` 竞争一行，在领取
事务内调用 Consumer 并删除。它直接使用 Event 的可空 `dsl()` 或开启自有同步事务；
异步发布始终使用自有事务且不携带 DSL。
Default Queue 使用项目 `JsonFactory` 重组排除 DSL 的 Queue Entry，并通过装配时指定
的 `Class<T>` 恢复业务 Event；内部 `eventType` 仍由业务自己解释。普通 Consumer
异常仍完成删除；只有领取事务未提交时原行重新可见，因此崩溃恢复可能再次调用
Consumer。
Broadcast 未来同样复用 `queues` 并使用 `BROADCAST` 类型；当前不提供其
Interface、消费游标或保留清理，未来专属投递状态应放入独立表而不是拆分消息载荷。
_Avoid_: storage-specific public Queue name, Transactional Outbox, Retry Queue, Exactly-once Queue

**TaskRun**:
Execution 实际执行某个 Task 时产生的真实实例。Executor Scheduling Cycle 可以
先在 nexts 中构造 CREATED TaskRun，但只有 `onNexts` 经 Execution 聚合接受后
才成为真实历史。它通过 `taskId` 关联确定 Flow Reversion 中的 Task，通过
`parentId` 关联真实父 TaskRun，并保存本次执行的状态、输入、输出和错误。循环体
直接子 TaskRun 还以可空的正整数 iteration 表达所属轮次；同一 Task 可以因循环
产生多个 TaskRun，其列表顺序表达真实运行顺序。条件未命中的 Route 仍是已经判断过的
真实 TaskRun，以 SKIPPED 保存；其未进入的子路径不伪造 TaskRun。
_Avoid_: Activity, Task instance

**PAUSE Task**:
由 Flow Core 提供的外部暂停编排 Task。它通过唯一必填的 `pause` Task 定义进入
等待前必须执行的动作，通过 `resume` Input 列表定义外部回调，通过可选且成对出现
的 `duration + behavior` 定义超时目标。Executor 先让 Pause TaskRun 保持
`RUNNING` 并无条件执行完整 pause 子树，收敛后才使 Pause TaskRun 进入
`PAUSED`；Execution 始终保持 `RUNNING`。`pause` 只表示暂停前必须完整执行的
专有 Task，并通过 `definitionChildren()` 纳入定义树；Pause 直接继承 Task，不拥有
Branch 的普通 `tasks`。Pause 的 `outputs` 直接使用
Task 的普通可配置输出契约；`resume` 只定义外部回调输入，不从 resume 派生 outputs，
也不要求两者字段一致。它不定义审批人、表单、工单或其他外部业务规则。
_Avoid_: Approval Task, User Task, Assignment, External Task

**Execution Origin**:
一次运行的直接父运行编号与整棵派生树最初运行编号组成的来源关系。首次运行没有父运行，后续每次派生指向直接来源，并沿用最初运行编号。
_Avoid_: execution generation number, task parent, mutable current execution pointer

**Inherited TaskRun**:
新运行沿用的历史步骤运行事实，保留原步骤运行编号、已有结果和进度，能够与本次真正重新执行的步骤区分。后续运行的变化不覆盖原运行保存的事实。
_Avoid_: rerun, newly completed task, shared mutable history

**Execution Rewind**:
从当前有效暂停节点退回已完成的因果前驱，产生具备来源关系的新运行，整体接替原运行。原运行停止并保留历史；受影响片段重新执行，无关并行分支沿用已有进度。后续回调使用新运行编号，旧运行编号不能自动转到新运行。保持跨嵌套编排支持，循环内部端点仍不在本次范围。见 [ADR 0087](docs/decisions/0087-derive-execution-snapshots-on-replay.md)。
_Avoid_: same-execution rewind, global suffix invalidation, append order as branch order

**Execution Resume**:
Flow Core 接受一个确定 PAUSE TaskRun 的外部回调，按该 Pause 的具体 resume Input
校验并规范化数据，再把原 TaskRun 从 `PAUSED` 恢复为 `RUNNING`，由 Executor
状态机完成它并继续同一 Execution。非法回调原子拒绝且 TaskRun 保持 `PAUSED`；
外部调用方只提供 `executionId + taskRunId` 和回调数据，不能指定下一 Task、路由
或目标状态。
_Avoid_: External Task completion, direct TaskRun update, external routing

**External Business Capability**:
与 Flow Core 对接、但拥有自身业务对象和生命周期的能力，例如审批、表单、工单
或外部服务。它保存 PAUSE 对应的 `executionId + taskRunId`，并通过 Flow Core
公开的 Execution Resume 提交结果，但不能改变 Execution、TaskRun 或选择下一
Task。
_Avoid_: PAUSE Task, embedded workflow domain, direct Execution mutation

**External Trigger**:
独立于启动 Execution 的 server 生命周期之外、为一个 PAUSE TaskRun 提交业务
结果的外部参与者。External Trigger 不持有原 server 的领域对象或内存状态，只能
使用持久化的 executionId 和 taskRunId 调用 Execution Resume。
_Avoid_: In-process test callback, direct Handler invocation

**Durable Resume**:
原 server 生命周期结束后，新 server 根据 executionId 和 taskRunId 从持久化状态
重建 Execution、TaskRun 和绑定的 Flow Reversion，再从原 PAUSE TaskRun 继续
推进到下一个稳定态或终态。
_Avoid_: In-memory resume, same-context continuation

**DAG Dependency**:
未来 DAG 流程类型拥有的运行前置关系。`dependOn` 不属于抽象 Task、Branch、Route 或
Condition；在 DAG 领域结构、可见范围和运行规则确认前，普通 Flow 定义不接受该字段。
_Avoid_: dependOn on every Task, implicit DAG, Condition dependency

**Flowing Context**:
当前串行 Branch 作用域内可供后续 Route 读取的只读 outputs 数据域，按已经完成的前序
直接子 Task key 隔离，例如 `outputs.prepare.result`。它不包含未来兄弟、并行兄弟、
其他循环轮次或全局可写状态。
_Avoid_: all Execution outputs, future sibling outputs, global variables

**DependOn Outputs**:
旧公共 Task dependOn 产生的输出域；当前模型不提供该数据域。未来 DAG 如需依赖输出，
必须在 DAG 决策中重新确认，不能通过恢复 Task.dependOn 隐式带回。
_Avoid_: current Task input, Merged Flowing Context

**Global Context**:
一次 Execution 共享的全局数据域；第一阶段只允许读取，不允许 Task 写入。
Flow Variable 是该共享读取语义中的流程定义来源，但不等同于某次 Execution 的可变运行状态。
_Avoid_: Writable shared variables

**Stable State**:
Execution 当前没有需要同步继续推进的自动工作、可以安全提交的运行事实组合，
例如全部未完成叶子 TaskRun 都是 PAUSED，或 Execution 已失败、取消、完成。
稳定点由 TaskRun 事实判断，不新增 Execution 等待状态。一个命令可以连续推进多个
同步 Task，直到到达下一稳定点；每次变更先调用领域方法，再保存完整快照。
Worker 回调不持有 Execution 业务事务。并行分支允许在整体 RUNNING 时恢复精确 PAUSED 节点。
_Avoid_: Cross-service transaction, SQL-driven state transition
