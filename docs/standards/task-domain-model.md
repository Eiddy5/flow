# Task 领域模型规范

## 适用范围与效力

本规范定义 Task 定义域的目标模型，包括 Task 的聚合角色、身份、字段、方法、
类型扩展、递归结构、路由、依赖以及与 TaskRun 的边界。

本规范落实
[`CONTEXT.md`](../../CONTEXT.md)、
[`ADR 0002`](../decisions/0002-workflow-core-runtime-class-design.md)、
[`ADR 0006`](../decisions/0006-single-execution-branch-routing-and-join.md)、
[`ADR 0008`](../decisions/0008-separate-flow-source-from-deployed-flow.md)、
[`ADR 0013`](../decisions/0013-centralize-yaml-parsing-and-flow-materialization.md)
、[`ADR 0014`](../decisions/0014-complete-flow-source-and-reversion-migration.md)、
[`ADR 0016`](../decisions/0016-separate-pause-from-external-business-capabilities.md)、
[`ADR 0021`](../decisions/0021-require-explicit-parallel-task.md)、
[`ADR 0023`](../decisions/0023-discover-task-extensions-with-service-loader.md)、
[`ADR 0024`](../decisions/0024-separate-runnable-and-branch-task-capabilities.md)
以及
[`domain-object-modeling.md`](domain-object-modeling.md)
已经确认的领域语义。Java、插件注册、YAML 物化和 PostgreSQL 重建链路已经完成
本模型迁移；不能以插件属性快照或数据库结构反向修改 Task 领域字段。

本领域统一使用 `Flow Reversion` 和字段名 `reversion`。旧文档或代码中的
`FlowVersion`、`FlowDefinition`、`version` 和“发布”，应分别迁移为
`Flow Reversion`、`Flow`、`reversion` 和“部署”。

## 定义与对象角色

Task 是一次 Flow Reversion 中不可分割的流程步骤定义。它描述“这个步骤是什么、
需要什么、产生什么、何时成为候选”，但不描述“某次运行执行到了哪里、产生了
什么结果”。

Task 的对象角色是 **Flow 聚合内实体**：

- Task 有跨 Flow Reversion 稳定的技术身份。
- Task 不能脱离 Flow 独立创建、保存、删除或变更。
- Task 没有独立 Repository、Service、审计字段、业务版本和生命周期状态。
- Task 只在 `Flow.deploy` 成功时随完整 Flow Reversion 一起产生。
- 已部署 Task 不可变；调整 Task 必须修改 `FlowDraft.raw` 并重新部署
  一个新的 Flow Reversion。
- Task 基类不统一执行自身。每个具体 Task 必须恰好实现 RunnableTask 或
  BranchTask：前者在 `run(RunContext)` 中保存具体工作并交给 Worker，后者没有
  实际工作并由 Executor 直接编排；TaskRun 记录每一次真实运行事实。
- RunnableTask 与 BranchTask 都是 Task 的能力，离开 Task 没有独立业务意义；
  两者及 RunnableTask 的直接调用契约 RunContext、RunResult 与 Task 一起归入
  `core/domains/tasks`。Worker 和 Executor 是能力调用方，不是能力所有者。

原始 YAML 中的 Task 节点以及 Parser 返回的 Task 节点映射都不是 Task。
映射只由 Flow 在一次完整创建或部署中消费，不形成新的领域类型。任何被 Flow
聚合持有的 Task 都必须已经获得稳定 `id`、正确 `parentId`，并通过类型、
输入输出、路由、依赖和递归结构校验。

## 领域类图

