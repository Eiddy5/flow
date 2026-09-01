# ADR 0078：以 Generation 统一记录退回片段与循环轮次

## 状态

Accepted（2026-08-31）

## 背景

Pause 除了正常 `resume`，还需要允许用户从当前已经创建且正在等待的 Pause
退回到本次 Execution 中一个已完成的历史 TaskRun，再从该目标重新执行到源
Pause。该行为迭代的是一段运行片段，不是重新启动整个 Execution，也不能覆盖或
删除已经发生的 TaskRun 历史。

现有 Loop 与 LoopUntil 通过扫描子 TaskRun 的 `iteration` 推导最新轮次。该方案
能够继续调度，却不能直接表达“当前轮次、已经结束的轮次以及进入本轮的原因”，
也无法与退回片段复用同一种历史查询语义。

## 方案

### 方案一：Rewind 和 Loop 分别建立专用历史模型

语义直接，但会出现两套相同的版本迁移、Current/History、持久化和查询结构。

### 方案二：只增加一个全局 generation 整数

实现简单，但无法说明当前版本的边界与原因，也无法区分当前事实和已经结束的
历史；多个 Loop 也不能共享一个 Execution 级游标。

### 方案三：共享 Generation 值对象，由迭代作用域分别拥有

Generation 只表达“这个拥有者当前是否存在一轮片段迭代，以及过去有哪些轮次”。
Execution 和每个循环 TaskRun 各自拥有该值对象，具体边界语义由拥有者决定。

## 决策

采用方案三。

### Generation 模型

- `Generation` 只定义两个内部类型：`Current` 与 `History`。
- `Current` 保存：
  - 从 1 开始连续递增的 `version`；
  - 可成对出现的 `sourceTaskRunId` 与 `targetTaskRunId`；
  - 非空 `reason`；
  - 产生该 Current 的 Unix 毫秒 `date`。
- `History` 只保存已经结束的 `List<Current>`，不另建重复的 History Entry 类型。
- 正常 Execution 没有退回时，`current` 为空且 `history.currents` 为空；不能为正常
  执行伪造 version 1。
- Current 完成或被下一次迭代替代时，原对象原样进入 History；版本不能覆盖或回退。

### Rewind 所有权与状态迁移

- `Execution.generation` 专门保存该 Execution 当前和历史的退回片段。
- 新增外部 `Rewind` Command 与
  `ExecutionService.rewind(session, executionId, sourceTaskRunId,
  targetTaskRunId, reason)` 公开用例。
- source 必须是当前 Execution 中已创建且处于 `PAUSED` 的 Pause TaskRun；target
  必须是同一 Execution 当前有效运行路径中位于 source 之前的已完成历史 TaskRun；
  已经被 Generation 替代的旧 TaskRun 不能作为下一次退回的源或目标。
- 本阶段只接受顶层串行 TaskRun 之间的退回。Parallel 内部、Loop 内部和嵌套活动
  片段的规则尚未确认，公开入口必须拒绝，不能由 Executor 猜测。
- 接受退回后，旧的未完成 TaskRun 进入 `KILLED`，Execution 写入新的 Current 并从
  `PAUSED` 进入 `RESTARTED`。
- `handleRestart` 仍只负责 `RESTARTED -> RUNNING`；它不计算退回目标，也不创建
  TaskRun。
- `handleNext` 读取 Execution 的 Current，只为“target 定义步骤到 source Pause”
  这一段创建带当前 Generation version 的新 TaskRun。目标之前的有效历史保持不变，
  source 之后的步骤不能提前运行。
- 新片段再次到达 Pause 时，用户既可 `resume`，也可再次 `rewind`。再次 rewind 会把
  原 Current 放入 History，再以新 source、target、reason 创建下一 version。
- 用户恢复当前片段重新产生的 source Pause 后，Current 进入 History并清空；
  Execution 按最新有效 TaskRun 输出继续普通调度。

### Rewind 受理结果与影响片段

- `ExecutionService.rewind(...)` 返回专用 `RewindResult`，其中同时包含退回提交时的
  Execution 快照和 `affectedTaskRunIds`。HTTP Adapter 将其映射为相同的组合结构，
  不要求调用方从完整 TaskRun 历史重新推导影响范围。
