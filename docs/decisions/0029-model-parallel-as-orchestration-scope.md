# ADR 0029：将 Parallel 建模为编排作用域

## 状态

Accepted（2026-08-04）

本决策修订 ADR 0017、0021 和 0024 中关于 `WAITING`、`BranchTask`、
`ParallelTask` 自身立即完成以及 Execution 等待状态的条款。单 Execution、真实
TaskRun、显式并行、Executor/Worker 分工和精确 Flow Reversion 恢复边界继续有效。

## 背景

流程中已经有 PAUSE、PARALLEL，并计划继续加入 LOOP、LOOP UNTIL 和 SUBFLOW。
这些 Task 没有可交给 Worker 的 `run`，而是由 Executor 解释并改变编排方式。
现有 `BranchTask` 名称只覆盖“分支”，无法准确概括暂停、循环和子流程；现有
Parallel 实现还会在子分支开始前先完成结构 TaskRun，因而不能表达并行范围何时
真正收敛。

同时，现有 `WAITING` 被 Execution 和 PAUSE TaskRun 共同使用。并行范围内只要
一个 PAUSE 分支等待，就可能把 Execution 错误地解释为等待；这会掩盖其他仍可
运行的分支，也使恢复动作必须额外切换 Execution 状态。

已确认 Parallel 需要表达完整的编排作用域，而不是一次性的分叉指令。

## 备选方案

### 方案一：保留 BranchTask 和立即完成的 ParallelTask

改动最小，但术语无法容纳循环和子流程，Parallel TaskRun 也无法回答并行范围是否
仍在运行。

### 方案二：新增 ParallelRun 或 ParallelBranch 聚合

单独保存分支选择、游标和并发信息。该方案会复制 Execution 与 TaskRun 已经拥有
的运行事实，并引入新的 Repository、表和恢复来源。

### 方案三：统一 OrchestrationTask，并由 Parallel TaskRun 持有作用域

没有 `run` 的流程控制 Task 统一实现 `OrchestrationTask`。Parallel 的 TaskRun 在
全部实际选中分支收敛前保持 RUNNING；Executor 只依据 Flow Reversion 和 TaskRun
树恢复调度。

## 决策

采用方案三。

### 统一语言和类型

- `RunnableTask` 表示有实际工作、由 Worker 执行的 Task 能力。
- `OrchestrationTask` 表示没有 `run`、只由 Executor 解释的编排能力。
- 每个具体 Task 必须恰好实现其中一种能力。
- `BranchTask` 一次性破坏迁移为 `OrchestrationTask`，不保留别名或兼容接口。
- Flow 自有 OrchestrationTask 按编排语义统一归入
  `org.cses.flow.extensions.flow`，不按 Parallel、Pause 等具体类型各自创建目录；
  后续 Loop、Loop Until 和 Subflow 也遵循这一归属。
- 具体并行类型一次性迁移为
  `org.cses.flow.extensions.flow.Parallel`。Pause 的最终类名和专有定义由
  ADR 0031 修订为 `org.cses.flow.extensions.flow.Pause`。不保留旧类型地址，也不
  迁移已有定义数据。

### 对象角色和聚合边界

Parallel 是 Flow Reversion 内的 Task 定义实体，不是独立聚合。Parallel TaskRun
是 Execution 聚合内的一次真实编排作用域实例：

- 通过 `taskId` 精确关联 Parallel 定义。
- 直接分支 TaskRun 的 `parentId` 指向 Parallel TaskRun。
- 分支后代继续形成普通 TaskRun 树。
- 不新增 `ParallelRun`、`ParallelBranch`、Repository 或数据库表。
- 不持久化“已选择分支列表”、next 队列、游标或运行时路由快照；恢复只使用精确
  Flow Reversion 和已存在的 TaskRun 事实。

### Parallel 生命周期

