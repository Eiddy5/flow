# 工作流核心 Java 模型规范

## 包与依赖方向

- `core/domains` 只放领域对象、领域能力接口、紧贴能力的直接调用契约、状态值对象
  和领域状态枚举，不得放 Factory、Reader、Snapshot、Reference、DTO、解析中间
  类型或持久化映射对象。
- `core/domains/tasks` 统一拥有 Task、RunnableTask、BranchTask，以及 RunnableTask
  的直接调用契约 RunContext、RunResult；Worker 与 Executor 只能消费这些能力。
- `core/serializers` 保持扁平，只提供与业务类型解耦的格式能力；当前
  `YamlParser` 是 YAML 唯一语法入口。
- `core/plugins` 保持扁平，统一保存插件 SPI、classpath 发现、唯一注册和
  Task 类型分派 Interface 与实现；`TaskTypeDispatcher` 不属于 Flow 领域，且
  该目录不能依赖 `extensions` 中的具体扩展。
- Repository 接口放在 `core/repositories/<业务模块>`；生产实现放在
  `infrastructure` 的真实持久化适配器中。
- JOOQ 生成类只能通过具体 Repository `entries` 子包中的 `XxxEntry` 使用；
  Entry、领域转换和 Map 写入规则见 `docs/standards/jooq.md`。
- 内存 Repository、伪事务和无连接 JOOQ Factory 只能存在于 `src/test`，
  不能注册为生产 Bean。
- Service 放在 `core/services/<业务模块>`。
- Command、Handler 和 Query 必须在各自 Core 技术目录下继续按 `flows`、
  `executions` 或真正跨领域的 `shared` 分包。现存 `externaltasks` 是迁移遗留，
  新能力不得继续依赖。
- Executor 状态机、上下文和 nexts 批次逻辑统一放在与 `core` 平级的
  `org.cses.flow.executor`；WorkerDispatcher、WorkerTask 和 WorkerTaskResult
  统一放在同级的 `org.cses.flow.worker`。Core 内不得建立 `executors` 或
  `workers` 目录，顶层运行时包也不得复制 Task 能力接口。
- CommandHandler 按业务模块放在 `core/handlers` 的对应子目录中，不得直接
  平铺在 `core/handlers` 下；运行协调由顶层 executor 的 DefaultExecutor
  负责，Core 不建立第二个 ExecutionHandler。
- Controller 位于 Core 外部。
- 具体 Task 与 TaskExtension 是扩展 Adapter，放在 `extensions/tasks`；具体
  Task 自身实现 RunnableTask 或 BranchTask，不再建立 WorkerTaskHandler。

类型的语义所有权、Interface 与直接契约归属统一遵守
[`domain-object-modeling.md`](domain-object-modeling.md)，不能因为某个类型当前由
Executor、Worker 或 Adapter 调用，就把它移动到调用方 Module。

写链路固定为：

```text
Service -> CommandExecutor -> CommandHandler -> Repository/Domain
```

运行推进在 CommandHandler 内委托：

```text
DefaultExecutor -> ExecutorService -> ExecutionRepository/WorkerDispatcher
```

查询链路固定为：

```text
Service -> QueryHandler -> Repository/JOOQ
```

### Core 业务分包细则

- Core 技术职责目录下的业务类型继续按小写英文复数业务模块分包，例如 `flows`
  和 `executions`；现存 `externaltasks` 是迁移遗留，不能作为新模块示例。
- 同一业务链路在 Command、Handler、Domain、Query、Repository 和 Service 中使用
  一致的业务模块名；Java `package` 必须与文件目录完全一致。
- `shared`、`support`、`common` 不能作为无法归类代码的容器。只有被多个业务
  Module 稳定复用且不属于任一业务 Module 的类型才允许进入明确的共享模块。
- 一个功能涉及多个业务 Module 时，按各类型实际承担的语义分别归档，不能把整条
  调用链全部放进发起方模块。
- `src/test/java` 的测试包镜像生产包；移动生产类型时同步更新测试、组件扫描和
  架构约束测试。
- `core/serializers` 与 `core/plugins` 是明确的扁平目录例外，不继续按业务模块
  分包；前者只保存格式能力，后者只保存插件运行机制。

## 领域模型

- FlowDraft、Flow 和 Execution 分别维护自己的领域规则。
- PAUSE 是 Flow Core 的外部等待编排能力并实现 BranchTask；Executor 直接使其
  TaskRun 进入 WAITING，不经过 Worker。等待事实由 Execution 中的 WAITING
  PAUSE TaskRun 表达。
- 禁止 Handler、Service、Executor 或 Worker 直接修改领域字段。
- 所有状态变化必须调用领域方法。
- `FlowDraft` 是只保存原始 YAML 的来源草稿聚合，没有正式
  `reversion`；`Flow` 是部署时完成解析和校验后产生的完整定义聚合。
