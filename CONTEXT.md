# Flow Workflow

本上下文定义可发布、可运行并可恢复的工作流。它统一描述流程定义、一次运行及单个步骤运行事实之间的关系。

## Language

**Flow**:
由 FlowWithSource 在部署时完成解析和校验后形成的完整工作流定义；同一逻辑 Flow 的各次部署共享稳定 `id`，每个 Flow 都有正式 `reversion`，并持有 inputs、outputs 和 Task 定义，但不保存运行状态。
_Avoid_: Raw Flow, Draft, Process

**FlowWithSource**:
独立于 Flow 的唯一可编辑 Draft，只保存尚未解析的原始 YAML，并且没有正式 `reversion`；只有部署成功时，它才会被映射成 Flow。
_Avoid_: Raw Flow, parsed Flow, Flow Version

**Flow Reversion**:
同一逻辑 Flow 的一次成功部署所形成的、具有正式 `reversion` 且可独立读取的不可变 Flow 快照。
_Avoid_: Flow Version, Revision, Draft, mutable Flow

**Current Flow Reversion**:
某个 Flow 最新成功部署、供新 Execution 启动时绑定的唯一 reversion；旧 reversion 继续保留，但不再是当前 reversion。
_Avoid_: Current Flow Version, Only version, mutable version

**Draft**:
FlowWithSource 作为可编辑来源时承担的生命周期角色；它只包含原始 YAML，不是 Flow，也没有正式 `reversion`。
_Avoid_: Flow status, Version 0, unpublished Flow Reversion

**Data**:
Flow 或 Task 中以唯一业务 `key` 标识、并通过 `type` 声明数据类型的数据定义；它是 Input 与 Output 的共同基础，但不保存某次运行产生的实际值。
_Avoid_: Runtime value, arbitrary Map, database column

**Input**:
直接实现 Data、由 Flow 或 Task 持有的具体输入定义对象，以 `key` 和 `type` 描述所属定义可以接受的数据；实际输入值属于 TaskRun。
_Avoid_: Input interface, Input value, request DTO

**Output**:
直接实现 Data、由 Flow 或 Task 持有的具体输出定义对象，以 `key` 和 `type` 描述所属定义可以产生并交给下游的数据；实际输出值属于 TaskRun。
_Avoid_: Output interface, Output value, response DTO

**Task**:
Flow Reversion 中不可分割的流程步骤定义；其领域字段 `id` 跨 reversion 保持稳定，被引用时称为 `taskId`。Task 通过 `parentId` 表达定义父子关系，并声明输入、输出、路由、依赖及直接子 Task，但不保存实际运行结果；YAML 只声明业务 `key`，完整 Task 仅在成功部署时产生。
_Avoid_: Node, Activity

**Task Plugin**:
为一个稳定 Task `type` 提供具体 Task 定义及其物化规则的可扩展能力；注册后，Flow 可以用该 `type` 部署 Task，并由匹配的执行能力处理。Task Plugin 不分配 Task 身份、不拥有 Flow 聚合或 TaskRun 状态。
_Avoid_: Task instance, Task type catalog, generic Task

**Execution**:
Flow Reversion 被启动后形成的一次完整运行实例；它永久绑定启动时的 `flowId + flowReversion`，保存生命周期状态和有序 TaskRun 历史，并且可以同时拥有多个活动 TaskRun。Execution 不是移动游标，实际执行路径由 TaskRun 事实表达。
_Avoid_: Process, workflow instance

**TaskRun**:
Execution 实际执行某个 Task 时产生的真实实例。它通过 `taskId` 关联确定 Flow Reversion 中的 Task，通过 `parentId` 关联真实父 TaskRun，并保存本次执行的状态、输入、输出和错误。同一 Task 可以因循环等原因产生多个 TaskRun；其列表顺序表达真实运行顺序。
_Avoid_: Activity, Task instance

**External Task**:
PAUSE TaskRun 创建的外部恢复触发记录。它可以代表用户操作、服务回调、定时器或信号；等待期间 External Task 为 `WAITING`，Execution 和 TaskRun 仍为 `RUNNING`。
_Avoid_: User Task definition, signal

**External Trigger**:
独立于启动 Execution 的 server 生命周期之外、为一个 External Task 提交业务结果
的外部参与者。External Trigger 不持有原 server 的领域对象或内存状态，只能使用
持久化身份触发恢复。
_Avoid_: In-process test callback, direct Handler invocation

**Durable Resume**:
原 server 生命周期结束后，新 server 根据 External Task 身份从持久化状态重建
External Task、Execution、TaskRun 和绑定的 Flow Reversion，再从原 PAUSE
TaskRun 继续推进到下一个稳定态或终态。
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