| 当前状态 | 动作 | 目标状态 |
| --- | --- | --- |
| `CREATED` | Executor 进入 Parallel | `RUNNING` |
| `RUNNING` | 全部实际选中分支子树正常收敛 | `COMPLETED` |
| `RUNNING` | 任一分支明确失败或 Execution 被取消 | `TERMINATED` |

Parallel 不进入 `PAUSED`。即使它的所有未完成叶子都是 Pause TaskRun，Parallel
TaskRun 仍保持 `RUNNING`。

### 分支选择与组合

- Parallel 的每个直接子 Task 及其完整子树构成一个分支。
- Executor 按 Flow 定义顺序记录所有可创建的直接子 TaskRun；不同分支的实际完成
  顺序不影响结果。
- 直接子 Task 数量不受限制，允许 0、1 或多个。
- 运行时没有 route 匹配时，不创建子 TaskRun，Parallel 正常完成。
- Parallel、Pause、Runnable Task 以及未来其他编排 Task 可以任意嵌套组合。
- 嵌套 Parallel 使用独立 TaskRun 作用域；外层只在内层完整收敛后把对应分支视为
  已收敛。

### 路由、依赖和未选择传播

- 每个分支使用进入 Parallel 时同一份不可变流动上下文判断直接子 route。
- route 不匹配的 Task 不创建 TaskRun，也不创建 `SKIPPED` 状态。
- `dependOn` 继续允许出现在 Parallel 内或跨组合使用。
- route 匹配且依赖均已完成的 Task 可以形成 TaskRun。
- 依赖目标已经因 route 或上游未选择而确定不会运行时，依赖方也确定为未选择；
  该传播不创建 TaskRun，且不阻止 Parallel 正常收敛。
- 依赖目标仍可能运行时，依赖方继续等待已有事实变化。

### 数据边界

- 同一份进入 Parallel 的不可变上下文扇出给所有实际选中分支。
- 各分支从该快照开始独立演进，不自动合并分支 outputs。
- Parallel 收敛后，原始进入上下文继续沿外层线路流动。
- 下游读取分支结果必须显式使用 `dependOnOutputs.<taskKey>`。
- Parallel 可以声明 outputs，但当前实现不隐式填充值；其 TaskRun outputs 可以为空。
  不从同名 inputs 或任一分支 outputs 猜测填充。

### 失败与取消

- Parallel 使用 fail-fast：第一个明确分支失败会终止 Execution、Parallel 和全部
  尚未完成的兄弟分支及后代；已经完成的分支保留 `COMPLETED`。
- 未处理的框架异常仍使当前事务回滚，不伪造业务终止事实。
- 只允许取消整个 Execution，不提供单分支取消。
- 取消后 Pause 分支不能恢复，Parallel 之后的任务不再运行。

### PAUSED 状态所有权

统一运行词汇改为：

```text
CREATED / RUNNING / PAUSED / COMPLETED / WARNING / CANCELLED / FAILED / TERMINATED
```

- `PAUSED` 只允许由明确的 Pause TaskRun 使用。
- Execution 只走
  `CREATED -> RUNNING -> COMPLETED | TERMINATED`，不存在等待或暂停状态。
- Pause TaskRun 走
  `CREATED -> RUNNING -> PAUSED -> RUNNING -> COMPLETED | TERMINATED`；其中恢复后的
  RUNNING 由 Executor 收敛。WARNING、CANCELLED、FAILED 由 ADR 0031 作为超时
  Behavior 目标预留，当前没有触发入口。
- Parallel TaskRun 和 Runnable TaskRun 都不使用 `PAUSED`。
- 恢复 Pause 时先把目标 Pause TaskRun 恢复为 `RUNNING`，再由 Executor 状态机
  完成并继续推进；Execution 在恢复前后都保持 `RUNNING`。该条由 ADR 0031 修订。
- 稳定暂停点由 Executor 检查 TaskRun 事实得出，不能再通过 Execution 状态推断。
- ExternalTask 自身的 `WAITING` 是外部任务技术生命周期，不属于 `State.Type`，
  本决策不修改它。