```mermaid
classDiagram
direction LR

class Flow {
    <<aggregateRoot>>
    -String id
    -long reversion
    -List~Task~ tasks
    +deploy(source, latest, actor, deployedAt) Flow$
    +findTask(taskId) Optional~Task~
    +allTasks() List~Task~
}

class Task {
    <<abstractEntity>>
    -String id
    -String parentId
    -String key
    -String type
    -List~Input<?>~ inputs
    -List~Output~ outputs
    -RouteExpression route
    -List~String~ dependOn
    -List~Task~ tasks
    +id() String
    +parentId() Optional~String~
    +key() String
    +type() String
    +inputs() List~Input<?>~
    +outputs() List~Output~
    +route() RouteExpression
    +dependOn() List~String~
    +tasks() List~Task~
    +isTopLevel() boolean
    +matchesRoute(parentOutputs) boolean
    +declaresOutput(outputName) boolean
    +validateOutputs(actualOutputs) Map
    +dependsOn(taskKey) boolean
    +findDescendant(taskId) Optional~Task~
    +allDescendants() List~Task~
}

class RunnableTask {
    <<taskCapability>>
    +run(context) RunResult
}

class BranchTask {
    <<taskCapability>>
    +waitsForResume() boolean
    +startsChildrenInParallel() boolean
}

class TaskTypeDispatcher {
    <<pluginRuntime>>
    +dispatch(id, parentId, key, type, inputs, outputs, route, dependOn, properties, children) Task
    +restore(...) Task
    +properties(task) Map
}

class PluginRegistry {
    <<extensionRegistry>>
    +find(extensionPoint, type) Optional~Plugin~
}

class Plugin {
    <<rootSPI>>
    +type() String
    +extensionPoint() Class
}

class TaskExtension {
    <<extensionSPI>>
    +create(id, parentId, key, inputs, outputs, route, dependOn, properties, children) Task
    +rehydrate(...) Task
    +properties(task) Map
}

class AutomaticTask {
    <<entitySubtype>>
    +create(id, parentId, key, inputs, outputs, route, dependOn, children) AutomaticTask$
    +run(context) RunResult
}

class PauseTask {
    <<entitySubtype>>
    +create(id, parentId, key, inputs, outputs, route, dependOn, children) PauseTask$
}

class ParallelTask {
    <<entitySubtype>>
    +create(id, parentId, key, inputs, outputs, route, dependOn, children) ParallelTask$
    +startsChildrenInParallel() boolean
}

class RouteExpression {
    <<valueObject>>
    -String source
    -String outputKey
    -String expectedValue
    +direct() RouteExpression$
    +parse(source) RouteExpression$
    +source() String
    +referencedOutputKey() Optional~String~
    +matches(parentOutputs) boolean
}

class Data {
    <<interface>>
    +getKey() String
    +getType() DataType
}

class DataType {
    <<enumeration>>
    STRING
    BOOLEAN
    BYTE
    SHORT
    INTEGER
    LONG
    FLOAT
    DOUBLE
    CHARACTER
}

class Input {
    <<abstractEntity>>
    -String key
    -String displayName
    -boolean required
    -T defaultValue
    +getKey() String
    +getType() DataType*
    +valid(value T) void*
}

class Output {
    <<entity>>
    -String key
    -DataType type
    +getKey() String
    +getType() DataType
}

class TaskRun {
    <<executionEntity>>
    -String id
    -String taskId
    -String parentId
    -State state
}

class State {
    <<flowDomainValueObject>>
    -Code code
}

Flow "1" *-- "0..*" Task : ordered top-level definitions
Flow ..> TaskTypeDispatcher : selects subtype
TaskTypeDispatcher ..> PluginRegistry : resolves TaskExtension
PluginRegistry o-- Plugin : extension point + normalized type
Plugin <|-- TaskExtension
TaskExtension ..> Task : calls subtype create or rehydrate
Task "1" *-- "0..*" Task : ordered direct children
Data <|.. Input
Data <|.. Output
Data --> DataType
Task *-- Input
Task *-- Output
Task *-- RouteExpression
Task <|-- AutomaticTask
Task <|-- PauseTask
Task <|-- ParallelTask
RunnableTask <|.. AutomaticTask
BranchTask <|.. PauseTask
BranchTask <|.. ParallelTask
TaskRun ..> Task : references by taskId
TaskRun --> State
```

