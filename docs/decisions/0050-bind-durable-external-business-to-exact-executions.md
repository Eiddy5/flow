# ADR 0050：外部业务绑定精确 Execution 并可靠物化

## 状态

Superseded by
[`ADR 0068`](0068-remove-two-phase-execution-start.md)

本文保留被取代的两阶段物化方案及其历史背景；当前 Execution 启动只允许一次完整的
`create` 操作。

## 背景

CSES Approval 在自己的数据库事务中先固定 Approval Template、精确 Flow Reversion
和待执行的 START Operation，Flow Execution 随后才作为跨数据源副作用创建。两者之间
可能发生进程崩溃、网络响应丢失或新 Flow Reversion 部署。如果重试时重新选择 Current
Flow Reversion，同一 Approval 会从已经承诺的 V1 漂移到 V2；如果每次生成新的
Execution ID，响应丢失会造成重复运行。

同时，一个真实 Approval 表达整份申请的业务生命周期，而 Flow 中可以有多个串行或
并行 Pause 节点。把每个 Pause 建成独立 Approval 会拆散整单状态、取消和历史；把
审批规则放进 Flow 又会破坏两个限界上下文的职责。

## 备选方案

### 方案一：重试时始终启动 Current Flow Reversion

公开入口保持简单，但不能兑现 Approval 已保存的精确模板绑定，部署竞态会改变业务
语义。

### 方案二：由 Flow 保存 Approval 和审批节点

可以在单个数据库内协调运行，但 Flow 将反向拥有审批模板、参与人、Decision 和整单
生命周期，破坏 ADR 0038 的通用编排边界。

### 方案三：外部业务持久承诺精确绑定，再幂等物化

CSES 保存稳定 Execution ID、精确 Flow Reversion 和可靠 Operation；Flow 只提供一个
受信调用路径，按这三个事实创建待运行 Execution，并继续拥有全部运行状态。

## 决策

采用方案三。

- 普通 `create(flowId)` 和 `createPending(flowId)` 仍解析 Current Flow Reversion，
  最大 Reversion 已删除时不回退旧版本。
- 可靠外部业务可以调用精确物化入口，提供调用方已经持久化的稳定 `executionId`、
  `flowId` 和正数 `flowReversion`。Flow 必须加载该精确且未删除的 Reversion，创建
  CREATED Execution，但不立即派发首个 Task。
- 相同 Execution ID 与相同精确引用的重试返回同一 Execution；相同 ID 配不同 Flow
  或 Reversion 必须冲突，不能复制或改绑。
- 精确物化只用于完成已经持久承诺的外部副作用，不是面向普通用户的历史版本选择器，
  也不是 Current Flow 缺失时的回退机制。
- 一个 CSES Approval 绑定这条完整 Execution；每个到达的审批 Pause TaskRun 在该
  Approval 下形成一条共享 Todo，Decision 仍完全由 CSES 持有。
- 未物化的 Operation 不能越过 Flow Deletion。删除 Flow 前，宿主必须先停止产生新
  START Operation，并处理或明确终止仍引用该 Flow 的待执行 Operation；已经存在的
  Execution 继续按原精确 Reversion 运行。

## 理由

稳定身份解决“Flow 已创建但调用方丢失响应”的重复执行问题，精确 Reversion 解决
“本地事实已提交但副作用延后”的版本漂移问题。待运行状态让 CSES 能在首个 Task
执行前绑定 Approval 与 Execution。Flow 仍只校验和运行自己的定义，审批业务仍只在
CSES 演进。

## 后果

- `ExecutionService` 同时保留普通当前版本启动和可信精确物化两个契约，调用方必须
  明确选择，不能用精确入口实现产品级历史版本选择。
- 外部 Operation 的 ID 可以作为确定性 Execution ID；重试无需额外查询“是否曾经
  创建另一条 Execution”。
- Flow 发布新 Reversion 不影响已经提交但尚未物化的 Approval START Operation。
- Flow Deletion 与外部 START Operation 之间需要部署或管理面协调；删除后的精确
  Reversion 不接受新的物化。
- Approval 详情、取消和最终结果以整条 Execution 为边界；Todo 只表达一个精确 Pause
  occurrence，不能替代 Approval。
