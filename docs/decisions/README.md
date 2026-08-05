# Flow 架构决策索引

## 文档定位

本目录记录 Flow 已经作出的具体架构和领域设计选择。通用开发与建模方法位于
`docs/standards/`；本目录回答“Flow 当前为什么选择这种对象、状态、模块和运行
协议”。

阅读同一主题时按 ADR 编号和正文中的修订关系确定当前决策。较新的 Accepted ADR
可以修订较早决策的局部条款；不能只读取一个早期 ADR 或从现有类结构反推设计。

历史测试报告可能保留已经移除的具体模型规范文件名，以忠实记录当次执行环境；
读取这些历史引用时，使用本索引定位当前 ADR，不回写历史报告。

## Core 组件与依赖方向

- [`ADR 0032`](0032-restore-single-server-runtime-module.md)：恢复 `gen + server`
  两个 Gradle 模块；Core、Executor、Worker 和扩展继续以 `server` 内的平级 Java
  包表达职责。
- [`ADR 0030`](0030-split-embeddable-core-from-server.md)：已被 ADR 0032 取代的
  Core Library 与薄 Server 拆分方案。
- [`ADR 0001`](0001-workflow-core-module-architecture.md)：工作流核心模块架构。
- [`ADR 0005`](0005-unify-domain-model-and-business-packages.md)：统一领域模型和
  Core 业务分包。
- [`ADR 0007`](0007-keep-test-adapters-out-of-production.md)：测试 Adapter 不进入
  生产代码。
- [`ADR 0012`](0012-separate-executor-and-worker-from-core.md)：Executor、Worker 与
  Core 平级。
- [`ADR 0023`](0023-discover-task-extensions-with-service-loader.md)：已被 ADR 0026
  取代的 ServiceLoader 历史方案。
- [`ADR 0024`](0024-separate-runnable-and-branch-task-capabilities.md)：Task 运行能力
  归属及 Executor/Worker 消费边界；其中 BranchTask 名称已由 ADR 0029 修订。
- [`ADR 0026`](0026-use-task-class-as-in-project-plugin.md)：具体 Task 作为项目内
  Plugin、真实类地址注册及严格多态绑定。
- [`ADR 0027`](0027-describe-registered-plugins-and-query-schemas.md)：已注册插件
  两级目录、可选描述元信息与按需定义 Schema 查询。
- [`ADR 0028`](0028-add-log-extension-and-task-template-expressions.md)：按能力名称
  组织新的 Task 扩展，并由受限模板表达式从运行输入提取日志消息。

目录位置和当前文件落位以 [`../project-structure.md`](../project-structure.md) 为准；
它只描述目录职责，不重新定义架构决策。

## Flow 定义与生命周期

- [`ADR 0004`](0004-use-yaml-and-exact-flow-reference.md)：YAML 定义和精确 Flow
  引用。
- [`ADR 0008`](0008-separate-flow-source-from-deployed-flow.md)：分离 FlowDraft 与
  已部署 Flow。
- [`ADR 0013`](0013-centralize-yaml-parsing-and-flow-materialization.md)：集中 YAML
  解析和 Flow 物化。
- [`ADR 0014`](0014-complete-flow-source-and-reversion-migration.md)：完成来源与
  Reversion 迁移。
- [`ADR 0018`](0018-use-flow-lifecycle-flags.md)：定义生命周期事实。
- [`ADR 0022`](0022-model-flow-draft-as-separate-aggregate.md)：FlowDraft 独立聚合
  以及当前 `deleted` 模型；它修订 ADR 0018 中的草稿表达。

## Data、Input 与 Output

- [`ADR 0019`](0019-establish-basic-data-types.md)：Data、DataType、具体 Input 类型、
  Output、定义值边界、不变量、场景和当前实施状态。

尚未确认的运行输入映射和类型比较继续记录在 ADR 0019 的“尚待确认”中，不能由
调用方或 Adapter 自行补充规则。

## Task、路由、并行与扩展

- [`ADR 0006`](0006-single-execution-branch-routing-and-join.md)：单 Execution 的
  条件路由、并行和汇合。
- [`ADR 0013`](0013-centralize-yaml-parsing-and-flow-materialization.md)：Task 随
  Flow 物化的边界。
- [`ADR 0021`](0021-require-explicit-parallel-task.md)：普通子任务默认串行，只有
  PARALLEL 显式并行。
- [`ADR 0023`](0023-discover-task-extensions-with-service-loader.md)：已被 ADR 0026
  取代的 TaskExtension/ServiceLoader 历史方案。