`AutomaticTask`、`PauseTask` 和 `ParallelTask` 是当前内置类型，不表示 Task
类型集合被封闭。新的 Task 类型通过新的 Task 子类型、该子类型自身的静态
`create(...)`、一个声明稳定 `type` 的 `TaskExtension`，并由具体 Task 恰好实现
RunnableTask 或 BranchTask；注册表自动收集插件，不修改中心枚举、中心 `switch`，
不引入领域 Factory，也不向 `Task` 增加无类型约束的字段扩展。

内置 TaskExtension 作为 Micronaut Bean 装配；普通外部插件 JAR 通过 classpath
`ServiceLoader<Plugin>` 提供 TaskExtension。RunnableTask 的执行逻辑位于具体
Task 类的 `run` 方法，BranchTask 由 Executor 处理，不再注册或发现
WorkerTaskHandler。YAML `type` 不承担实现类名、JAR 路径或 ClassLoader 选择。

通用 `Plugin` SPI、`PluginLoader`、`PluginRegistry`、Task 扩展点
`TaskExtension`、`TaskTypeDispatcher` 与 `RegisteredTaskTypeDispatcher` 属于
宿主插件运行机制，统一位于 `core/plugins`；
`extensions/tasks` 保存具体 Task 与 TaskExtension；不再建立
`extensions/workers` 执行 Adapter。
Flow 只消费 `core/plugins` 暴露的内部运行门面，不在 Task 领域中声明插件
Interface 或保存注册、发现逻辑。

PauseTask 是 Flow Core 提供的外部等待编排 Task。它只定义流程在哪里等待以及
等待结果的输入输出契约，不是审批 Task、用户待办、表单或工单。PAUSE 对应的
TaskRun、Execution Resume 和外部业务能力边界统一由
[`pause-domain-model.md`](pause-domain-model.md) 规定。PauseTask 实现 BranchTask，
其 WAITING 转换由 Executor 直接完成，不进入 Worker。

ParallelTask 是显式并行结构。普通 Task 的直接子 Task 按定义顺序串行，只有
ParallelTask 的 route 成立直接子 Task 才能在同一调度批次中成为并行分支。
ParallelTask 本身仍是完整 Task，产生真实 TaskRun；它不创建 Child Execution，
也不把并行标志扩散到所有 Task 类型。

图中的 `Input<T>` 是抽象输入定义，具体 Input 子类固定独立 DataType 并实现
校验；Output 是直接实现 Data 的具体输出定义。它们作为所属 Task 聚合内对象
存在，不是运行值。完整规则见
[`data-domain-model.md`](data-domain-model.md)。独立 DataType 与具体 Input
类型体系由 Accepted ADR 0019 定义；实现前不得用 YAML Map、数据库 Record 或
调用方各自的类型 switch 替代目标领域对象。

## 字段

| 字段 | 含义 | 规则 |
| --- | --- | --- |
| `id` | Task 的稳定技术身份 | 非空；首次成功部署该业务 Task 时生成，相同逻辑 Task 的后续 reversion 复用 |
| `parentId` | 定义层直接父 Task 的稳定 `id` | 顶层 Task 为空；子 Task 必须等于其直接父 Task 的 `id` |
| `key` | 原始定义中声明的业务标识 | 非空；在整个 Flow Reversion 的递归 Task 树中唯一 |
| `type` | Task 类型代码 | 非空；必须唯一映射到具体 Task 子类型；该子类型恰好实现一种运行能力 |
| `inputs` | Task 的输入契约 | `List<Input<?>>`；没有输入时为空只读集合 |
| `outputs` | Task 的输出契约 | `List<Output>`；没有输出时为空只读集合 |
| `route` | 当前 Task 成为候选时的路由条件 | 非空值对象；未声明时规范化为 `DIRECT` |
| `dependOn` | 运行前必须完成的 Task key | 结构化只读字符串列表；未声明时为空 |
| `tasks` | 当前 Task 完成后的有序直接子 Task | 只读集合；默认按顺序推进，Parallel BranchTask 将匹配项作为并行分支；RunnableTask 不在内部执行它们 |

`id` 是领域字段名。TaskRun、API 引用和持久化外键使用 `taskId` 表达“所引用
Task 的 id”，两者是同一身份，不再为 Task 增加另一个 `taskId` 字段。