- FlowDraft 与 Flow 是部署映射关系，不是继承关系，也不能使用
  `status + nullable version` 合并成一个领域对象。
- Flow 直接持有不可变 Input、Output 和 Task；TaskRun、Execution 查询也直接
  返回对应领域对象的隔离副本，不建立 Snapshot 复制模型。
- Data 是非泛型基础接口，只提供 `getKey()` 和返回独立 DataType 的
  `getType()`；`Input<T>` 是抽象输入基类，具体 Input 子类固定 DataType 并
  实现校验，Output 直接实现 Data。它们作为所属 Flow 或 Task 聚合内对象存在，
  不保存 TaskRun 的实际值。完整规则见
  [`data-domain-model.md`](data-domain-model.md)。
- Task 是 Flow 聚合内实体，没有独立 Repository、状态、审计字段或
  `reversion`；Flow 从通用只读映射一次性创建完整 Task，不存在或持久化
  `id = null` 的中间 Task。
- Task 的 `id` 跨 Flow reversion 稳定，TaskRun 以 `taskId` 引用该身份；
  完整规则见 [`task-domain-model.md`](task-domain-model.md)。
- 普通 Task 的直接子 Task 按定义顺序串行推进；只有显式 ParallelTask 的直接
  子 Task 可以形成同一并行批次。并行不根据同级数量或多个匹配 route 推断。
- FlowDraft 与 Flow 的类型表达草稿和正式定义角色，不保存 `draft`；
  两者创建时均使用 `deleted=false`。
- `deploy` 是产生 Flow 和正式 `reversion` 的唯一业务入口；`delete` 保留原
  `reversion`，不生成新版本。
- 仓储负责聚合副本隔离，Service/Query 不通过重复模型实现只读。
- Execution 是一次启动实例，不是游标。
- Execution 内的 TaskRun 列表保存真实执行历史；列表顺序是内存顺序。
- Execution 和 TaskRun 的目标字段、方法、四类状态机及聚合边界见
  [`execution-domain-model.md`](execution-domain-model.md)。
- 工作流统一运行状态由 Flow 定义域的 `State` 值对象及其内部 `State.Type`
  定义。Execution 和 TaskRun 持有 State，不能定义各自的状态枚举。
- 数据库使用独立顺序列还原 TaskRun 列表，该顺序列不进入领域对象。
- TaskRun.parentId 表示真实父 TaskRun，不表示前驱或调度原因。
- TaskRun 通过稳定 taskId 关联 Execution 绑定 Flow reversion 中的 Task 定义。
- 所有技术 ID 使用 `String`，且只通过 `StringUtil.newId()` 生成。
- 领域对象的普通业务创建统一由类型自身的 `public static create(...)` 完成；
  Core 不建立 `factories` 技术目录，也不通过工厂类或工厂接口包装领域创建。
- 领域对象构造方法不对领域类型外部公开；Repository Adapter 恢复持久化状态时
  使用静态 `rehydrate(...)`，不能调用 `create(...)` 生成新身份。
- Domain 类型必须使用 `class` 保护构造和领域行为；简单边界协议是否使用
  `record`，按 [`project-development.md`](project-development.md) 判断。

## Executor 与 Worker

- Executor 和 Worker 是与 Core 平级的运行组件；Task 能力、领域事实、
  Repository 端口和对外用例仍属于 Core。
- ExecutorContext 是一次调度循环的可变工作单元，只持有精确 Flow、
  Execution、本轮 nexts、Runnable WorkerTask、Branch TaskRun 和状态变化增量；
  它可重建且不持久化。
- Session 和 DSLContext 不进入 ExecutorContext，只在 DefaultExecutor 的提交
  与单次 RunContext 构造边界传入。
- ExecutorService 是状态机，不访问 Repository/JOOQ：`handleNext()` 只把下一
  批 TaskRun 计划加入 Context，`onNexts()` 才启动 Execution、原子并入 TaskRun
  并按互斥能力分别形成 Runnable WorkerTask 或 Branch TaskRun。
- `handleNext()` 不改变 Execution；同一 nexts 计划只能被 `onNexts()` 消费一次。
- DefaultExecutor 管理聚合生命周期、同事务中间保存、Worker 投递、结果合并、
  取消和异常边界。
- 每个被调度的具体 Task 必须恰好实现 RunnableTask 或 BranchTask。只有
  RunnableTask 形成 WorkerTask；BranchTask 由 Executor 直接完成等待、结构节点
  完成和并行展开。
- WorkerDispatcher 不按类型选择 Handler，直接调用具体 RunnableTask 的
  `run(RunContext)`。WorkerTask 不携带 Session、DSLContext、Execution 或可变
  TaskRun。