- [`ADR 0024`](0024-separate-runnable-and-branch-task-capabilities.md)：
  RunnableTask、历史 BranchTask、RunContext、RunResult、Worker 与 Executor 的
  职责；当前编排能力名称以 ADR 0029 为准。
- [`ADR 0026`](0026-use-task-class-as-in-project-plugin.md)：Task 直接作为 Plugin、
  canonical class name 类型和 Task 树持久化边界。
- [`ADR 0027`](0027-describe-registered-plugins-and-query-schemas.md)：全局插件目录、
  Task 元信息和 JSON Schema 定义查询边界。
- [`ADR 0028`](0028-add-log-extension-and-task-template-expressions.md)：Log 扩展、
  新扩展目录命名以及 Task 字符串模板表达式。
- [`ADR 0029`](0029-model-parallel-as-orchestration-scope.md)：以
  OrchestrationTask 统一编排能力，由 Parallel TaskRun 持有完整并行作用域，并
  定义输入扇出、未选择传播与 concurrent 契约。
- [`ADR 0031`](0031-model-pause-as-task-backed-gate.md)：Pause 直接拥有暂停前 Task、
  Resume Input、ISO 8601 duration 与 Behavior，并按 Task 即 Plugin 的结构物化。

## Execution、TaskRun、State 与调度

- [`ADR 0002`](0002-workflow-core-runtime-class-design.md)：Execution 聚合、TaskRun
  真实历史和 Executor 状态机。
- [`ADR 0006`](0006-single-execution-branch-routing-and-join.md)：单 Execution 分支、
  并行与汇合。
- [`ADR 0017`](0017-centralize-workflow-runtime-state-in-flow-domain.md)：统一 State、
  State.Type、History、服务器系统时间和持久化规则。
- [`ADR 0020`](0020-stage-executor-cycle-effects.md)：ExecutorContext、nexts 两阶段
  处理和 DefaultExecutor 提交边界。
- [`ADR 0021`](0021-require-explicit-parallel-task.md)：串行与显式并行调度。
- [`ADR 0024`](0024-separate-runnable-and-branch-task-capabilities.md)：运行能力分类和
  Worker/Executor 协作。
- [`ADR 0029`](0029-model-parallel-as-orchestration-scope.md)：Parallel 作用域、仅
  Pause TaskRun 使用 PAUSED，以及 Execution 始终保持 RUNNING 的当前规则。

## PAUSE 与外部恢复

- [`ADR 0011`](0011-require-durable-external-trigger-resume.md)：外部触发必须跨
  Server 生命周期持久恢复。
- [`ADR 0016`](0016-separate-pause-from-external-business-capabilities.md)：PAUSE
  通过 Execution 生命周期统一恢复，审批等业务能力位于 Flow Core 外。
- [`ADR 0017`](0017-centralize-workflow-runtime-state-in-flow-domain.md)：WAITING、
  Resume 和状态历史。
- [`ADR 0024`](0024-separate-runnable-and-branch-task-capabilities.md)：PAUSE 由
  Executor 直接处理、不进入 Worker 的历史决策；能力名称已由 ADR 0029 修订。
- [`ADR 0029`](0029-model-parallel-as-orchestration-scope.md)：BranchTask 迁移为
  OrchestrationTask；Pause TaskRun 使用 PAUSED，Execution 不再进入等待状态。
- [`ADR 0031`](0031-model-pause-as-task-backed-gate.md)：Pause 前置 Task、恢复输入、
  超时定义以及 `PAUSED -> RUNNING` 后由 Executor 继续推进的当前契约。

## 持久化、时间与并发

- [`ADR 0009`](0009-persist-current-core-in-postgresql.md)：Core 聚合 PostgreSQL
  持久化。
- [`ADR 0026`](0026-use-task-class-as-in-project-plugin.md)：`flow_tasks.type`、
  properties JSONB 和 parent_id 派生规则。
- [`ADR 0015`](0015-use-long-millisecond-java-time.md)：项目 Java 时间使用 Unix
  timestamp 毫秒值，数据库保留原生时间类型。
- 各聚合的锁、版本和事务边界继续由所属领域 ADR 及
  [`command-executor.md`](../standards/command-executor.md) 共同约束。

## 通用实现决策

- [`ADR 0003`](0003-use-generic-command-executor.md)：泛型 CommandExecutor。
- [`ADR 0010`](0010-use-static-create-for-domain-objects.md)：领域对象静态 create 和
  rehydrate。
- [`ADR 0025`](0025-allow-records-for-simple-boundary-contracts.md)：简单边界协议
  可以选择 Java record。

通用实施方法仍以 [`project-development.md`](../standards/project-development.md)
和 [`domain-object-modeling.md`](../standards/domain-object-modeling.md) 为准。