以下内容不属于 Task：

- `reversion`：由所属 Flow Reversion 统一拥有。
- `status`、实际 inputs、实际 outputs、错误、开始时间和完成时间：属于
  TaskRun。
- creator、updater、deleter 和审计时间：由所属 Flow 统一拥有。
- 原始 YAML：只属于 FlowDraft。
- 通用 `Map<String, Object> properties`：无法保护具体 Task 类型的不变量。
  类型扩展字段必须由具体 Task 子类型使用明确字段或值对象表达。

`dependOn` 是所有 Task 都能被统一读取和校验的编排字段，不再隐藏在
`properties` 中。某种 Task 类型是否允许声明依赖，由该具体 Task 子类型的
`create(...)` 和部署校验共同限制。

## 方法

### 唯一创建入口

抽象 `Task` 不提供可实例化的公共 `Task.create(...)`；每个具体 Task 子类型
必须提供自身的 `public static create(...)`。Task 只能在 Flow 聚合编排的以下
链路中创建：

1. `YamlParser` 将 `FlowDraft.raw` 转换为通用只读映射。
2. Flow 直接递归解释映射中的 Task 节点，检查 key、类型、输入输出、路由、
   依赖和递归结构。
3. Flow 按上一 Flow Reversion 的 Task key 复用或生成稳定 `id`，并计算每个
   子 Task 的 `parentId`。
4. Flow 调用 `TaskTypeDispatcher`；分派器按规范化后的 `type` 从
   `PluginRegistry` 的 `TaskExtension` 扩展点解析唯一插件。
5. 插件直接调用对应具体 Task 子类型的静态 `create(...)`，例如
   `AutomaticTask.create(...)` 或 `PauseTask.create(...)`。
6. 全部 Task 构造和校验成功后，`Flow.deploy` 原子生成新的 Flow Reversion。

```mermaid
flowchart LR
    source["FlowDraft.raw"] --> parser["YamlParser"]
    parser --> mapping["通用只读映射"]
    mapping --> identity["Flow 解释字段并生成身份<br/>id + parentId"]
    identity --> dispatch["TaskTypeDispatcher"]
    dispatch --> registry["PluginRegistry<br/>TaskExtension + type"]
    registry --> plugin["TaskExtension"]
    plugin --> create["具体 Task.create(...)"]
    create --> task["完整且不可变的 Task"]
    task --> deploy["Flow.deploy"]
```

`TaskTypeDispatcher` 是 `core/plugins` 提供给 Flow 的内部运行门面；它自身不
包含具体类型分支，只通过 `PluginRegistry` 的 TaskExtension 扩展点解析插件。每个
`TaskExtension` 只声明一个稳定类型并调用该具体 Task 子类型的创建或重建入口，
不生成身份、不解析 YAML，也不拥有 Flow 聚合规则。具体 Task 构造方法必须为
`private` 或 `protected`，
领域类型外部不得直接 `new`，也不得新增 `TaskDefinitionFactory` 等工厂接口
包装创建。

解析、校验、身份协调或 Task 构造任一步失败，都不得产生 Flow Reversion、不得
占用 `reversion`，也不得改变 FlowDraft。

### 业务查询方法