- 影响片段以该次提交时的 `Execution.effectiveTaskRuns()` 为唯一运行历史输入，范围
  是 target 所属顶层定义到 source Pause 所属顶层定义（两端均包含），并包含这些
  顶层 TaskRun 下已经真实创建的全部后代 TaskRun。
- 未命中的 Route TaskRun 已真实发生并以 `SKIPPED` 收敛，因此作为叶子进入列表；
  没有创建的 Route 子 TaskRun 不会被伪造到列表中。
- 列表按业务回滚顺序返回：在 Execution 历史中较晚追加的 TaskRun 在前，因此后代
  先于所属父 TaskRun，target TaskRun 最后。每次 rewind 独立计算，后一次结果不会
  混入已被上一 Generation 替代的 TaskRun。
- `RewindResult` 仍是 Queue 受理结果。其中的 Execution 是提交时、命令消费前的
  防御性快照；影响列表表示本次已通过校验的拟退回范围，不证明异步 rewind 已经写入
  Generation 或完成了外部业务回滚。

### Loop 与 LoopUntil 所有权

- 每个 Loop 或 LoopUntil 的作用域 TaskRun 拥有自己的 `generation`。
- 进入循环作用域时创建 version 1、reason 为 `INITIAL` 的 Current。
- 一轮完整收敛且决定继续时，原 Current 进入 History，新 Current 的 version 加一；
  固定轮数 Loop 记录 `FIXED_COUNT_NOT_REACHED`，LoopUntil 记录
  `CONDITION_NOT_SATISFIED`。
- 循环成功或失败时，最后一个 Current 进入 History并清空。
- 直接循环子 TaskRun 的 `iteration` 继续作为该 TaskRun 所属轮次的稳定标量引用，
  值等于拥有循环 TaskRun 当时的 `generation.current.version`；它不再是推导当前轮次
  的权威游标。

### 持久化与查询

- `executions.generation` 与 `task_runs.generation` 使用非空 JSONB 保存完整
  Current/History。
- `task_runs.execution_generation_version` 保存该 TaskRun 属于哪个 Execution 退回
  片段；普通 TaskRun 为空。
- TaskRun occurrence 唯一身份扩展为
  `executionId + taskId + parentId + iteration + executionGenerationVersion`，因此同一
  定义可在不同退回版本形成不同历史 TaskRun。
- Execution 查询同时返回 Execution Generation、TaskRun Generation 与
  TaskRun 的 Execution Generation version，使用户能够直接观察当前片段、历史片段、
  当前循环轮次、历史轮次和原因。
- 下游变量和完成状态只读取当前有效运行路径；活动退回片段内被替代的旧 outputs 或
  WARNING 不得影响新片段。

## 理由

- 一个模型同时覆盖片段迭代和循环轮次，避免重复的版本与历史协议。
- Generation 归属实际迭代作用域，多个循环之间不会争用 Execution 全局轮次。
- TaskRun 历史保持追加且可审计，退回不会改写已经发生的状态和输出。
- 状态恢复与下一批 TaskRun 计算仍沿用现有 Executor 分工：Restart 只改状态，Next
  才解释运行历史和 Generation。
- 影响片段由 Execution Service 在受理边界统一计算，Controller 和外部业务不需要
  重复解释 TaskRun 父子关系、Generation 有效性与回滚顺序。

## 后果

- 本 ADR 修订 ADR 0036 中“最新轮次完全由 TaskRun 历史扫描推导”的条款；
  `iteration` 仍保留为直接循环子 TaskRun 的轮次归属。
- 本 ADR 扩展 ADR 0016：Pause 的公开后续动作不再只有 Resume，还包括 Rewind；
  审批、表单等外部业务仍不进入 Flow Core，也不能直接修改 Execution。
- 本 ADR 扩展 ADR 0034 的 JSONB 值对象范围：State 与 Generation 分别保存各自完整
  当前/历史，不把两者合并。
- 开发期 Schema 基线变化后必须重建数据库并重新生成 JOOQ；不提供 ALTER 或旧数据
  回填脚本。
- Parallel 内部 rewind、循环内部 rewind、权限、最大退回次数和嵌套活动片段仍需
  独立确认后再扩展。
