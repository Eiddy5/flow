# Flow 架构决策索引

当前运行时持久化边界以 [ADR 0084](0084-save-domain-snapshots-without-business-transactions.md) 为准：
普通读取领域、调用领域方法、完整快照保存；不使用业务事务或锁定读取。
下列早期 ADR 的业务事务表述按该决策修订；当前 Executor 的 Pulsar 传输见 ADR 0094。
显式 `lock` 随对象携带，普通 `save` 由实现层 `CasRepository` 统一执行 CAS，
不再维护元数据缓存或作用域，见 [ADR 0097](0097-carry-lock-in-aggregate-and-inherit-cas-repository.md)。

## 文档定位

本目录记录 Flow 已经作出的具体架构和领域设计选择。通用开发与建模方法位于
`docs/standards/`；本目录回答“Flow 当前为什么选择这种对象、状态、模块和运行
协议”。

阅读同一主题时按 ADR 编号和正文中的修订关系确定当前决策。较新的 Accepted ADR
可以修订较早决策的局部条款；不能只读取一个早期 ADR 或从现有类结构反推设计。

历史测试报告可能保留已经移除的具体模型规范文件名，以忠实记录当次执行环境；
读取这些历史引用时，使用本索引定位当前 ADR，不回写历史报告。

## Core 组件与依赖方向

- [`ADR 0096`](0096-remove-legacy-postgres-queues.md)：清理旧 PostgreSQL 队列、专属契约、
  压测及队列表基线/生成类，保留 PAAS Pulsar 为唯一运行传输。

- [`ADR 0094`](0094-use-pulsar-for-executor-queues.md)：两条 Executor 队列切换到注解式
  Pulsar，公共 Queue 只保留发布能力，删除旧默认队列工厂，不接入通知。
- [`ADR 0092`](0092-add-annotation-driven-paas-pulsar-queues.md)：新增注解式 PAAS Pulsar
  队列与方法消费者，复用 PAAS 发送、消费和 ACK/NACK；保留现有 PostgreSQL Executor
  队列及其 Interface 的初始阶段，不增加 Flow 队列 YAML；默认链路已由 ADR 0094 切换。
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
  拥有 Event，具体 Adapter 拥有存储、恢复和消费机制；其中“运行链路保持同步”的
  阶段性范围已由 ADR 0051 修订。
- [`ADR 0047`](0047-implement-default-dispatch-queue.md)：以具名 `flow` JOOQ、JSONB、
  `FOR UPDATE SKIP LOCKED` 和周期轮询实现 Default Dispatch Queue；所有传输类别共享
  `queues`，由 `queue_type + queue_name` 逻辑隔离，当前只实现 `DISPATCH`；
  Queue 通过 `JsonFactory` 和 `Class<T>` 统一完成 Event 业务 JSONB 快照与恢复；其
  Event 自带事务条款已由 ADR 0066 修订。
- [`ADR 0060`](0060-publish-queue-transactions-explicitly.md)：为 DispatchQueue 增加显式
  `emitInTransaction` 发布 Interface；其中保留 `Event.dsl()` 的兼容条款已由 ADR 0066
  修订。
- [`ADR 0066`](0066-remove-transaction-state-from-queue-events.md)：从 Event Interface
  删除 `dsl()`，普通发布固定使用 Queue 自有事务，调用方事务只通过
  `emitInTransaction(...)` 显式传入；ExecutionCommand 与 ExecutorEvent 均为纯 payload，
  ExecutorEvent 仅保留租户、Execution 身份和生命周期类型，Flow 由 Execution 反查。
- [`ADR 0051`](0051-start-executions-through-dispatch-queue.md)：
  `ExecutionService` 构造并投递 Executor `Create` Command，普通启动返回 Queue 受理
  回执；外部命令由 `ExecutionCommandEventHandler` 校验并原子投递内部
  `ExecutorEvent`，由 `ExecutorEventMessageHandler` 负责一个周期的提交；Consumer 异常保留
  消息重试，确定性 Task 异常落为 `FAILED`。两阶段启动条款由 ADR 0068 取代。
- [`ADR 0068`](0068-remove-two-phase-execution-start.md)：删除 `createPending` 与
  `continueExecution`，Execution 只通过一次完整 `create` 受理；Service 在发布前确认
  当前 Flow、inputs、稳定 id 和发起人，Consumer 原子物化 Execution 并交接首个事件。
