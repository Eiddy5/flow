# ADR 0079：将未命中的 Route 记录为 SKIPPED

## 状态

Accepted（2026-08-31）

## 背景

Route 的条件只能在对应 TaskRun 已经创建并进入 RUNNING 后，基于当次运行变量进行
判断。此前执行器在条件未命中时把 Route TaskRun 记为 SUCCESS，虽然不会创建其子
TaskRun，却无法从运行历史区分“条件命中且子树完成”和“条件已经判断但未命中”。

审批业务在 Flow 退回后需要根据运行历史识别受影响片段。未命中的 Route 也是已经
发生的编排判断事实，必须可查询；没有进入的业务子路径则不能伪造成已经执行。

## 方案

### 方案一：未命中的 Route 继续记录为 SUCCESS

无需增加状态，但历史丢失了 Route 的选择结果，只能重新解释定义和当时变量，无法
可靠区分命中与未命中。

### 方案二：不创建未命中 Route 的 TaskRun

历史只保留实际业务路径，但与“先创建并开始 Route TaskRun，再计算条件”的运行时序
冲突，也无法证明该条件已经被判断。

### 方案三：Route TaskRun 以 SKIPPED 保存，子路径不创建 TaskRun

同时保留判断事实和真实业务路径边界，运行历史可以直接查询且无需伪造子任务。

## 决策

采用方案三。

- `State.Type` 增加终态 `SKIPPED`，共享 State 允许通用
  `RUNNING -> SKIPPED` 转换。
- `SKIPPED` 只属于 TaskRun。Execution 的状态路线明确拒绝该状态；Worker 和
  RunnableTask 也不能把它作为运行结果上报。
- Executor 仍先创建并开始 Route TaskRun，再计算 Condition。条件未命中时，由
  Execution 聚合把该 TaskRun 从 RUNNING 迁移到 SKIPPED。
- SKIPPED TaskRun 的 outputs 固定为空且没有 error，不参与后续运行变量或循环输出。
- 调度器把 SKIPPED Route 视为已经收敛的叶子，不遍历也不创建其任何子 TaskRun，
  随后继续计算同级后续任务和 Execution 终态。
- 条件命中的 Route 保持现有语义：Route TaskRun 持有其子作用域，子树正常收敛后
  进入 SUCCESS。
- SKIPPED 不是成功执行结果，也不能作为 Rewind 的目标；当前 Rewind 目标仍只接受
  SUCCESS 或 WARNING 的历史 TaskRun。
- State 继续以 JSONB 枚举名称持久化，因此本次不改变 PostgreSQL Schema 或 JOOQ
  生成代码。

## 理由

- 历史能够直接回答每个 Route 是否命中，无需重新执行条件或推断旧变量。
- Route 判断和业务子路径的事实边界清晰：判断发生过，未选中的业务步骤没有发生。
- SKIPPED 是 TaskRun 的编排结果，不污染 Execution 生命周期或 Worker 结果协议。
- 未来构造 rewind 受影响片段树时，可以把 SKIPPED Route 作为明确叶子。

## 后果

- 本 ADR 修订 ADR 0001、ADR 0006 和 ADR 0029 中“未命中 Route 不创建 TaskRun、
  不引入 SKIPPED”的条款。
- 本 ADR 修订 ADR 0076 中“未命中 Route 完成自身”的条款：当前精确语义为 Route
  TaskRun 进入 SKIPPED，而不是 SUCCESS。
- 历史查询方需要把 SKIPPED 识别为 TaskRun 终态，同时不能把它计为成功输出。
- 未命中 Route 的子树仍然没有 TaskRun；SKIPPED 不会递归传播为伪造的子树记录。