| 方法 | 用途 | 规则 |
| --- | --- | --- |
| `id()` | 读取稳定 Task 身份 | 永不返回空值 |
| `parentId()` | 读取定义层直接父 Task 身份 | 顶层返回空 Optional |
| `key()` | 读取业务标识 | 不能代替 `id` 作为持久化身份 |
| `type()` | 读取扩展类型代码 | 与实际 Task 子类型一致 |
| `inputs()` | 读取输入契约 | 返回只读集合 |
| `outputs()` | 读取输出契约 | 返回只读集合 |
| `route()` | 读取已解析路由值对象 | 不返回原始未校验字符串 |
| `dependOn()` | 读取依赖 Task key | 返回只读集合 |
| `tasks()` | 读取有序直接子 Task | 不递归；具体调度由 Executor 与 BranchTask 能力决定 |
| `isTopLevel()` | 判断是否位于 `Flow.tasks` | 只由 `parentId` 是否为空推导 |
| `matchesRoute(parentOutputs)` | 判断直接父 TaskRun 的 outputs 是否满足路由 | `DIRECT` 恒为 true；不读取其他 Execution |
| `declaresOutput(outputKey)` | 按 `Output.getKey()` 判断本 Task 是否声明某输出 | 供部署期路由引用校验 |
| `validateOutputs(actualOutputs)` | 校验并规范化一次运行结果 | 必须提交结果对象；允许声明字段缺省，拒绝未声明 key、null 和类型错误；成功返回深度不可变的规范化 Map |
| `dependsOn(taskKey)` | 判断是否声明某依赖 | 只查询定义，不读取 TaskRun |
| `findDescendant(taskId)` | 在当前 Task 子树中按稳定 id 查询后代 | 不包含当前 Task 本身 |
| `allDescendants()` | 展平当前 Task 的全部后代 | 保持定义遍历顺序，返回只读集合 |

跨 Task 的规则不放进单个 Task：

- 全局 key 唯一、父子一致、依赖存在性和依赖无环由 `Flow.deploy` 校验。
- 下一批可运行 Task 的计算由 Executor 根据完整 Flow 和 TaskRun 事实完成。
- TaskRun 的等待、完成、失败和取消不属于 Task 方法。RunnableTask 只通过
  `run(RunContext)` 返回工作事实，BranchTask 不提供执行方法。
- 加载、保存和事务不属于 Task 方法。

目标模型不提供 `identify`、`setParentId`、`setRoute`、`setStatus`、
`execute`、`complete` 或 `copy` 等公共方法。Task 在被创建时就必须具有完整
身份；不可先创建 `id = null` 的领域 Task，再通过 `identify` 补全。

## 身份与跨 Reversion 规则

Task 的稳定身份规则如下：

- YAML 只声明 `key`，不允许调用方声明或覆盖 `id`。
- 某个 `key` 第一次成功部署时生成新的 Task `id`。
- 同一逻辑 Flow 后续成功部署时，相同 `key` 复用已有 Task `id`。
- 新增 `key` 生成新 `id`；删除 Task 不会删除旧 Flow Reversion 中的历史定义。
- Task 在树中移动时，只要 `key` 未变，`id` 保持不变，新的 `parentId` 反映新的
  直接父子关系。
- 一个 Task 快照由 `flowId + flowReversion + taskId` 精确定位。

系统当前无法仅凭两份 YAML 区分“Task 重命名”和“删除旧 Task 后新增新 Task”。
因此不同 `key` 按新身份处理；若未来需要重命名后保持 `id`，必须新增显式身份
迁移协议和 ADR，不能用模糊匹配猜测。

### 持久化重建与快照相等

Repository 重建同一个 Flow Reversion 时，Task 必须恢复为与保存前相同的不可变
定义快照。用于快照比较的相等语义递归包含 `id`、`parentId`、`key`、`type`、
inputs、outputs、route、类型属性和直接子 Task；不能依赖 Java 对象引用相同。

该相等语义用于判断“两个实例是否表达同一份 Task 定义快照”，不能替代
`flowId + flowReversion + taskId` 的持久化定位规则，也不能用于判断两个不同
Reversion 中的 Task 是否具有同一稳定身份。

## 递归结构、顺序与路由

`Flow.tasks` 与 `Task.tasks` 的集合语义不同：

- `Flow.tasks` 是顶层 Task 的有序定义，第一阶段按顺序推进；前一个顶层 Task 的
  已选择子树收敛后，才能进入下一个顶层 Task。
- 普通 `Task.tasks` 是父 Task 完成后的有序后续步骤。所有 route 成立的直接
  子 Task 按声明顺序串行；前一个已选择子树收敛后才判断下一个。
- `ParallelTask.tasks` 是显式并行分支。所有 route 成立且依赖满足的直接
  子 Task 进入同一个有序可运行批次。
- 没有子 Task 匹配时，不创建虚构 TaskRun，也不使用 `SKIPPED`；该子树正常
  收敛。