- RunContext 每次只服务一个 RunnableTask，只提供 Session、DSLContext 和只读
  inputs，不暴露 Task、WorkerTask、Execution、TaskRun 或 taskRunId。
- Worker 返回结果事实，`RunResult` 与 `WorkerTaskResult.targetState` 使用统一的
  `State.Type`，只允许 COMPLETED 或 TERMINATED，再由 ExecutorService 调用
  Execution 领域方法合并并生成真实 History；WAITING 只由 BranchTask 的 Executor
  路径产生。
- 延迟、子流程、Loop 和 durable outbox 尚无已确认协议，不使用 Object 列表或
  空壳类型预占 Context；对应协议确认后以明确效果类型扩展。

## 状态与 PAUSE

本节统一定义 State 的字段、通用迁移、服务器系统时间和持久化规则；Execution 与
TaskRun 的具体合法路线由各自领域规范进一步收紧。

- Execution 和 TaskRun 统一使用 Flow 定义域 `State`，其 `State.Type` 为
  `CREATED/RUNNING/WAITING/COMPLETED/TERMINATED`，分别归入创建和运行、等待、
  正常终止和异常终止四类。
- State 内部保存 `Type current` 和有序 `List<State.History> history`；
  History 保存 state 与发生时的 Unix timestamp 毫秒值 date。history 非空、
  第一项必须为 CREATED，current 必须等于最后一项 state。运行对象通过 `state()`
  返回完整 State，不提供直接返回 Type 的状态旁路。
- `State.created()` 产生 CREATED 首条历史，通用迁移由
  `State.withState(...)` 及无时间参数快捷方法管理；Execution 和 TaskRun 继续
  限制自己的合法路线和聚合业务前置条件。
- State 每次真实创建或成功迁移时在内部读取一次服务器
  `System.currentTimeMillis()` 并写入 History；调用方、Worker 和外部请求不能
  提交或覆盖状态时间。
- State 的通用迁移超集为 `CREATED -> RUNNING/TERMINATED`、
  `RUNNING -> WAITING/COMPLETED/TERMINATED`、
  `WAITING -> RUNNING/COMPLETED/TERMINATED`；COMPLETED 和 TERMINATED 为终态。
- `State.rehydrate(current, history)` 只允许可信 Repository Adapter 使用已保存
  历史重建，不重新读取系统时间，并校验起点、终点和全部迁移路线。
- Repository 同时持久化 current 和完整 history；Worker、Executor、Handler 和
  Service 只能通过 Execution 或 TaskRun 领域行为触发状态变化，不能直接替换 State。
- State 时间测试使用调用前后服务器 timestamp 区间断言，不要求相邻 History 的
  date 严格递增；持久化往返必须完整保持 current、state、date 和顺序。
- FlowDraft/Flow 类型与 `deleted` 只表达 Flow 定义生命周期，不属于运行 State；不得建立
  `FlowDefinitionStatus` 或其他定义状态枚举。
- PAUSE 是 BranchTask 类型；Executor 使对应 TaskRun 从 CREATED、RUNNING 进入
  WAITING。只有
  不存在其他 CREATED/RUNNING 工作时，Execution 才进入稳定 WAITING。
- WAITING PAUSE TaskRun 自身表达持久化等待事实，不建立第二个 Flow Core 等待
  聚合；审批待办等外部对象仍属于对应业务能力。
- `ExecutionService.resume(...)` 是唯一公开恢复入口；它直接完成原 PAUSE
  TaskRun，使 Execution 回到 RUNNING，再由 ExecutorService 安排下一任务。
- 面向用户的受派、待办和权限查询属于外部业务能力。
- 完整职责和状态规则见
  [`pause-domain-model.md`](pause-domain-model.md)。

## 事务

- 所有写操作都通过 Command，使用调用该命令的 Session。
- JOOQ DSLContext 是命令事务边界，不建立嵌套事务。
- 同步 Task 可以在一个命令内连续运行至卡点或终态。
- RunnableTask 保存自身业务记录时通过单次 RunContext 使用当前命令 DSLContext。
- RunnableTask 明确返回 TERMINATED 并携带 error 属于可提交的失败结果；未处理
  异常必须回滚。
- 修改已有 Execution 的一个命令最多增加一次聚合 lockVersion。

## 测试

- 场景测试只通过 Service 驱动写操作。
- 必须断言 Flow 绑定版本、Execution State、TaskRun 顺序和 State。
- PAUSE 稳定场景断言 Execution 与原 TaskRun 都是 WAITING。
- resume 场景通过 ExecutionService 驱动，并证明没有重复创建 PAUSE TaskRun。
- 取消场景必须证明其他 Execution 不受影响。
- PostgreSQL Adapter 完成前，验证顺序为目标测试和完整测试；完成后增加应用
  启动、数据库迁移和 HTTP 响应验证。
