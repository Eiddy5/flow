# ADR 0006：单 Execution 条件分支、并行与汇合协议

## 状态

Accepted（生产内存实现部分由 ADR 0007 修订；绑定的 Flow 定义类型由
ADR 0008 修订；PAUSE 恢复入口由 ADR 0016 修订；可运行批次的计划与应用边界
由 ADR 0020 修订；普通子任务默认串行和显式 PARALLEL 语义由 ADR 0021
修订）

## 背景

ADR 0001 已确定一次 Flow 启动只创建一个 Execution，真实路径由有序 TaskRun
表达，并把条件路由与 `dependOn` 汇合作为第一阶段范围。UC-05 和 UC-06 进一步
要求并行 PAUSE 分支、任意完成顺序、唯一汇合、精确字符串路由和无匹配稳定态。

现有状态机只允许一个运行中的 TaskRun，并只顺序遍历顶层 Task；Task 的嵌套
`tasks`、`route` 和扩展属性中的 `dependOn` 尚未进入调度。

## 备选方案

### 方案一：为每条分支创建 Child Execution

可以复用单游标状态机，但违反 ADR 0001，且会把一次启动的取消、版本绑定和真实
历史拆散到多个聚合。

### 方案二：增加并行、等待或跳过状态

能够显式保存调度过程，但会把推导状态写入数据模型，并与当前
`CREATED/RUNNING/WAITING/COMPLETED/TERMINATED` 统一运行状态冲突。

### 方案三：在单 Execution 中按定义和 TaskRun 事实重建可运行集合

TaskRun 只记录真实执行。状态机根据绑定的不可变 Flow Reversion、已完成
TaskRun、活动 TaskRun 和输出重新计算下一批可运行 Task。

## 决策

采用方案三。

### 子 Task 与路由

- 一个 Task 完成后，其直接子 Task 成为候选集合。
- `DIRECT` 始终匹配。
- 第一阶段条件语法固定为
  `outputs.<field> == "<value>"`，使用区分大小写的字符串精确比较。
- route 只能读取直接父 TaskRun 的 outputs；部署时校验语法，并校验字段由父
  Task 声明。
- 所有匹配的直接子 Task 都进入可运行集合；多个匹配形成同一 Execution 内的
  并行线路。
- 同一时刻满足条件的直接子 Task 由 `handleNext` 形成一个有序 nexts 批次；
  `handleNext` 不改变 Execution，`onNexts` 完整校验后一次并入全部 TaskRun。
- 没有 route 匹配时不创建候选 TaskRun，该子树视为已收敛；若没有后续顶层
  Task，Execution 正常进入 `COMPLETED`。
- 未选择分支不创建 TaskRun，也不引入 `SKIPPED`。

### 并行 TaskRun

- Execution 可以同时拥有多个 `CREATED`、`RUNNING` 或 `WAITING` TaskRun。
- 子 TaskRun 的 `parentId` 指向直接父 TaskRun id。
- 同一不可循环定义 Task 在一次 Execution 中最多创建一个 TaskRun。
- Worker 仍逐个同步派发；PAUSE 返回 WAITING 后，状态机继续创建其他已经可运行
  的并行分支，直到没有新的可运行 Task。

### `dependOn` 与汇合

- YAML `dependOn` 是 Task key 字符串列表，在 Task 领域对象中暴露为结构化只读
  列表。
- 部署时校验依赖 key 存在、不得依赖自身且依赖图无环。
- 一个 Task 只有在其全部依赖 TaskRun 为 `COMPLETED` 后才可运行。
- 顶层顺序仍然生效：后续顶层 Task 还必须等待前一个顶层 Task 的整个已选择
  子树收敛。这同时表达“依赖完成”和“所有参与线路到达”。
- 可运行集合根据 TaskRun 事实重建；已经存在 TaskRun 的 Task 不会再次创建，
  因而汇合 Task 最多创建一次。

### 取消、失败和恢复

- 取消遍历并取消同一 Execution 中全部活动 TaskRun 及其 Worker 所有等待资源。
- 任一 TaskRun 明确失败时 Execution 和全部未完成 TaskRun 进入 `TERMINATED`，
  不再创建分支或汇合。
- PAUSE 恢复只完成原 TaskRun，然后重新计算可运行集合；不会再次派发原 PAUSE。
- 测试中的 Execution 与 TaskRun 写入共享一个命令事务；Resume 后续推进异常时
  回滚到命令开始前快照。生产环境由 PostgreSQL 事务提供等价原子性。

## 理由

该协议只扩展状态机的可运行集合计算，不改变公开 Service、核心状态集合或真实
历史模型。调度结果可以从不可变定义和 TaskRun 事实恢复，既满足第一阶段内存
验证，也保留未来 PostgreSQL Adapter 的持久化边界。

## 后果

- 第一阶段不支持 Loop 或同一 Task 在一次 Execution 中多次运行。
- 多 route 同时匹配采用并行语义；未来若产品需要单选，必须新增显式网关语义，
  不能改变本 ADR 下已有 Flow 的解释。
- `dependOn` 暂不保存独立到达记录；顶层顺序和已完成 TaskRun 是当前收敛证据。
- 更复杂表达式、类型比较、默认分支、超时与重试需要后续 ADR。
- 单 Execution 并行 TaskRun 的目标类模型和状态边界由
  [`execution-domain-model.md`](../standards/execution-domain-model.md)
  统一规定。
