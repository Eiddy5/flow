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

- [`ADR 0042`](0042-split-core-from-http-server.md)：采用 `gen + core + server` 的
  浅拆分；Core 保存完整非 HTTP Flow 能力，Server 只保存 HTTP、启动和资源，并
  通过 `api` 依赖 Core 维持 CSES 的单坐标接入。
- [`ADR 0040`](0040-bind-flow-datasource-from-host-yaml.md)：使用 Micronaut 标准
  `datasources.flow` 具名数据源，框架自动创建 DataSource 与 JOOQ；宿主只提供
  YAML、Consul 或环境变量值。
- [`ADR 0039`](0039-embed-complete-flow-server-in-cses.md)：完整 Flow 依赖同时支持
  独立启动和嵌入 CSES，由宿主 ApplicationContext 装配 Controller、传递引入的
  Core 与按真实 Java 包分组的编译期插件，并由人工 SQL 管理 Flow Schema；模块
  形态由 ADR 0042 修订。
- [`ADR 0038`](0038-keep-approval-business-in-cses.md)：审批业务、数据和可靠 Resume
  outbox 归 CSES；Flow 只保留通用 Pause 与 Execution Resume 能力。
- [`ADR 0032`](0032-restore-single-server-runtime-module.md)：已被 ADR 0042 取代的
  `gen + server` 单运行模块方案。
- [`ADR 0030`](0030-split-embeddable-core-from-server.md)：已被 ADR 0032 取代的
  Core Library 与薄 Server 拆分方案。
- [`ADR 0001`](0001-workflow-core-module-architecture.md)：工作流核心模块架构。
- [`ADR 0005`](0005-unify-domain-model-and-business-packages.md)：统一领域模型和
  Core 业务分包。
- [`ADR 0007`](0007-keep-test-adapters-out-of-production.md)：测试 Adapter 不进入
  生产代码。
- [`ADR 0012`](0012-separate-executor-and-worker-from-core.md)：Executor、Worker 与
  Core 平级。
- [`ADR 0046`](0046-define-typed-dispatch-queue-framework.md)：在 Gradle Core 中建立
  与 Core、Executor、Worker 平级的类型化 Dispatch Queue Interface；业务 Module
  拥有 Event，具体 Adapter 拥有存储、恢复和消费机制，当前运行链路保持同步。
- [`ADR 0047`](0047-implement-default-dispatch-queue.md)：以具名 `flow` JOOQ、JSONB、
  `FOR UPDATE SKIP LOCKED` 和周期轮询实现 Default Dispatch Queue；所有传输类别共享
  `flow_queues`，由 `queue_type + queue_name` 逻辑隔离，当前只实现 `DISPATCH`；
  同步发布直接使用 Event 的可空 DSL 或 Queue 自有事务，异步发布始终使用自有事务；
  Queue 通过 `JsonFactory` 和 `Class<T>` 统一完成不含 DSL 的 Event JSONB 快照与恢复。
- [`ADR 0023`](0023-discover-task-extensions-with-service-loader.md)：已被 ADR 0026
  取代的 ServiceLoader 历史方案。
- [`ADR 0024`](0024-separate-runnable-and-branch-task-capabilities.md)：Task 运行能力
  归属及 Executor/Worker 消费边界；其中 BranchTask 名称已由 ADR 0029 修订。
- [`ADR 0026`](0026-use-task-class-as-in-project-plugin.md)：具体 Task 作为项目内
  Plugin、真实类地址注册及严格多态绑定。
- [`ADR 0027`](0027-describe-registered-plugins-and-query-schemas.md)：已注册插件
  按真实 Java 包分组的两级目录、可选描述元信息与按需定义 Schema 查询。
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
- [`ADR 0043`](0043-use-serializable-object-for-json-models.md)：JSON 模型统一继承
  PAAS `SerializableObject`，并由 Jackson Databind 作为 Micronaut HTTP codec，移除
  逐类 Micronaut Serialization 注解。

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
  真实 Java 包分组、Task 元信息和 JSON Schema 定义查询边界。
- [`ADR 0028`](0028-add-log-extension-and-task-template-expressions.md)：Log 扩展、
  新扩展目录命名以及 Task 字符串模板表达式。
- [`ADR 0044`](0044-remove-bean-context-from-run-context.md)：RunContext 移除通用
  BeanContext 查找，仅保留明确的 RunnableTask 调用期运行能力。
- [`ADR 0045`](0045-pass-execution-and-inputs-through-run-variables.md)：通过不可变
  RunContext variables 传递当前 Execution 与 TaskRun inputs，并由便捷方法解析。
- [`ADR 0029`](0029-model-parallel-as-orchestration-scope.md)：以
  OrchestrationTask 统一编排能力，由 Parallel TaskRun 持有完整并行作用域，并
  定义输入扇出、未选择传播与 concurrent 契约。
- [`ADR 0031`](0031-model-pause-as-task-backed-gate.md)：Pause 直接拥有暂停前 Task、
  Resume Input、ISO 8601 duration 与 Behavior，并按 Task 即 Plugin 的结构物化。