- [`ADR 0080`](0080-start-execution-with-optional-flow-version.md)：Execution 启动统一
  使用 `create(session, key, Optional<version>, inputs)`；版本为空选择最新 Flow，版本
  不为空精确选择指定 Flow 版本，不保留旧启动重载。
- [`ADR 0081`](0081-accept-preallocated-execution-id.md)：保留普通 Flow 启动入口，并
  增加接受预分配稳定 Execution ID 的入口；宿主可先提交自身聚合引用，再通过同一条
  校验与 Queue 链路完整启动 Flow，当前不增加跨库可靠启动协议。
- [`ADR 0082`](0082-orchestrate-host-execution-actions-after-local-transactions.md)：
  宿主事务 Handler 只提交本地聚合事实，Service 在事务返回后编排 Create、Cancel、
  Resume 与 Rewind；Rewind 通过无副作用计划保留精确影响范围和本地先提交顺序。
- [`ADR 0059`](0059-route-executor-state-handoffs-through-executor-event-queue.md)：
  保持泛型 `ExecutorEventHandler<T>` 契约不变；外部 Command 只进入
  `ExecutionCommandEventHandler`，内部状态交接统一使用 `ExecutorEvent` Queue，
  `ExecutorContext` 只组织单个内部周期，不跨 Queue 传递。
- [`ADR 0023`](0023-discover-task-extensions-with-service-loader.md)：已被 ADR 0026
  取代的 ServiceLoader 历史方案。
- [`ADR 0024`](0024-separate-runnable-and-branch-task-capabilities.md)：Task 运行能力
  归属及 Executor/Worker 消费边界；其中 BranchTask 名称已由 ADR 0029 修订。
- [`ADR 0026`](0026-use-task-class-as-in-project-plugin.md)：具体 Task 作为项目内
  Plugin、真实类地址注册及严格多态绑定。
- [`ADR 0027`](0027-describe-registered-plugins-and-query-schemas.md)：已注册插件
  按真实 Java 包分组的两级目录、可选描述元信息与按需定义 Schema 查询。
- [`ADR 0057`](0057-declare-flow-usage-examples-on-plugins.md)：以独立 `Example` 作为
  `@Plugin.examples` 的值类型，随插件详情提供可物化的 Flow YAML 使用示例。
- [`ADR 0028`](0028-add-log-extension-and-task-template-expressions.md)：按能力名称
  组织新的 Task 扩展，并由受限模板表达式从运行输入提取日志消息。
- [`ADR 0077`](0077-remove-automatic-task-use-log.md)：移除没有业务语义的
  `AutomaticTask`，使用 `Log` 替代无副作用的流程推进步骤。

目录位置和当前文件落位以 [`../project-structure.md`](../project-structure.md) 为准；
它只描述目录职责，不重新定义架构决策。

## Flow 定义与生命周期

- [`ADR 0083`](0083-allocate-flow-version-in-repository.md)：每次保存都由 Repository
  按全部历史版本分配 `company_id + key` 范围内的下一版本并追加新行；草稿、正式定义
  和删除状态共享非空版本序列，`flows.id` 只标识数据库行。
- [`ADR 0069`](0069-unify-flow-draft-and-deployed-definition.md)：草稿与正式版本统一为
  携带 `source` 的 `Flow` 聚合类型，以默认 `draft=true` 的布尔属性区分状态，
  共用一个 Repository 和 `flows` 表；它取代独立 FlowDraft 聚合与表的方案，版本与
  追加保存条款由 ADR 0083 修订。
- [`ADR 0004`](0004-use-yaml-and-exact-flow-reference.md)：YAML 定义和精确 Flow
  引用。
- [`ADR 0008`](0008-separate-flow-source-from-deployed-flow.md)：分离 FlowDraft 与
  已部署 Flow；已由 ADR 0069 取代。
- [`ADR 0013`](0013-centralize-yaml-parsing-and-flow-materialization.md)：集中 YAML
  解析和 Flow 物化。
- [`ADR 0014`](0014-complete-flow-source-and-reversion-migration.md)：完成来源与
  Reversion 迁移；独立来源聚合条款已由 ADR 0069 取代。
- [`ADR 0018`](0018-use-flow-lifecycle-flags.md)：定义生命周期事实。
- [`ADR 0022`](0022-model-flow-draft-as-separate-aggregate.md)：FlowDraft 独立聚合；
  已由 ADR 0069 取代，独立 `deleted` 字段此前由 ADR 0067 取代。
