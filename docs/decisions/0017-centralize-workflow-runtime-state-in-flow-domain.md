# ADR 0017：在 Flow 定义域统一工作流运行状态与变更历史

## 状态

Accepted（首次启动与单轮 transition 增量记录边界由 ADR 0020 补充）

## 背景

工作流运行状态原来由 Execution、TaskRun 和 Worker 分别定义。它们虽然使用相近
的状态值，但没有共享同一个运行词汇，也无法从一个状态对象看出已经发生的变化
轨迹。状态判断散落后，暂停、恢复、失败和取消很容易在不同组件中产生不同解释。

已确认的目标是：

- 状态属于整个工作流运行语言，应定义在 Flow 聚合域中。
- Execution、TaskRun 等运行对象都持有同一种 `State` 对象。
- `State` 不只是当前枚举值，还要保存从创建开始的真实状态变更历史。
- 全局状态词汇统一，但不同运行对象只允许走各自生命周期需要的路线。
- PAUSE 只表达 Core 等待点；审批、表单、工单等外部业务仍维护自己的业务状态。

FlowDraft/Flow 类型与 `deleted` 表达定义生命周期事实，不是工作流运行状态，因此
不能并入本决策的 `State`。

## 备选方案

### 方案一：保留各对象独立状态枚举

Execution、TaskRun 和 Worker 各自维护枚举，通过名称约定保持一致。修改范围小，
但不能从类型上保证词汇一致，也会继续重复活动态、等待态和终态判断。

### 方案二：只统一当前状态，不记录变化历史

在 Flow 域提供一个共享枚举或仅含 `current` 的值对象。该方案能统一当前状态，
但无法由领域对象回答“经历过哪些状态、何时发生”，也无法完整持久化运行轨迹。

### 方案三：由 Flow 域提供带历史的统一 State

在 `core/domains/flows` 定义不可变 `State`，内部提供唯一 `Type` 枚举、当前值、
有序历史和通用迁移入口。Execution 与 TaskRun 持有完整 State，并进一步校验
自己的合法路线；Worker 只报告目标 `State.Type`，由聚合产生真实历史。

## 决策

采用方案三。

### 统一状态词汇

`org.cses.flow.core.domains.flows.State` 是工作流运行状态的唯一领域模型。
`State.Type` 包含五个具体状态，归入四个运行大类：

| 运行大类 | `State.Type` | 语义 |
| --- | --- | --- |
| 创建和运行 | `CREATED`、`RUNNING` | 已创建但尚未启动；正在推进 |
| 等待 | `WAITING` | 必须等待外部结果，当前不能自行继续 |
| 正常终止 | `COMPLETED` | 工作正常收敛 |
| 异常终止 | `TERMINATED` | 失败、取消或连带终止 |

失败与取消共用异常终止类型，具体原因由领域动作和 TaskRun `error` 区分，不增加
同义运行状态。不得重新引入 `ExecutionStatus`、`TaskRunStatus`、
`WorkerTaskOutcome` 或其他兼容枚举。

### State 对象与 History

`State` 是不可变值对象，固定包含：

```text
current : State.Type
history : List<State.History>
```

`State.History` 是 State 的不可变内部类，固定包含：

```text
state : State.Type
date  : long
```

`date` 是该状态真正写入历史时的 Unix epoch 毫秒。列表顺序是状态变化的权威
顺序，不依赖系统时钟值严格递增。

必须满足以下不变量：

- history 非空，第一条必须是 `CREATED`。
- history 最后一条的 state 必须等于 current。
- history 中相邻记录必须符合 State 的通用迁移规则。
- 对外返回的 history 不可修改。
- State 的相等性同时包含 current 和完整 history。

### 创建、迁移与重建

- `State.created()` 捕获一次当前时间，返回
  `current = CREATED`，history 只含同一时刻的 CREATED 记录。
- `withState(target)` 是通用迁移入口；它校验迁移、在内部捕获当前时间、追加一条
  History，并返回新的 State。
- `running()`、`waiting()`、`complete()`、`fail()` 和 `terminate()` 是
  `withState(...)` 的快捷方法，不接收时间参数。
- `rehydrate(current, history)` 只用于可信持久化适配器恢复完整轨迹；它不生成
  新历史，但必须重新校验 State 不变量。
- 禁止原地修改 current 或 history，也禁止只从数据库 current 伪造 State。

State 的通用超集迁移为：

```text
CREATED -> RUNNING | TERMINATED
RUNNING -> WAITING | COMPLETED | TERMINATED
WAITING -> RUNNING | COMPLETED | TERMINATED
COMPLETED | TERMINATED -> no transition
```

通用 State 只定义所有运行对象可能使用的合法超集。每个状态拥有者仍须限制自己
的具体路线：

| 状态拥有者 | 合法路线 |
| --- | --- |
| Execution | `CREATED -> RUNNING`；`RUNNING -> WAITING/COMPLETED/TERMINATED`；`WAITING -> RUNNING/TERMINATED` |
| 普通 TaskRun | `CREATED -> RUNNING -> COMPLETED/TERMINATED` |
| PAUSE TaskRun | `CREATED -> RUNNING -> WAITING -> COMPLETED/TERMINATED` |

因此，词汇和历史结构全局统一，不代表 Execution 与 TaskRun 可以走完全相同的
路线。聚合还要继续校验完成前已收敛、失败必须携带 error 等业务不变量。

### Worker 边界

