# ADR 0021：普通子任务默认串行且仅 PARALLEL 显式并行

## 状态

Accepted（2026-07-30；PARALLEL 通过 Worker 完成的规则由 ADR 0024 替代）

本决策修订 ADR 0001 和 ADR 0006 中“一个 Task 的所有匹配直接子 Task 同批
运行”的规则。ADR 0006 的单 Execution、真实 TaskRun、条件路由、依赖与汇合
边界继续有效；ADR 0020 的调度计划与应用边界继续有效。

## 背景

当前 `Task.tasks` 同时承担两个不同含义：

- 普通业务步骤完成后的有序后续步骤。
- 一个分叉点下需要同时开始的并行分支。

执行器无法区分这两种意图，因此只要普通 Task 下存在多个 `DIRECT` 子 Task，就会
把它们形成同一 nexts 批次。用户编排一个审批步骤和之后的普通自动步骤时，后者会
在审批尚未完成前提前运行。

用户已经确认：

- 普通流程默认串行。
- 子任务集合按声明顺序推进。
- 并行必须由单独、显式的并行 Task 类型表达。

## 备选方案

### 方案一：继续以多个匹配 route 隐式表示并行

无需调整定义模型，但普通同级步骤仍会被误解释为并行。页面只能依赖用户手工调整
树形结构规避，无法从 YAML 判断真实意图。

### 方案二：给每个 Task 增加 `parallel: true/false`

可以区分调度方式，但会把并行网关语义变成所有 Task 的可选标志。类型扩展和页面
还需组合解释 `type + parallel`，并且同一个 Task 在不同定义中承担两种职责。

### 方案三：新增显式 PARALLEL Task，其他 Task 的子任务默认串行

普通 Task 的直接子任务按定义顺序执行；PARALLEL Task 是明确的结构步骤，其所有
route 成立的直接子任务形成同一并行批次。

## 决策

采用方案三。

### 普通 Task 子任务

- `Flow.tasks` 继续按声明顺序串行。
- 除 PARALLEL 外，Task 的所有 route 成立直接子 Task 按声明顺序串行。
- 前一个已选择子 Task 的完整子树收敛后，才判断并进入下一个直接子 Task。
- route 不成立的子 Task 不产生 TaskRun，并继续判断后续同级定义。
- 多个 route 同时成立不再隐式形成并行；它们按声明顺序执行。

### PARALLEL Task

- 稳定 Task type 为 `PARALLEL`。
- PARALLEL 本身是 Flow 聚合内的具体 Task 定义，并产生真实 TaskRun。
- PARALLEL Worker 同步完成自身工作；之后所有 route 成立且依赖已满足的直接
  子 Task 形成同一有序 nexts 批次。
- 每个并行分支仍在同一个 Execution 内运行，不创建 Child Execution。
- PARALLEL 的完整子树只有在全部已选择分支收敛后才收敛；其后普通同级 Task
  才能继续。
- 分支汇合仍可使用顺序收敛或显式 `dependOn`；不新增虚构 JOIN TaskRun。

Task 基类向 Executor 提供稳定的调度语义查询，普通实现返回串行，只有
ParallelTask 返回并行。Executor 不比较 `type` 字符串，也不依赖具体扩展类。

### 定义、页面与持久化

- YAML 使用 `type: PARALLEL` 明确声明并行节点。
- Demo 的“创建并行流程”必须创建一个 PARALLEL 节点，再把分支放入其
  `tasks`，不能只创建两个普通同级节点。
- PARALLEL 通过现有 TaskExtension、Flow 部署、PostgreSQL Task 快照与 Worker
  注册链路扩展，不增加表字段和独立 Repository。
- 普通同级结构不会被自动改写成 PARALLEL，因为系统无法可靠判断历史作者的
  串行或并行意图。

### 已有 Flow Reversion 与 Execution

本决策修正运行解释规则，不修改已保存 Flow Reversion 的定义内容：

- 没有显式 PARALLEL 的已有定义在新执行中按串行解释。
- 需要保留并行意图的用户必须编辑草稿、添加 PARALLEL 并部署新 Reversion。
- 已经产生多个活动 TaskRun 的 Execution 以真实 TaskRun 为准；现有活动分支
  可以继续完成，不删除或重写运行历史。
- 不执行自动数据迁移，避免把原本应串行的历史定义猜测为并行。

## 理由

显式 PARALLEL 让定义直接表达用户意图，并使普通编排获得安全的串行默认值。
并行仍通过单 Execution 内多个 TaskRun 表达，因此不破坏版本绑定、取消、恢复、
依赖或聚合事务边界。

## 后果

- Task 定义新增 ParallelTask、ParallelTaskPlugin 和相应自动完成 Worker 支持。
- Executor 必须分别计算串行 children 与并行 branches。
- UC-05 的并行流程必须显式包含 PARALLEL；UC-07 增加普通同级任务不得提前执行
  的场景。
- 依赖隐式同级并行的历史定义需要重新部署，系统不会猜测性改写。
- 未来若需要单选网关、顺序组或其他结构控制，应新增明确编排类型，不能复用
  PARALLEL 或恢复隐式规则。
