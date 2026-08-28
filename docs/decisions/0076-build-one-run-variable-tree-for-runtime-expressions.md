# ADR 0076：构建统一运行变量树供 RunContext 与 Condition 使用

## 状态

已接受。

本决策取代 ADR 0045 中 `$flow.*` 保留键、RunContext 保存 Session 以及直接携带
Execution 对象的运行变量协议；修订 ADR 0055 中 `variables.<key>` 和
`$flow.variables` 的读取名称、ADR 0058 中 Worker 继续传递 Session 的部分，以及
ADR 0074 中 Condition 只接受固定 scope 和 Operand 保存 `scope + path` 的部分。
同时修订 ADR 0001、0006、0021、0029 与 0074 中“Route 条件不成立时不创建
Route TaskRun”的历史调度规则。

## 背景

RunnableTask 模板渲染、Route 条件和 Loop Until 条件都需要读取运行事实。此前这些
入口分别装配 `$flow.*` 保留键或独立的 variables、inputs、outputs Map，导致同一个
表达式路径在不同消费者中可能得到不同结果。RunContext 同时承担变量装配、Session
传递和运行身份保存，也使构建职责与 Task 调用职责混在一起。

运行变量还需要表达当前 Task、当前 TaskRun、Execution 元数据、直接父级和完整祖先
链路。继续向 RunContext 增加字段会重复变量树中已经存在的身份，并扩大 RunnableTask
接口。

## 备选方案

### 方案一：继续扩展 RunContext 字段和 `$flow.*` 保留键

改动局部，但每新增一种运行事实都要修改 RunContext、Worker 投递和表达式消费者，
Condition 仍然维护另一套 scope 到 Map 的映射。

### 方案二：让各表达式消费者按需构造自己的 Map

每个消费者可以只暴露自身需要的值，但变量命名、父级顺序、Task key 分组和缺值行为
容易分叉，无法形成一个稳定的表达式协议。

### 方案三：由 RunVariables 投影统一变量树

RunVariables Builder 只接收 Flow、Execution、Task、TaskRun 四个领域事实根，通过
解析这些对象生成深度不可变的规范变量树。RunContext、ConditionContext 和 Worker
都消费这棵树，不再各自解释运行事实，也不能向 Builder 塞入已经投影好的 Map 或
独立身份。

## 决策

采用方案三。

### 构建职责

- `RunVariables.builder()` 是运行事实到表达式变量的唯一规范投影入口。
- Builder 只接收 Flow、Execution、当前 Task 和当前 TaskRun；不接收 inputs、Flow
  variables、Task outputs、Execution outputs 等已经可直接使用的 Map，也不接收
  executionId、taskRunId 或 parentTaskRunId 等可从领域对象解析的独立身份。
- Builder 校验 Flow 与 Execution 版本、Task 所属 Flow、TaskRun 所属 Task/Execution
  的一致性。直接父级只从当前 `TaskRun.parentId` 开始沿 Execution 中的 TaskRun
  记录解析，不存在第二个父身份来源。
- `build()` 按运行事实所有权组织：Flow、Task 与 TaskRun 元数据分别由对应的
  `of(...)` 投影；当 Execution 存在时，从中统一解析 execution、inputs、全部已完成
  TaskRun outputs 以及当前 TaskRun 的 parents/parent。调用方不拆出这些值再传入。
- Flow variables 仍从精确 Flow Reversion 解析；当前 Execution 只保存 Flow 引用而不
  保存变量定义。当前 Task 与 TaskRun 也保留为显式上下文根，不能在并行执行中用
  Execution 的最后一条 TaskRun 猜测。
- 构建结果是深度不可变的 Map/List 快照，不放入 Flow、Execution、Task 或 TaskRun
  领域对象，表达式不能借此修改运行状态。
- `RunContext` 使用 Lombok Builder 构建，只保存一个 `variables` 字段。它不保存
  Session、executionId、taskRunId、parentTaskRunId 或 parentId；`taskRunInfo()` 从
  `execution`、`task`、`taskRun` 路径聚合当前运行身份和 TaskRun outputs，
  `flowInfo()` 从 `flow` 路径聚合 Flow 元数据。调用方不再分别通过
  `executionId()`、`taskRunId()` 读取身份；直接父级仍由 `parentTaskRunId()` 从
  `parent.taskRun.id` 派生。

### 顶层变量协议

| 顶层字段 | 含义 | 示例路径 |
| --- | --- | --- |
| `flow` | 当前 Flow 的 id、key，以及存在时的 companyId 与 version | `flow.id`、`flow.key`、`flow.version` |
| `inputs` | 当前 Execution 按精确 Flow Reversion 规范化的 Flow inputs | `inputs.orderId` |
| `outputs` | Execution 中所有已成功或警告完成的 Task outputs，以 Task 业务 `key` 分组 | `outputs.prepare.result` |
| `vars` | 当前精确 Flow Reversion 定义的流程变量 | `vars.environment` |
| `task` | 当前 Task 的 `key` 与 `type` | `task.key`、`task.type` |
| `taskRun` | 当前 TaskRun 的 id、state、inputs、outputs 和可选 iteration | `taskRun.id` |
| `execution` | Execution 的 id、flowKey、flowVersion、state 与最终 outputs | `execution.id`、`execution.outputs.result` |
| `parent` | 最近一个直接父级的 Task 与 TaskRun 元数据；无父级时不插入 | `parent.taskRun.id` |
| `parents` | 从最近父级到最远祖先的完整链路；无父级时为空 List | 后续列表路径能力使用 |