- [`ADR 0063`](0063-bind-flow-snapshots-by-key-and-version.md)：Flow 快照、Task 快照
  和 Execution 统一按 `company_id + flow_key + flow_version` 绑定；`flows.id` 仅
  保留为技术行 ID，Task 不拥有独立版本。
- [`ADR 0070`](0070-use-flow-id-for-flow-repository-selectors.md)：Flow Repository 统一
  使用 `FlowId(companyId, key, version)` 业务选择器；有 version 时精确查询正式版本，
  无 version 时查询最新正式版本或最新活动草稿，实体仍使用字符串 `Flow.id`；草稿
  选择语义由 ADR 0083 修订。
- [`ADR 0071`](0071-remove-lockable-from-domain.md)：锁与并发协议不进入 Flow 领域；
  业务唯一键、行锁、CAS 和事务隔离由数据库 Schema、Repository 与 Queue Adapter
  在各自基础设施边界负责。对象携带版本元数据的限制已由 ADR 0097 修订。

## Data、Input 与 Output

- [`ADR 0099`](0099-resolve-task-orchestration-as-read-only-plans.md)：三类 Task 互斥能力，
  resolveNexts 返回 Task/TaskRun 列表、resolveState 返回 State.Type、Executor 统一应用，以及独立子执行请求与结果。

- [`ADR 0098`](0098-run-subflow-as-linked-execution.md)：SubFlow 统一 inputs、独立运行、
  精确父调用关联、等待和结果返回；流程引用字段由 SubFlow 直接持有。首次调用闭环不包含重试或重启恢复。
- [`ADR 0095`](0095-return-typed-task-outputs.md)：Task 类型声明具体 Output，替代
  RunResult 与 Task 可配置输出列表，结果经 TaskRun 投影到后续 RunContext。

- [`ADR 0019`](0019-establish-basic-data-types.md)：Data、DataType、具体 Input 类型、
  Output、定义值边界、不变量、场景和当前实施状态。
- [`ADR 0088`](0088-let-input-own-field-binding.md)：Input 自行处理字段取值、默认值、
  转换和校验；Flow/Pause 仅管理声明集合，持久化层仅物化并触发定义检查。
- [`ADR 0089`](0089-validate-input-during-materialization.md)：Input 在 JSON/YAML Creator
  与 Builder 共用的构造路径完成定义校验，移除外部事后校验和无参/Setter 半成品。
- [`ADR 0090`](0090-register-host-input-types.md)：宿主业务 Input 按类加入插件注册表，
  定义类型与基础值类型分离，复用受控绑定和持久化入口。
- [`ADR 0093`](0093-discover-inputs-from-jackson-type-names.md)：自动索引 JsonTypeName，
  内置和业务 Input 共用 Jackson 原生多态，PAAS/JSON/YAML 与目录 Schema 统一。
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
- [`ADR 0057`](0057-declare-flow-usage-examples-on-plugins.md)：插件通过
  `@Plugin.examples` 声明 Flow YAML 使用示例，详情查询统一返回示例元信息。
- [`ADR 0028`](0028-add-log-extension-and-task-template-expressions.md)：Log 扩展、
  新扩展目录命名以及 Task 字符串模板表达式。
- [`ADR 0044`](0044-remove-bean-context-from-run-context.md)：RunContext 移除通用
  BeanContext 查找，仅保留明确的 RunnableTask 调用期运行能力。
- [`ADR 0045`](0045-pass-execution-and-inputs-through-run-variables.md)：通过不可变
  RunContext variables 传递当前 Execution、Execution 级 Flow inputs、当前/父 TaskRun
  调用期身份与 TaskRun inputs 的历史协议；其 `$flow.*` 键和对象传递方式已由
  ADR 0076 取代。
- [`ADR 0058`](0058-remove-dsl-from-run-context.md)：RunContext 不再暴露
  `DSLContext`；ExecutorEventHandler 拥有执行提交所需的事务 DSL。其 Worker 继续传递
  Session 的历史部分已由 ADR 0076 取消。
- [`ADR 0029`](0029-model-parallel-as-orchestration-scope.md)：以
  OrchestrationTask 统一编排能力，由 Parallel TaskRun 持有完整并行作用域，并
  定义输入扇出、未选择传播与 concurrent 契约。
- [`ADR 0031`](0031-model-pause-as-task-backed-gate.md)：Pause 直接拥有暂停前 Task、
  Resume Input、ISO 8601 duration 与 Behavior，并按 Task 即 Plugin 的结构物化。