Execution 和 TaskRun 持有完整 `State`。Worker 并不拥有聚合状态历史，因此
`WorkerTaskResult` 只携带目标 `State.Type targetState`：

```text
WAITING | COMPLETED | TERMINATED
```

Executor 把结果交给 Execution 聚合，由聚合调用领域动作并生成带真实时间的
History。Worker 不得创建完整 State、伪造 History、指定 CREATED/RUNNING，
也不能直接修改聚合。

### Flow 定义生命周期

- Flow 定义对象不持有某次运行的 State。
- Flow 的来源角色和删除事实按 ADR 0022 使用 FlowDraft/Flow 类型与 `deleted`
  表达，不进入运行 State，也不建立 `FlowDefinitionStatus`。

### PAUSE、Resume 与外部业务

PAUSE Worker 报告 `WAITING`。当 Executor 已无其他活动工作时，稳定组合为：

```text
Execution.current = WAITING
TaskRun.current   = WAITING
Task type         = PAUSE
```

审批、表单、工单等能力独立维护待办、权限、审批意见和业务状态，不得把
`PENDING/APPROVED/REJECTED` 等值加入 `State.Type`。外部能力完成后只调用
`ExecutionService.resume(...)`：

1. PAUSE TaskRun 从 `WAITING` 进入 `COMPLETED`。
2. Execution 从 `WAITING` 回到 `RUNNING`。
3. Executor 在同一生命周期内继续安排后续工作。

并行运行中可以暂时存在 `RUNNING Execution + WAITING TaskRun`；只有没有
CREATED/RUNNING TaskRun 或下一候选工作时，Execution 才进入稳定 WAITING。

### 时间规则

一般领域对象仍不得自行读取系统时间。但 State 的职责就是在每次状态变化时记录
真实发生时间，而且已经确认其快捷方法不接收时间参数，因此 `State.created()` 和
`State.withState(...)` 是该规则的唯一显式例外：它们各自只读取一次
`System.currentTimeMillis()`，并立即把值写入新 History。

测试时间边界时使用调用前后区间断言，不要求注入时钟，也不要求相邻 History 的
date 严格递增。

### 持久化

- `executions.status` 和 `task_run.status` 保存 `state.current().name()`。
- 两张表新增非空 `jsonb state_history`，按顺序保存
  `[{"state":"CREATED","date":...}, ...]`。
- Repository Entry 必须同时写入 current 和完整 history，并通过
  `State.rehydrate(current, history)` 恢复；两者不一致时拒绝重建。
- 数据库约束保证 history 是非空数组、首项为 CREATED、末项等于 status，且每项
  都含合法 state 和非负数值 date；领域重建继续校验全部迁移路线。
- 迁移把旧 `ACTIVE` 映射为 `RUNNING`，把 `FAILED/CANCELED` 映射为
  `TERMINATED`，并识别已有外部等待对应的 WAITING。
- 历史数据没有完整的旧状态事件，只能用 `created_at/updated_at` 构造一次迁移
  基线。该基线表达可验证的最小轨迹，不宣称恢复已经丢失的精确历史时间。
- 数据迁移由
  `2026-07-30/001_unify_workflow_state_types.sql` 完成，并可重复执行。

## 理由

- 一个 Flow 域类型统一所有运行对象的状态词汇和历史结构。
- CREATED 与 RUNNING 分开后，可以准确表达对象何时创建、何时真正开始。
- 由状态拥有者追加 History，时间和业务动作位于同一领域边界，不会由 Worker
  或 Repository 伪造。
- 通用迁移超集与聚合具体路线分层，既复用状态机基础，又不放松 Execution、
  TaskRun 各自的不变量。
- 定义生命周期、Core 等待语义和审批业务生命周期边界清晰，后续审批接入不会
  污染 Flow Core 状态。

## 后果

- 每次成功状态变化都会创建一个新的 State 并追加 History；没有状态变化的派生
  查询或失败操作不得增加记录。
- Execution 启动必须显式执行 `CREATED -> RUNNING`，并由首次 `onNexts`
  完成；TaskRun 计划被聚合接受和 Worker 派发分别对应 CREATED 和 RUNNING。
- ExecutorContext.states 只记录本轮观察到的 Execution Type 增量；
  本 ADR 定义的 `Execution.state.history` 仍是唯一权威完整历史。
- Resume 的“重启”是动作语义，不新增 RESTARTED 类型；它把 Execution 恢复为
  RUNNING。
- 新增运行状态时，必须同时核对 State 通用规则、各聚合路线、Worker 协议、
  Repository、数据库迁移、文档和测试。
- 失败或取消把 Execution 及其全部未完成 TaskRun 收敛到 TERMINATED；只有明确
  Worker 失败的目标 TaskRun 保存 error。
- PAUSE 和审批对接继续遵循 ADR 0016 的统一 Resume 边界。
- 本决策修订 ADR 0002 中的状态枚举、ADR 0012 中 Worker 持有完整 State 的
  描述，以及此前把 CREATED/RUNNING 合并为 ACTIVE 的文档；其余聚合、运行和
  包边界继续有效。
- 统一目标模型由
  [`workflow-core-java-model.md`](../standards/workflow-core-java-model.md)、
  [`execution-domain-model.md`](../standards/execution-domain-model.md)、
  [`pause-domain-model.md`](../standards/pause-domain-model.md) 和
  [`flow-definition-lifecycle.md`](../standards/flow-definition-lifecycle.md)
  共同维护。