### concurrent 契约

Parallel 新增可选字段 `concurrent`：

- 值必须是固定的正整数；省略表示不限制。
- 不支持表达式，不以 0 或其他魔法值表示无限。
- 它表示 Parallel 作用域下 Worker 队列消费者的最大并发数，不表示分支准入数量。
- Executor 仍可创建全部已可运行的分支 TaskRun；PAUSE 和其他 OrchestrationTask
  不消耗 Worker 并发配额。
- 本轮只落地定义、校验、Schema 和持久化契约。队列投递、消费者控制、嵌套作用域
  配额和 WorkerTask 携带作用域标识由后续独立决策实现。

## 不变量

1. 具体 Task 恰好实现 RunnableTask 或 OrchestrationTask。
2. Parallel TaskRun 从进入到全部实际选中分支收敛始终为 RUNNING。
3. Parallel 的直接分支 TaskRun 必须以该 Parallel TaskRun 为 parentId。
4. 未选择的 Task 不产生 TaskRun 或虚构状态。
5. 分支 outputs 不会隐式合并进 Parallel outputs 或外层流动上下文。
6. 只有 Pause TaskRun 可以处于 PAUSED；Execution 和 Parallel 永远不处于 PAUSED。
7. 失败或取消后不存在仍未完成的 TaskRun。
8. concurrent 为空或为正整数，且不改变 Executor 的分支选择事实。
9. 精确 Flow Reversion 与 TaskRun 树是恢复 Parallel 的全部持久化事实。

## 验证场景

- 正向：0、1、多个分支；完成顺序反转；嵌套 Parallel；分支 Pause 后逐个恢复；
  显式依赖输出；`concurrent` 缺省和正整数持久化往返。
- 反向：`concurrent <= 0`；RunnableTask 与 OrchestrationTask 双能力或无能力；
  重复恢复；取消后恢复；未声明 outputs。
- 变异：若 Parallel 启动后立即完成、若 Execution 随 Pause 进入 PAUSED、若自动
  合并任一分支 outputs，测试必须失败。
- 恢复：在部分分支完成、部分 Pause 的稳定点重新加载 Execution 和 Flow
  Reversion，能够从 TaskRun 事实继续并最终收敛。
- 并发：不同分支完成顺序不改变 TaskRun 定义顺序、最终状态和汇合次数；实际
  Worker 消费并发在队列方案落地后补充验证。

## 理由

- TaskRun 作用域能直接表达 Parallel 是否真正完成，不需要额外运行实体。
- Execution 始终 RUNNING 后，暂停一个分支不会遮蔽其他可运行分支。
- 输入快照扇出、输出不隐式合并使数据来源确定，避免并发写入顺序影响结果。
- 未选择传播让 route 与 dependOn 可以组合，而不需要虚构 SKIPPED 事实。
- concurrent 与 Worker 消费边界分离后，Parallel 定义不承担消息队列实现细节。

## 后果

- 删除 `BranchTask` 和 `ParallelTask`，迁移全部代码、YAML、Demo 和测试类型地址。
- `State.Type.WAITING` 破坏迁移为 `PAUSED`；已有 Execution、TaskRun 和
  ExternalTask 运行事实一次性清空，不做兼容迁移。
- ExecutorContext 使用 paused TaskRun 词汇，并增加编排作用域完成这一瞬时计划；
  该计划仍不持久化。
- Executor 必须在搜索分支时区分“依赖待完成”和“依赖确定未选择”。
- PostgreSQL 约束必须分别限制 Execution 与 TaskRun 的可用状态；ExternalTask
  `WAITING` 约束保持不变。
- 当前同步 WorkerDispatcher 只能验证调度事实，不能证明 `concurrent` 的物理消费
  上限；该字段在队列消费者改造完成前不会改变实际 Worker 投递。