- [`ADR 0065`](0065-allow-pause-continuation-tasks.md)：Pause 同时拥有暂停前专有
  `onPause` Task 和恢复后继承的普通 `tasks`；其中普通 `tasks` 已由 ADR 0074 取消。
- [`ADR 0036`](0036-model-loop-and-loop-until-as-recoverable-orchestration-scopes.md)：
  Loop 固定次数循环、Loop Until 后置条件循环、每轮 TaskRun 身份和可恢复调度协议。
- [`ADR 0078`](0078-reuse-generation-for-rewind-fragments-and-loop-rounds.md)：以拥有者
  级 Generation 统一记录退回片段和 Loop/LoopUntil 轮次的 Current、History 与原因；
  Loop 最新轮次不再由子 TaskRun 扫描推导。
- [`ADR 0074`](0074-separate-condition-and-structural-task-capabilities.md)：Task 只
  保留共同字段，Branch 独占 tasks，Route 保存 `route` 原文并按需形成 Condition，
  LoopUntil 直接组合 Condition；Condition 只把完整 `{{ path.to.value }}` 识别为引用，
  其余操作数按常量处理；dependOn 留给 DAG。固定 scope 已由 ADR 0076 取消。
- [`ADR 0076`](0076-build-one-run-variable-tree-for-runtime-expressions.md)：由
  RunVariables Builder 把 Flow、Execution、Task 和 TaskRun 事实投影为统一不可变变量
  树；RunContext 只保存该树且不保存 Session 或重复身份，Condition 与模板共用完整
  Map 路径并取消固定 scope；Route 先创建并启动自身 TaskRun，再计算 Condition，未
  命中时完成自身但不创建子 TaskRun。
- [`ADR 0079`](0079-record-unmatched-routes-as-skipped.md)：Route 在自己的 TaskRun
  已创建并开始后计算条件；未命中时该 TaskRun 以 `SKIPPED` 收敛且不创建子
  TaskRun，Execution 继续正常推进。
- [`ADR 0052`](0052-bind-confirmed-flow-inputs-to-safe-task-routes.md)：Flow 启动时
  规范化并持久保持 typed inputs 到 `Execution.inputs`，Task Route 可用受限运算符读取
  inputs；宿主只能提交结构化字段映射，不能注入脚本或任意表达式。
- [`ADR 0055`](0055-add-flow-level-variables.md)：Flow Reversion 持有简单的
  `Map<String, Object>` 流程级变量，Route 和 RunContext 可只读读取，不复制到
  TaskRun，也不与启动时的 typed inputs 混用；当前表达式路径名称由 ADR 0076 统一为
  `vars.<key>`。

## Execution、TaskRun、State 与调度

- [ADR 0087](0087-derive-execution-snapshots-on-replay.md)：退回派生新 Execution、两字段 Origin、独立继承快照、单 SQL 原子交接及来源查询。

- [`ADR 0051`](0051-start-executions-through-dispatch-queue.md)：Execution 启动采用
  持久化 Queue 的异步受理边界与至少一次消费协议。
- [`ADR 0050`](0050-bind-durable-external-business-to-exact-executions.md)：已被 ADR 0068
  取代的外部业务两阶段精确物化方案。
- [`ADR 0068`](0068-remove-two-phase-execution-start.md)：Execution 不再暴露 pending
  物化与继续启动，普通启动只选择当前可用 Flow 并通过单个 `Create` Command 完成。
- [`ADR 0081`](0081-accept-preallocated-execution-id.md)：外部业务可先保存通过统一生成器
  预分配的 Execution ID，并在本地事务提交后把同一个 ID 交给 Flow 的完整 `create`
  链路；该能力不物化 pending Execution，也不提供跨库原子性。
- [`ADR 0082`](0082-orchestrate-host-execution-actions-after-local-transactions.md)：
  外部业务的 Create、Cancel、Resume 与 Rewind 统一由宿主 Service 在本地事务之外
  编排；`planRewind` 只读返回精确影响范围，不投递 Queue 命令。
- [`ADR 0002`](0002-workflow-core-runtime-class-design.md)：Execution 聚合、TaskRun
  真实历史和 Executor 状态机。
- [`ADR 0006`](0006-single-execution-branch-routing-and-join.md)：单 Execution 分支、
  并行与汇合。
- [`ADR 0017`](0017-centralize-workflow-runtime-state-in-flow-domain.md)：统一 State、
  State.Type、History、服务器系统时间和持久化规则。