- 每层 Task 都是独立原子 Task，父 Task 的 Worker 不在内部代替子 Task 执行。

第一阶段 RouteExpression 只允许：

```text
DIRECT
outputs.<field> == "<value>"
```

- `DIRECT` 恒为 true。
- 条件表达式只读取直接父 TaskRun 的实际 outputs。
- 字符串比较区分大小写并精确匹配。
- 路由语法及引用输出必须在部署前校验。
- 多个 route 同时成立时全部执行；普通 Task 下按定义顺序执行，ParallelTask
  下并行执行。若未来需要单选，必须新增显式网关语义。

## dependOn 规则

- `dependOn` 中每个值引用同一 Flow Reversion 内唯一的 `Task.key`。
- 部署时必须校验依赖存在、不得依赖自身，并且整个依赖图无环。
- Task 只有在同一 Execution 内全部依赖 TaskRun 都为 `COMPLETED` 时才可运行。
- `dependOn` 描述依赖完成条件，不改变 Task 的定义父子关系。
- `Task.parentId` 不是前驱、依赖或调度原因。
- 依赖是否满足由 Executor 根据 TaskRun 事实判断；Task 只保存和查询依赖定义。
- 同一非循环 Task 在一次 Execution 中最多产生一个 TaskRun。

## Task 与 TaskRun 的边界

| 维度 | Task | TaskRun |
| --- | --- | --- |
| 业务含义 | 一次 Flow Reversion 中的步骤定义 | 某次 Execution 对该步骤的真实运行实例 |
| 所属聚合 | Flow | Execution |
| 精确身份 | `flowId + flowReversion + taskId` | `executionId + taskRunId` |
| `parentId` | 直接父 Task 的稳定 `id` | 本次运行中直接父 TaskRun 的 `id` |
| 状态 | 无 | 持有统一 State：CREATED、RUNNING、WAITING、COMPLETED、TERMINATED，并保存历史 |
| inputs/outputs | 输入输出契约 | 本次运行的实际输入输出 |
| route/dependOn | 保存定义 | 不复制定义，只用于调度计算 |
| 可变性 | 部署后不可变 | 通过 Execution 聚合发生合法状态转换 |
| 多次出现 | 每个 Flow Reversion 一个定义快照 | 同一 Task 未来可因 Loop 等产生多次运行事实 |
| Repository | 无独立 Repository | 由 ExecutionRepository 随聚合保存 |

TaskRun 通过 `taskId` 引用 Task，但 Task 不反向持有 TaskRun。定义域不能读取某个
Execution 的运行事实来改变已部署 Task。

## 领域不变量

- `TASK-001`：Task 只能存在于一个确定的 Flow Reversion 中，并随该 Flow
  Reversion 保持不可变。
- `TASK-002`：进入领域的 Task 必须拥有非空稳定 `id`，不存在可持久化的
  “未识别 Task”。
- `TASK-003`：`key` 在整个 Flow Reversion 的递归 Task 树中唯一。
- `TASK-004`：顶层 Task 的 `parentId` 为空；任一子 Task 的 `parentId` 必须
  等于其直接父 Task 的 `id`。
- `TASK-005`：Task 递归父子结构无环，同一个 Task 不能同时出现在多个父节点下。
- `TASK-006`：规范化后的 `type` 必须唯一映射到一个 TaskExtension、一个具体
  Task 子类型及其静态 `create(...)`；该子类型必须恰好实现 RunnableTask 或
  BranchTask。
- `TASK-007`：inputs、outputs、dependOn 和 tasks 永不为 null，并且对外只读；
  每个 Input、Output 都满足 Data 的 key、type 契约，同方向集合内 key 唯一。
- `TASK-008`：route 永不为 null；缺省值是 `DIRECT`，条件路由只引用直接父
  Task 声明的 STRING Output key。
- `TASK-009`：dependOn 引用存在、非自身且无环。
- `TASK-010`：`Task.tasks` 保持直接子 Task 的定义顺序；普通 Task 的已选择
  子树按顺序收敛，ParallelTask 的已选择直接子 Task 才能同批运行；RunnableTask
  不执行子 Task。
