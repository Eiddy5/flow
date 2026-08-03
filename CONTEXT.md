# Flow Workflow

本上下文定义可发布、可运行并可恢复的工作流。它统一描述流程定义、一次运行及单个步骤运行事实之间的关系。

## Language

**Flow**:
由 FlowDraft 在部署时完成解析和校验后形成的完整工作流定义；同一逻辑 Flow 的各次部署共享稳定 `id`，每个 Flow 都有正式 `reversion`，并持有 inputs、outputs 和 Task 定义。Flow 不是草稿，也不保存某次 Execution 的运行 State。
_Avoid_: Raw Flow, Draft, Process

**Flow Draft**:
独立于 Flow 的唯一可编辑原始定义，只保存尚未解析的 YAML，并且没有正式 `reversion`；部署成功会从它映射出新的 Flow Reversion，但不会把它转换为 Flow。
_Avoid_: FlowWithSource, generic Draft, Draft status, Raw Flow, parsed Flow, Flow Version

**Flow Reversion**:
同一逻辑 Flow 的一次成功部署所形成的、具有正式 `reversion` 且可独立读取的不可变 Flow 快照。
_Avoid_: Flow Version, Revision, Draft, mutable Flow

**Current Flow Reversion**:
某个 Flow 同一 `id` 下最大的 reversion；只有该最大 reversion 尚未删除时，它才供新 Execution 启动绑定。最大 reversion 已删除时不存在可启动的当前 Flow，不能回退到旧 reversion。
_Avoid_: Current Flow Version, Only version, mutable version

**Flow Deletion**:
Flow 定义被用户删除后的不可用事实，由 `deleted=true` 表达；新建 FlowDraft 和新部署版本均从 `deleted=false` 开始。删除后不能编辑 FlowDraft、继续部署或启动新的 Execution，但已经启动的 Execution 继续绑定原 Flow Reversion。
_Avoid_: CLOSED status, FlowDefinitionStatus, physical deletion, version replacement

**Workflow Runtime State**:
由 Flow 定义域的 `State` 统一定义、贯穿一次工作流运行的状态语言；
State 对象以 `current` 表达当前状态，以 `history` 保存从创建开始的真实变化
轨迹。`State.Type` 统一包含 `CREATED/RUNNING/WAITING/COMPLETED/TERMINATED`，
分别归入创建和运行、等待、正常终止、异常终止四类。Execution、TaskRun 各自
持有完整 State 并限制自己的合法路线；RunnableTask 只报告完成或失败事实，
BranchTask 的等待和完成由 Executor 直接应用，全部历史仍由聚合产生。
PAUSE 使用 WAITING，审批、表单或工单仍拥有自己的外部业务状态。
_Avoid_: ExecutionStatus, TaskRunStatus, WorkerTaskOutcome, approval status

**Data**:
Flow 或 Task 中以唯一业务 `key` 标识、并通过 `type` 声明数据类型的数据定义；它是 Input 与 Output 的共同基础，但不保存某次运行产生的实际值。
_Avoid_: Runtime value, arbitrary Map, database column

**Data Type**:
Data 对实际值种类的稳定契约，由独立的有限类型 `DataType` 表达；同一个类型代码
在 Input 与 Output 中具有相同含义，并决定一个已提供运行值能否被该数据定义
接受。Data Type 不属于 Data 内部，也不表达输入输出方向、Task 类型或业务字段
用途。
_Avoid_: Data.Type, Task Type, Java class name, database column type, implicit String

**Input**:
实现 Data、由 Flow 或 Task 持有的抽象输入定义基类；它保存 `key`、
`displayName`、`required`、`defaultValue` 等公共描述，具体子类声明固定
Data Type 与自身约束并负责校验。实际输入值属于 TaskRun。
_Avoid_: Concrete Input, Input value, request DTO, untyped input Map

**Output**:
直接实现 Data、由 Flow 或 Task 持有的具体输出定义对象，以 `key` 和独立
Data Type 描述所属定义可以产生并交给下游的数据；实际输出值属于 TaskRun。
_Avoid_: Output interface, Output value, response DTO

**Task**:
Flow Reversion 中不可分割的流程步骤定义；其领域字段 `id` 跨 reversion 保持稳定，被引用时称为 `taskId`。Task 通过 `parentId` 表达定义父子关系，并声明输入、输出、路由、依赖及直接子 Task，但不保存实际运行结果；YAML 只声明业务 `key`，完整 Task 仅在成功部署时产生。
RunnableTask 与 BranchTask 是具体 Task 可拥有的两种互斥能力，离开 Task 后没有
独立业务意义；Worker 和 Executor 分别调用或解释这些能力，但不拥有它们。
_Avoid_: Node, Activity

**Runnable Task**:
继承 Task 并实现 RunnableTask 能力的具体步骤定义；它把实际工作写在
`run(RunContext)` 中，只有这种 Task 才能形成 WorkerTask 并交给 Worker。它不能
读取或改变 Execution、TaskRun、nexts 或分支编排状态。
_Avoid_: WorkerTaskHandler, executable BranchTask, generic Task execution

**Branch Task**:
继承 Task 并实现 BranchTask 能力的流程控制步骤定义，例如 PAUSE、PARALLEL 和
未来规则确认后的 LOOP。它没有实际工作和 `run` 方法，不形成 WorkerTask；
Executor 直接根据其编排特征管理 TaskRun、等待、并行展开和后续路线。
_Avoid_: Runnable branch, structural WorkerTask, WorkerTaskHandler