- [`ADR 0034`](0034-persist-state-as-one-jsonb-value.md)：Execution 与 TaskRun 将完整
  `current + history` 作为单一 State JSONB 值对象持久化。
- [`ADR 0020`](0020-stage-executor-cycle-effects.md)：ExecutorContext、nexts 两阶段
  处理和运行提交边界；内部周期交接由 ADR 0059 修订为 `ExecutorEventMessageHandler` 和
  `ExecutorEvent` Queue。
- [`ADR 0021`](0021-require-explicit-parallel-task.md)：串行与显式并行调度。
- [`ADR 0024`](0024-separate-runnable-and-branch-task-capabilities.md)：运行能力分类和
  Worker/Executor 协作。
- [`ADR 0029`](0029-model-parallel-as-orchestration-scope.md)：Parallel 作用域、仅
  Pause TaskRun 使用 PAUSED，以及 Execution 始终保持 RUNNING 的当前规则。
- [`ADR 0036`](0036-model-loop-and-loop-until-as-recoverable-orchestration-scopes.md)：
  同一 Task 的多轮 TaskRun、iteration 运行事实以及 Loop 作用域收敛规则。
- [`ADR 0078`](0078-reuse-generation-for-rewind-fragments-and-loop-rounds.md)：Execution
  拥有退回片段 Generation，循环 TaskRun 拥有轮次 Generation；`handleNext` 根据活动
  Current 计算片段内下一批 TaskRun；Rewind 受理结果同时返回提交时 Execution 快照
  和按业务回滚顺序排列的当前有效影响 TaskRun 编号。
- [`ADR 0079`](0079-record-unmatched-routes-as-skipped.md)：共享 State 增加 TaskRun
  专用终态 `SKIPPED`；Execution 与 Worker 不能使用该状态，未命中 Route 不贡献输出
  且被调度器视为已收敛叶子。

## PAUSE 与外部恢复

- [`ADR 0050`](0050-bind-durable-external-business-to-exact-executions.md)：一个外部
  Approval 绑定整条 Execution，每个精确 Pause TaskRun 对应该 Approval 下的一条共享
  Todo；启动副作用通过稳定身份和精确 Reversion 恢复。
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
- [`ADR 0078`](0078-reuse-generation-for-rewind-fragments-and-loop-rounds.md)：Pause
  除 Resume 外支持 Rewind 到同一 Execution 的历史 TaskRun；当前只接受顶层串行片段，
  并在受理时返回该片段已经真实发生的 TaskRun 编号。

## 持久化、时间与并发

- [`ADR 0097`](0097-carry-lock-in-aggregate-and-inherit-cas-repository.md)：需要 CAS 的根
  携带 lock，由实现层 CasRepository 统一 save、版本比较与回填；删除隐式缓存和作用域。
- [`ADR 0083`](0083-allocate-flow-version-in-repository.md)：`FlowRepository.save`
  查询同租户同 key 的全部版本（包括草稿和删除），分配下一版本、生成新行 ID 并只
  执行 INSERT；数据库以 `(company_id, key, version)` 统一保证业务唯一性。
- [`ADR 0048`](0048-isolate-schema-ddl-and-pluralize-table-names.md)：保留单一开发期
  Schema 入口，按表隔离 DDL，表名使用小写蛇形且最后一个单词为以 `s` 结尾的复数；
  `task_runs` 同步进入 JOOQ 和 Adapter 边界；过渡性的 `external_tasks` 后续已按
  ADR 0016 删除。
- [`ADR 0039`](0039-embed-complete-flow-server-in-cses.md)：Flow 使用具名数据库，
  但不再携带或执行 Flyway；启动前由部署人员手工执行完整建表基线。
- [`ADR 0038`](0038-keep-approval-business-in-cses.md)：Approval 持久化属于 CSES，
  不进入 Flow Schema 或 Flow 事务。
- [`ADR 0033`](0033-use-single-development-database-baseline.md)：开发期只维护一个
  完整 PostgreSQL 逻辑基线；Schema 变化后显式重建数据库，不保留旧数据升级路径；
  物理文件布局由 ADR 0048 修订。
- [`ADR 0034`](0034-persist-state-as-one-jsonb-value.md)：使用单一 `state jsonb`
  保存完整 State，通过表达式索引查询 current，不保留冗余 status。
- [`ADR 0072`](0072-domain-owns-audit-and-postgres-adapter-does-not-generate-it.md)：
  审计事实由领域对象产生，PostgreSQL Adapter 只持久化领域字段，不读取 Session 或
  生成当前时间、操作者和独立审计列；Queue 排序时间仍属于基础设施。