- [`ADR 0036`](0036-model-loop-and-loop-until-as-recoverable-orchestration-scopes.md)：
  Loop 固定次数循环、Loop Until 后置条件循环、每轮 TaskRun 身份和可恢复调度协议。
- [`ADR 0037`](0037-centralize-condition-expressions-in-express-domain.md)：由
  Express 值对象统一 Route 与 Loop Until 的受限 outputs 条件解析和求值，并由
  消费方分别保护引用范围；TemplateExpression 同属 expressions 领域，但保持独立
  的模板语法、字符串结果和缺值失败语义。

## Execution、TaskRun、State 与调度

- [`ADR 0002`](0002-workflow-core-runtime-class-design.md)：Execution 聚合、TaskRun
  真实历史和 Executor 状态机。
- [`ADR 0006`](0006-single-execution-branch-routing-and-join.md)：单 Execution 分支、
  并行与汇合。
- [`ADR 0017`](0017-centralize-workflow-runtime-state-in-flow-domain.md)：统一 State、
  State.Type、History、服务器系统时间和持久化规则。
- [`ADR 0034`](0034-persist-state-as-one-jsonb-value.md)：Execution 与 TaskRun 将完整
  `current + history` 作为单一 State JSONB 值对象持久化。
- [`ADR 0020`](0020-stage-executor-cycle-effects.md)：ExecutorContext、nexts 两阶段
  处理和 DefaultExecutor 提交边界。
- [`ADR 0021`](0021-require-explicit-parallel-task.md)：串行与显式并行调度。
- [`ADR 0024`](0024-separate-runnable-and-branch-task-capabilities.md)：运行能力分类和
  Worker/Executor 协作。
- [`ADR 0029`](0029-model-parallel-as-orchestration-scope.md)：Parallel 作用域、仅
  Pause TaskRun 使用 PAUSED，以及 Execution 始终保持 RUNNING 的当前规则。
- [`ADR 0036`](0036-model-loop-and-loop-until-as-recoverable-orchestration-scopes.md)：
  同一 Task 的多轮 TaskRun、iteration 运行事实以及 Loop 作用域收敛规则。

## PAUSE 与外部恢复

- [`ADR 0038`](0038-keep-approval-business-in-cses.md)：CSES Approval 不保存流程
  拓扑；它通过 Flow 的公开 ExecutionService 恢复准确的 Pause。
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

- [`ADR 0048`](0048-isolate-schema-ddl-and-pluralize-table-names.md)：保留单一开发期
  Schema 入口，按表隔离 DDL，表名使用小写蛇形且最后一个单词为以 `s` 结尾的复数；
  `task_runs` 与 `external_tasks` 同步进入 JOOQ 和 Adapter 边界。
- [`ADR 0039`](0039-embed-complete-flow-server-in-cses.md)：Flow 使用具名数据库，
  但不再携带或执行 Flyway；启动前由部署人员手工执行完整建表基线。
- [`ADR 0038`](0038-keep-approval-business-in-cses.md)：Approval 持久化属于 CSES，
  不进入 Flow Schema 或 Flow 事务。
- [`ADR 0033`](0033-use-single-development-database-baseline.md)：开发期只维护一个
  完整 PostgreSQL 逻辑基线；Schema 变化后显式重建数据库，不保留旧数据升级路径；
  物理文件布局由 ADR 0048 修订。
- [`ADR 0034`](0034-persist-state-as-one-jsonb-value.md)：使用单一 `state jsonb`
  保存完整 State，通过表达式索引查询 current，不保留冗余 status。
- [`ADR 0009`](0009-persist-current-core-in-postgresql.md)：Core 聚合 PostgreSQL
  持久化。
- [`ADR 0026`](0026-use-task-class-as-in-project-plugin.md)：`flow_tasks.type`、
  properties JSONB 和 parent_id 派生规则。
- [`ADR 0015`](0015-use-long-millisecond-java-time.md)：项目 Java 时间使用 Unix
  timestamp 毫秒值，数据库保留原生时间类型。
- 各聚合的锁、版本和事务边界继续由所属领域 ADR 及
  [`command-executor.md`](../standards/command-executor.md) 共同约束。

## 通用实现决策

- [`ADR 0041`](0041-extract-shared-domain-capabilities.md)：在 `core/domains` 根目录
  提供 Identified、Auditable、Deletable、Lockable 与 ActorRef；能力接口同时覆盖
  行为、查询和失败校验，Session 只作为可信调用上下文传入。
- [`ADR 0035`](0035-unify-flow-demo-runtime-environment.md)：页面统一使用 `demo`
  环境，并通过平台托管开关在具名数据源与独立 PostgreSQL Adapter 之间互斥选择。
- [`ADR 0003`](0003-use-generic-command-executor.md)：泛型 CommandExecutor。
- [`ADR 0010`](0010-use-static-create-for-domain-objects.md)：领域对象静态 create 和
  rehydrate。
- [`ADR 0025`](0025-allow-records-for-simple-boundary-contracts.md)：简单边界协议
  可以选择 Java record。

通用实施方法仍以 [`project-development.md`](../standards/project-development.md)
和 [`domain-object-modeling.md`](../standards/domain-object-modeling.md) 为准。