- `TASK-011`：Task 不保存任何一次运行的状态、实际值、错误或时间。
- `TASK-012`：通用编排字段不能隐藏在类型扩展 Map 中；类型专有规则由具体
  Task 子类型保护。
- `TASK-013`：相同 `key` 跨 Flow Reversion 复用 `id`；不同 `key` 默认产生
  不同身份。
- `TASK-014`：并行展开查询只属于 BranchTask；ParallelTask 返回 true，Executor
  不根据同级数量或 route 数量推断并行，Task 基类和 RunnableTask 不暴露该方法。
- `TASK-015`：每个被调度的具体 Task 恰好实现 RunnableTask 或 BranchTask；
  RunnableTask 才能形成 WorkerTask，BranchTask 只能由 Executor 直接处理。

### Task Extension 注册不变量

- `TPLUGIN-001`：每个 TaskExtension 只声明一个非空稳定 `type`；注册键使用
  “扩展点 Interface + `trim + Locale.ROOT` 大写 type”。
- `TPLUGIN-002`：同一扩展点内规范化后相同的两个 `type` 属于重复注册，应用
  装配必须失败，不能依赖 Bean 顺序覆盖；不同扩展点可以复用相同 type。
- `TPLUGIN-003`：新增 TaskExtension 不要求修改 Flow、YamlParser、
  TaskTypeDispatcher 的类型分支、中心枚举或注册清单。
- `TPLUGIN-004`：定义中的未知类型必须使本次物化原子失败；持久化快照引用未
  安装的类型时必须使重建失败，不能回退成通用 Task 或其他类型。
- `TPLUGIN-005`：插件必须原样保留 Flow 已决定的 `id`、`parentId`、`key` 和
  通用字段，并返回与注册 `type` 一致的具体 Task。
- `TPLUGIN-006`：classpath 外部插件必须在 `Plugin` 服务文件中注册实现
  TaskExtension 的提供者；具体 Task 自身同时提供 RunnableTask 或 BranchTask
  能力，不再使用第二个 WorkerTaskHandler 服务文件。
- `TPLUGIN-007`：ServiceLoader 提供者在启动时一次发现，注册完成后集合只读；
  YAML 不能指定实现类、JAR 路径或触发运行时类加载。

### Task 节点映射不变量

- `TMAP-001`：Task 节点映射没有 `id`、`parentId`、状态、审计、Repository
  或持久化语义。
- `TMAP-002`：只有 Flow 可以解释 Task 节点映射，并在创建 Task 前决定稳定
  `id` 与直接 `parentId`。
- `TMAP-003`：TaskTypeDispatcher 位于 `core/plugins`，不解析 YAML、不生成
  身份，只通过注册表选择 TaskExtension；扩展只调用具体 Task 子类型的静态创建或
  重建入口。
- `TMAP-004`：Task 节点映射不会形成、保存或恢复为独立领域类型。

## 场景校验

- 正向：递归 Task 节点映射被 Flow 转换为正确的具体 Task 子类型，顶层
  parentId 为空，子 Task parentId 等于直接父 Task id。
- 正向：普通 Task 下两个匹配子 Task 按定义顺序运行，第一棵子树未收敛时第二
  个不产生 TaskRun；ParallelTask 下两个匹配分支形成同一批次。
- 反向：空 key/type、不支持的类型、错误路由、重复 key 或非法依赖被拒绝，且
  不留下部分 Task 树。
- 注册：两个大小写或空白形式不同但规范化结果相同的插件 type 被视为重复并使
  装配失败；缺失插件的持久化 Task 无法重建。
- 扩展：测试插件只需通过 Bean 或 classpath ServiceLoader 注册新的
  TaskExtension，并让具体 Task 实现一种运行能力，即可从 YAML 物化并运行，无需
  注册执行 Handler。
- 变异：若分派器生成 Task id、Parser 直接创建 Task 或 Task 允许空 id，领域和
  架构测试必须失败。
- 身份：相同 key 跨定义转换复用 id；新增 key 生成 id，树中移动只改变
  parentId。