- [`ADR 0073`](0073-keep-flow-lifecycle-rules-in-domain.md)：Flow 版本、删除、定义
  不可变和正式 Flow 审计保存规则由领域拥有，Repository 只负责查询、聚合恢复和
  持久化；其中版本分配和已有行更新条款已由 ADR 0083 取代，定义、Task 身份、删除
  限制和审计事实仍由领域拥有。
- [`ADR 0009`](0009-persist-current-core-in-postgresql.md)：Core 聚合 PostgreSQL
  持久化。
- [`ADR 0026`](0026-use-task-class-as-in-project-plugin.md)：`flow_tasks.type`、
  properties JSONB 和 parent_id 派生规则。
- [`ADR 0075`](0075-use-bigint-time-and-application-validation.md)：Flow PostgreSQL
  时间统一保存为 Unix 毫秒 `bigint`；数据库只维护结构身份和访问约束，不创建外键
  或业务校验对象，关系与业务合法性由应用边界保证。
- [`ADR 0015`](0015-use-long-millisecond-java-time.md)：项目 Java 时间使用 Unix
  timestamp 毫秒值；数据库保留原生时间类型的条款已由 ADR 0075 取代。
- 各聚合的锁、版本和事务边界继续由所属领域 ADR 及
  [`command-executor.md`](../standards/command-executor.md) 共同约束。

## 通用实现决策

- [`ADR 0041`](0041-extract-shared-domain-capabilities.md)：已被 ADR 0067 取代的
  Identified、Auditable、Deletable 组合能力方案。
- [`ADR 0067`](0067-inherit-business-identity-and-audit-state.md)：`BaseDomain` 使用
  稳定字符串实体 ID，`Audited` 统一 Audit Status、更新和删除审计；业务 key、
  version 和跨对象引用按真实字段表达，不建立 `*Id` 包装领域。
- [`ADR 0049`](0049-remove-demo-and-memory-runtime-modes.md)：移除 Demo 与 Memory
  运行时模式，统一使用标准具名 `flow` PostgreSQL 数据源；它取代 ADR 0035。页面
  删除条款已由 [`ADR 0053`](0053-restore-flow-management-surface.md) 修订。
- [`ADR 0053`](0053-restore-flow-management-surface.md)：恢复正式 Flow 管理页面，并按
  Flow、Execution 和 Session 资源拆分真实 HTTP Controllers；页面使用
  `@UserSession`，不恢复 Demo/Memory 运行时。
- [`ADR 0054`](0054-temporary-admin-session-for-flow-management.md)：临时固定 `admin`
  身份方案已废止；用户会话统一由 PAAS／宿主认证提供。
- [`ADR 0056`](0056-bind-paas-jooq-to-named-datasource.md)：替换 PAAS 的无限定名
  JOOQ Factory，按 DataSource 名称绑定同名 Configuration，避免嵌入宿主的额外
  Configuration Bean 造成启动冲突。
- [`ADR 0003`](0003-use-generic-command-executor.md)：泛型 CommandExecutor。
- [`ADR 0010`](0010-use-static-create-for-domain-objects.md)：领域对象静态 create 和
  rehydrate。
- [`ADR 0025`](0025-allow-records-for-simple-boundary-contracts.md)：简单边界协议
  可以选择 Java record。
- [`ADR 0061`](0061-use-records-for-data-protocols.md)：纯数据传输协议统一使用
  Java record；事务资源、运行上下文、领域对象和持久化 Entry 保持各自的对象
  边界。
- [`ADR 0062`](0062-use-company-flow-key-and-version-as-business-identity.md)：Flow
  业务身份统一为 `companyId + key + version`；该身份已由 ADR 0083 扩展到草稿和
  删除版本，`flows.id` 只标识单条数据库记录；Executor Create 的当前字段由
  ADR 0068 修订。
- [`ADR 0064`](0064-enforce-flow-draft-identity-by-company-key.md)：FlowDraft 按
  `(company_id, flow_key)` 全量唯一的历史方案；独立聚合与表由 ADR 0069 取代，单
  草稿唯一与 upsert 规则由 ADR 0083 取代。

通用实施方法仍以 [`development.md`](../standards/development.md)
和 [`domain-object-modeling.md`](../standards/domain-object-modeling.md) 为准。

- [ADR 0085：跨嵌套编排作用域退回](0085-rewind-across-nested-orchestration-scopes.md)
