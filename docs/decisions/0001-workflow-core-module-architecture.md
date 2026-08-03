# ADR 0001：工作流核心模块架构

## 状态

Accepted（生产内存实现部分由 ADR 0007 修订；Flow 定义生命周期部分由
ADR 0008 修订，并由 ADR 0014 完成来源与 Reversion 迁移；Executor/Worker
包边界由 ADR 0012 修订；PAUSE 恢复入口由 ADR 0016 修订；普通子任务与显式
并行语义由 ADR 0021 修订）

## 背景

`Flow 核心设计.md` 已确定定义模型使用 `Flow/Task`，运行模型使用
`Execution/TaskRun`，并要求支持版本绑定、PAUSE 外部等待、条件分支、并行线路
和汇合。

仓库曾经存在另一套 `Node/Edge/Process/Executor/Activity` 模型。该模型已经从生产代码中删除，但部分 Agent 文档仍然引用旧术语。继续混用两套模型会使接口、持久化结构和验收对象失去唯一含义。

核心还需要同时满足两个目标：

- 调用方只学习少量稳定接口，不承担编排推进细节。
- 第一阶段可以使用内存验证行为，后续替换 PostgreSQL 存储时不改变领域语义。

## 备选方案

### 方案一：恢复 Node/Edge 图模型

优点是可以复用历史代码。缺点是与当前确认的递归 Task 定义、`route`、`dependOn` 和全部 UC 文档冲突，需要长期维护术语转换层。

### 方案二：每种 Task Type 各自推进流程

优点是单个类型实现直观。缺点是路由、分叉、汇合、版本绑定和状态更新会分散到多个类型，调用接口浅且难以保持事务一致性。

### 方案三：以 Flow/Task 和 Execution/TaskRun 为唯一模型，由核心统一推进

定义生命周期和运行推进分别形成深模块。Task Type 只负责当前 Task 的业务行为，核心统一处理运行位置、路由、并行、汇合和记录。

## 决策

采用方案三。

### 唯一领域模型

- 定义层只使用 `Flow`、`FlowDefinition` 和 `Task`；Flow Version 与 Draft
  是 FlowDefinition 的生命周期语义，不再各自建立重复类型。
- 运行层只使用 `Execution` 和 `TaskRun`；PAUSE 等待也是 TaskRun 运行事实。
- `Node/Edge/Process/Executor/Activity` 不进入新核心接口和实现。

### 模块与接口

Core 按领域提供 Service 外部接口：

- 定义生命周期由 `FlowService` 提供 YAML Draft 保存、基于当前版本创建升级
  Draft、发布、关闭，以及按 `id + version + status` 精确读取。
- 运行生命周期由 `ExecutionService` 提供启动、resume、取消和 Execution
  查询；外部能力只能调用公开 resume。
- 写操作统一经过 `CommandExecutor` 和精确类型 Handler；复杂查询经过
  QueryHandler。Controller 位于 Core 外部。

核心内部统一完成以下行为，调用方不能直接移动执行位置：

- 启动时绑定最新已发布 Flow Version。
- 在 Task 真正开始执行时创建 TaskRun，并执行对应 Task Type。
- 记录真实 TaskRun 数据并依据绑定版本的 FlowDefinition 计算下一 Task。
- 将 Task 执行委托给 Worker，统一合并 WorkerTaskResult。
- 在全部有效 TaskRun 完成后完成 Execution。
- 将一次命令从一个稳定态推进到下一个稳定态，并在同一事务内提交。

### 编排解释

- `Flow.tasks` 是顶层顺序；启动进入第一个 Task，一个顶层 Task 的分支结束后进入下一个顶层 Task。
- `Task.tasks` 是当前 Task 完成后的候选集合。所有 `route` 成立的直接子 Task 都有效；多个候选同时成立时形成并行线路。
- 子 Task 没有继续候选时，沿祖先返回到下一个顶层 Task。
- `DIRECT` 恒成立。条件表达式读取刚完成 Task 的真实输出。
- `dependOn` 引用同一 Flow Version 中的 Task key；具体运行结构在对应
  Task 类型进入实现前另行设计。
- 依赖 Task 已完成与参与 Execution 线路已到达是两个独立条件；两者都满足后才能执行 dependOn Task。
- 线路到达尚未满足的 dependOn Task 时只更新 Execution 的当前位置和到达状态，不创建 TaskRun。
- Parallel、Loop、Route 和 dependOn 不得重新引入 Child Execution；
  它们必须通过同一 Execution 内的 TaskRun 关系表达真实路径。

### 状态与数据

- 已发布 Flow Version 的定义内容不可变；发布新版本时旧版本状态转为
  `CLOSE`，新版本成为唯一 `DEPLOYED` 版本。Flow 使用稳定主键 `id`，
  YAML 中的 `key` 是业务标识。
- Draft 没有 version。再次发布使用当前最大版本加一。
- Execution 代表一次完整启动实例，不划分 Main/Child Execution。
- Execution 的有序 TaskRun 列表是真实路径的数据真相源。
- TaskRun 是 Task 实际执行状态和真实运行数据的数据真相源；同一 Task
  可以产生多个 TaskRun。
- Route 不成立和依赖尚未满足都不创建 TaskRun，也不创建 `SKIPPED` TaskRun。
- TaskRun 只记录真实 inputs、outputs、状态、尝试次数、时间和错误等运行数据，不复制 Task 定义，也不保存 Route、候选 Task 或下一 Task 等计算结果。
- 变量拆分为单线路的一跳流动上下文、按 Task key 隔离的 `dependOnOutputs` 和第一阶段只读的全局上下文。汇合时不做字段名扁平合并。
- 恢复时同时使用 Execution 的编排状态和 TaskRun 的执行事实，不能用其中一方替代另一方。
- 一次运行命令从一个稳定态推进到下一个稳定态；期间连续自动 Task、分叉或最后一次依赖汇合属于同一事务。
- Execution 和 TaskRun 使用 ADR 0017 定义的统一 State：
  `CREATED/RUNNING/WAITING/COMPLETED/TERMINATED` 五个具体状态、四个运行大类，
  并各自保存状态历史。PAUSE 是 Task 类型，到达该类型会进入 WAITING。
- 完成与取消通过运行状态和版本号的乐观锁原子竞争。
- 同一个 PAUSE TaskRun 的重复 resume 通过状态和版本号拒绝重复推进。

### 第一阶段范围

第一阶段使用进程内存状态，优先实现并验证：

- Draft、发布、升级、关闭和版本读取。
- `AUTO` 顺序 Task。
- `PAUSE` 外部等待与完成。
- `outputs.<field> == "<value>"` 条件路由。
- `BLOCKED.dependOn` 全部分支汇合。
- Execution 取消。

JUMP、任意表达式、超时调度、失败重试策略、数据库表结构和应用重启恢复的实现属于后续阶段；其运行语义以本 ADR 和核心设计为约束。

## 理由

统一模型可以直接对应当前核心设计和 UC 文档。运行推进集中在一个深模块中，使版本绑定、路由、分叉、汇合和状态一致性只实现一次。内存实现先验证语义，能够在数据库表和事务接口尚未最终确定时降低返工范围。

## 后果

- 新代码不能再引入旧模型术语。
- `Flow PostgreSQL 数据存储设计.md` 需要在数据库阶段补充稳定 Flow 身份、当前版本指针和 Draft 存储；现有三张表仍可作为不可变版本内容的基础。
- Task Type 扩展接口、持久化 seam 和恢复协议将在对应能力进入编码前单独设计。
- 第一阶段内存实现只作为测试支架，不进入生产运行模式；详见 ADR 0007。