- 版本：Task 不拥有 reversion；转换失败不占用所属 Flow 的 reversion。
- 恢复：Repository 使用相同 id、parentId 和具体类型重建完整 Task，不恢复
  Parser 映射。
- 并发：同一 Flow 的并发部署由 Flow/Repository 版本约束保护，不能产生两个
  相同 reversion 的不同 Task 身份映射。

## 尚待业务规则确认

- Input 定义子类由 PAAS JSON 按 `type` 多态实例化；运行值的
  缺失/null/default 和数值绑定边界仍以 `data-domain-model.md` 的待确认项为准，
  不由 Task 解析映射自行决定。

## 现有实现迁移差距

本轮确认的 Task 定义链路差距已经关闭：

- `FlowDraft` 只保存原始 YAML，Task 只在 `Flow.deploy` 中递归物化。
- Task、TaskExtension、YAML 节点物化与持久化重建统一使用
  `List<Input>`、`List<Output>`；目标签名仍需迁移为
  `List<Input<?>>`、`List<Output>`。
- `dependOn` 已是 Task 的明确只读字段；`route()` 返回
  `RouteExpression`，运行判断统一使用 `matchesRoute(...)`。
- Task 基类不保存通用 properties。`Map<String, ?> properties` 只存在于插件
  创建/重建协议和 PostgreSQL 快照边界，具体插件必须把它转成具体 Task 子类型的
  明确字段，并在回写时显式编码。
- `PluginRegistry` 按“扩展点 Interface + 规范化 type”自动收集 Micronaut Bean
  和 classpath `ServiceLoader<Plugin>` 提供者，拒绝同一扩展点内重复注册；
  WorkerDispatcher 直接调用 RunnableTask.run，不再发现外部 Handler；Task 类型
  插件运行机制集中到 `core/plugins`，`extensions/tasks` 只保留具体实现。
- `FlowDefinition`、旧 version 和 publish 生产契约已经移除。

ADR 0024 新确认的差距由本轮实现关闭：

- AutomaticTask 实现 RunnableTask，并把实际逻辑收口到 `run(RunContext)`。
- PauseTask 与 ParallelTask 实现 BranchTask，由 Executor 直接处理。
- 删除 WorkerTaskHandler 发现链路和 `extensions/workers`。

独立 DataType、九个包装基础类型对应的具体 Input 子类和分阶段实施已经由
Accepted ADR 0019 确认，尚未修改现有 `String type`、原始 Input 签名和
`validateOutputs(...)` 实现。该决策不改变通用 Plugin 注册机制；实施时需要
同步 Flow/Task 定义、持久化、Demo 元数据、Worker/Resume 类型检查和测试。

## 相关文档

- [`flow-definition-lifecycle.md`](flow-definition-lifecycle.md)：Task 所属 Flow
  聚合、部署和 reversion 规则。
- [`data-domain-model.md`](data-domain-model.md)：Data 基础接口以及
  Input、Output 的身份和方向角色。
- [`execution-domain-model.md`](execution-domain-model.md)：TaskRun 如何引用
  Task，以及 Execution 中的真实运行历史。
- [`pause-domain-model.md`](pause-domain-model.md)：PauseTask、Execution Resume
  与外部业务能力边界。
- [`workflow-core-java-model.md`](workflow-core-java-model.md)：Core 分包、
  Executor 和 Worker 边界。
- [`task-plugin-classpath.md`](../harness/task-plugin-classpath.md)：普通 Java
  插件 JAR 的 classpath SPI 接入与验证步骤。
- [`UC-05 用户处理并行外派任务.md`](../uc/flow/UC-05%20用户处理并行外派任务.md)：
  dependOn 和并行运行场景。
- [`UC-06 用户提交外派结果后的条件路径.md`](../uc/flow/UC-06%20用户提交外派结果后的条件路径.md)：
  RouteExpression 场景。
- [`UC-07 用户处理多阶段外派流程.md`](../uc/flow/UC-07%20用户处理多阶段外派流程.md)：
  递归 Task 与 parentId 场景。