`parent` 等于 `parents` 的第一项。每个父级项固定为
`{task: {key, type}, taskRun: {...}}`。Task 的业务主键是 `key`；`outputs` 不使用内部
Task id 分组。相同 Task 在循环中出现多个已完成 TaskRun 时，使用 Execution 历史中
最后一个成功或警告 occurrence 的 outputs。Route、Loop Until、Worker 和模板都使用
这一解析规则，不允许通过 Builder 临时覆盖 outputs 或建立另一套变量结构。

Flow、Task、TaskRun 与 Execution 的字段投影由 `RunVariables` 外层对应的静态
`of(...)` 重载负责，Builder 只负责组合。顶层变量使用
`ImmutableMap.Builder<String, Object>` 组装；`of(Flow)` 先写入必填 id 与 key，version、
companyId 等可空值通过 `Optional.ofNullable(...).ifPresent(...)` 按存在性写入，不向
表达式树放入 null。

当前 Execution 聚合只保存 Flow output 声明，没有保存最终 output 值。因此
RunVariables 稳定暴露 `execution.outputs` 但当前解析结果为空 Map；不提供
`executionOutputs(...)` 覆盖入口，也不能从最后一个 Task output 隐式猜测最终值。
最终值的定义、计算和持久化需要后续独立决策并扩展 Execution 领域事实。

### Route 执行时序

- Route 是 OrchestrationTask，也是一个真实 Task；Executor 规划 Route 时必须先创建
  Route TaskRun 并加入 Execution。
- Executor 启动 Route TaskRun 后，才以 Flow、Execution、Route Task 和该 RUNNING
  TaskRun 构建 RunVariables，并计算 Route Condition。因此 `taskRun.id`、
  `taskRun.state`、`parent` 和 `parents` 在 Route 表达式中都有确定来源。
- Condition 成立时 Route TaskRun 保持 RUNNING，直到子树收敛；Condition 不成立时
  Route TaskRun 直接 SUCCESS，且不创建任何子 TaskRun。
- 已完成的 Route TaskRun 不再重新计算 Condition。SUCCESS 同时稳定表达“未进入子树”
  或“已进入且子树完成”，两种情况都意味着该 Route 已收敛。

### 表达式路径与 Condition

- `VariablePath` 统一解析和读取点分安全路径；只遍历 Map，不执行脚本、方法、反射、
  算术或属性访问。
- TemplateExpression 渲染完整规范变量树，不再只读取当前 TaskRun inputs。
- Condition Operand 保存完整路径，例如 `outputs.prepare.result`，不保存
  `OperandScope` 或其他顶层 scope 枚举。
- Condition parser 不限制顶层路径名称。未知或当前不存在的根可以被解析，求值时按
  缺值返回 false；这为后续增加顶层变量保留兼容空间。
- Flow 发布仍可对当前已知的 `inputs`、`outputs`、`vars` 引用做声明与类型校验，但不
  以固定 scope 或串行可见范围拒绝安全完整路径。
- 本决策不支持 `parents[0]` 等列表索引；`parents` 先作为稳定的运行数据结构提供。

### Worker 边界

- Executor 在形成 WorkerTask 前使用 RunVariables Builder 创建规范变量树。
- WorkerTask 的 `executionId`、`taskRunId` 和可选 `parentTaskRunId` 继续作为
  Executor/Worker 结果路由信封字段存在，但 WorkerTask 必须校验它们与 variables 中
  的规范路径一致。
- WorkerDispatcher 不接收或传递 Session，只使用
  `RunContext.builder().variables(workerTask.variables()).build()` 为每次调用创建新
  RunContext，再直接调用 RunnableTask。
- Session 仍可留在持久化、审计和事件 Handler 的事务边界，不进入 RunContext。

## 理由

RunVariables 隐藏了从多个领域事实构建表达式视图的复杂度，RunContext 则保持为小而
稳定的 RunnableTask 调用接口。模板和 Condition 共享路径语义与同一变量树后，表达式
是否可读只由四个领域事实根的当前状态决定，不再由不同 scope 实现或调用方覆盖值
决定。

完整路径而非固定 scope 枚举避免顶层变量协议每次扩展都修改 Condition 领域，同时
Map-only 解析仍然保持安全边界。Worker 信封保留路由身份并做一致性校验，可以在不把
这些身份重复保存到 RunContext 字段的前提下保护结果关联。

## 后果

- 旧表达式 `variables.environment` 必须改为 `vars.environment`；旧 `$flow.*` 保留键
  和 `RunContext.create(...)` 不再兼容。
- RunnableTask 不能从 RunContext 获得 Session、Execution 或 TaskRun 领域对象，只能
  读取规范变量快照、`TaskRunInfo`/`FlowInfo` 只读视图和便捷派生值。
- Condition 未知根不再是解析错误；不存在或类型不兼容的运行值统一求值为 false。
- Route 条件是否成立都会留下一个完成的 Route TaskRun；条件不成立仍不会产生子
  TaskRun。
- 新增顶层运行变量时优先扩展 RunVariables 投影，不向 RunContext 增加重复字段，也不
  在 Condition 中增加 scope 枚举。
- `execution.outputs` 的最终值来源和 `parents` 列表索引语法仍需后续需求与决策。