**Run Context**:
每次只服务一个 RunnableTask 调用的临时不可变上下文，只提供当前 Session、命令
DSLContext 和本次实际 inputs。它不包含 Task、WorkerTask、Execution、TaskRun、
taskRunId 或状态修改入口，也不持久化或跨 Task 复用。
_Avoid_: WorkerContext, Execution context, TaskRun snapshot, persisted context

**Ordered Task Children**:
普通 Task 完成后按定义顺序选择和运行的直接子 Task；前一个已选择子 Task 的完整
子树收敛后才能进入下一个，route 不匹配的定义不产生运行事实。它是普通编排的
默认语义，不表示同级分支同时运行。
_Avoid_: Implicit parallel branches, sibling batch, unordered children

**PARALLEL Task**:
显式声明多个直接子分支可以同时开始的结构 Task；它自身完成后，把所有 route
成立且依赖满足的直接子 Task 作为同一 Execution 内的并行分支运行，并等待全部
已选择分支收敛。并行不由普通同级 Task、Child Execution 或多个 DIRECT route
隐式推断。
_Avoid_: Implicit parallel, Parallel Execution, Child Execution, parallel flag

**Task Extension**:
为一个稳定 Task `type` 提供具体 Task 定义及其物化、重建和专有 properties 规则的
Plugin 扩展点；注册后 Flow 可以用该 `type` 部署 Task。运行能力由具体 Task 自身
实现 RunnableTask 或 BranchTask，不由 TaskExtension 执行。TaskExtension 不分配
Task 身份、不拥有 Flow 聚合或 TaskRun 状态。
_Avoid_: Task instance, WorkerTaskHandler, Task type catalog, generic Task

**Execution**:
Flow Reversion 被启动后形成的一次完整运行实例；它永久绑定启动时的 `flowId + flowReversion`，保存生命周期状态和有序 TaskRun 历史，并且可以同时拥有多个活动 TaskRun。Execution 不是移动游标，实际执行路径由 TaskRun 事实表达。
_Avoid_: Process, workflow instance

**Executor Scheduling Cycle**:
以一个 Execution 及其精确 Flow Reversion 为输入、从已有 TaskRun 事实重建下一
批工作并推进到下一个可提交点的一次可恢复调度循环。循环内的 ExecutorContext
只暂存 nexts、Runnable WorkerTask、Branch TaskRun、异常和状态变化增量；它不是持久化游标，也不持有
FlowDraft、Session 或 DSLContext。
_Avoid_: Execution cursor, transaction context, persisted next queue

**TaskRun**:
Execution 实际执行某个 Task 时产生的真实实例。Executor Scheduling Cycle 可以
先在 nexts 中构造 CREATED TaskRun，但只有 `onNexts` 经 Execution 聚合接受后
才成为真实历史。它通过 `taskId` 关联确定 Flow Reversion 中的 Task，通过
`parentId` 关联真实父 TaskRun，并保存本次执行的状态、输入、输出和错误。同一
Task 可以因循环等原因产生多个 TaskRun；其列表顺序表达真实运行顺序。
_Avoid_: Activity, Task instance

**PAUSE Task**:
由 Flow Core 提供的外部等待编排 Task；它开始运行后使对应 TaskRun 进入
`WAITING`，当没有其他可运行工作时 Execution 也进入 `WAITING`，直到 Flow Core
接受外部结果并恢复该 TaskRun。PAUSE Task 只定义流程在哪里等待以及结果契约，
不定义审批人、表单、工单或其他外部业务规则。
_Avoid_: Approval Task, User Task, Assignment, External Task

**Execution Resume**:
Flow Core 接受一个确定 PAUSE TaskRun 的外部结果、完成原 TaskRun 并继续同一
Execution 的生命周期动作；外部调用方只提供 `executionId + taskRunId` 和结果，
不能指定下一 Task、路由或目标状态。
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

**Task Dependency**:
Task 通过 `dependOn` 声明的运行前置关系；它引用同一 Flow Reversion 中的 Task key，并由同一 Execution 内已完成的 TaskRun 事实满足，不是一种独立 Task 类型。
_Avoid_: DependOn Task, Wait Task, join TaskRun per branch

**Flowing Context**:
随单条 Execution 线路流动的一跳数据域。A 完成后 A 的真实 outputs 交给 B；B 完成后，交给 C 的只剩 B 的真实 outputs，不累计整条线路历史输出。
_Avoid_: Accumulated Execution Context, global variables

**DependOn Outputs**:
声明 Task Dependency 的 Task 开始执行时可读取的依赖输出域，按依赖 Task key 隔离，例如 `dependOnOutputs.A.result`；同一依赖 Task 存在多次完成记录时使用最新的 `COMPLETED` TaskRun。该数据域不会自动传递给当前 Task 的下游。
_Avoid_: Merged Flowing Context

**Global Context**:
一次 Execution 共享的全局数据域；第一阶段只允许读取，不允许 Task 写入。
_Avoid_: Writable shared variables

**Stable State**:
Execution 当前没有需要同步继续推进的自动工作、可以安全提交的运行状态，例如停在 PAUSE、失败、取消或完成。一个命令可以连续推进多个同步 Task，直到到达下一稳定态，整个过程属于同一个数据库事务。
_Avoid_: One transaction per Task
