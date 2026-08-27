# Flow 项目目录结构

## 文档定位

本文是 Flow 项目目录结构和目录职责的唯一总览，供开发者和 AI Agent 在定位、
新增或移动代码前阅读。

本文回答以下问题：

- 项目包含哪些 Gradle 模块和根目录。
- `org.cses.flow` 下每个一级目录负责什么。
- `org.cses.flow.core` 包如何按技术职责和业务模块组织。
- 新增代码、测试、配置、数据库脚本和文档分别放在哪里。

具体编码规则由 `docs/standards/` 规定，架构选择及其原因由
`docs/decisions/` 记录。本文不复制这些规范和决策，只提供目录地图和定位入口。

## 仓库根目录

```text
flow/
├── .codex/            # 项目级 Codex 配置和可复用 Skill
├── AGENTS.md          # AI Agent 的仓库级工作入口和必读顺序
├── README.md          # 项目对外简介
├── build.gradle       # 根项目构建配置
├── settings.gradle    # Gradle 模块注册
├── buildSrc/          # 仓库共享的 Gradle 构建约定
├── gradle/            # Gradle Wrapper 和版本目录
├── gen/               # Flow 数据库脚本及 JOOQ 代码生成模块
├── core/              # 完整的非 HTTP Flow 能力与生产适配器
├── server/            # HTTP 服务、启动入口、资源与会话绑定
└── docs/              # 项目规范、决策、UC、Agent 和验证记忆
```

### `.codex/`

保存仅服务于本仓库的 Codex 配置与可复用能力：

```text
.codex/
└── skills/
    └── <skill-name>/
        ├── SKILL.md           # Skill 触发条件和执行流程
        ├── agents/openai.yaml # Codex UI 元数据
        ├── references/        # 按需读取的项目流程模板和参考资料
        └── scripts/           # Skill 使用的确定性校验或自动化脚本
```

项目级 Skill 应封装稳定、可重复的项目工作流，并引用 `docs/` 中的权威规范，不在
Skill 内复制第二套项目规则。`references/` 和 `scripts/` 只在工作流确实需要时
创建；通用个人 Skill 不放入本目录。

### `buildSrc/`

保存仓库内部复用的 Gradle 构建逻辑。它只负责构建约定，不放业务代码。

### `gradle/`

- `gradle/wrapper/`：Gradle Wrapper。
- `gradle/libs.versions.toml`：依赖和插件版本目录。

### `gen/`

负责数据库结构和 JOOQ 代码生成：

```text
gen/
├── sql/
│   └── flow/                 # Flow 开发期 PostgreSQL 完整建表基线
│       ├── 001_create_flow_tables.sql # psql 完整入口与显式执行顺序
│       └── tables/           # 一张表一个同名 SQL，包含表、约束和索引
└── src/main/
    ├── java/
    │   ├── org/flow/builder/ # JOOQ Generator 启动和配置
    │   └── org/flow/gen/     # Flow 数据库生成类
    └── resources/            # 生成器配置
```

Flow 数据库以 `gen/sql/flow/001_create_flow_tables.sql` 为完整执行入口，具体表结构
修改位于 `gen/sql/flow/tables/<table_name>.sql`。表文件隔离与命名规则见
[`docs/standards/postgresql-schema.md`](standards/postgresql-schema.md)。生成器逻辑放在
`gen/src/main/java/org/flow/builder/`，生成结果位于
`gen/src/main/java/org/flow/gen/flow/`。生成结果不能手工修改，业务代码不能放入
`gen`。生成类的使用规则见
[`docs/standards/jooq.md`](standards/jooq.md)。

`core` 构建依赖 `gen` 中 Flow 数据库的生成类型。Flow 基线由开发或部署人员在
应用启动前手工执行，不进入 Core 或 Server 运行资源。基线变化后重建对应开发数据库，不
维护旧数据升级路径；Generator 和代码生成依赖仍只属于 `gen`，业务代码不能调用它们。

### `core/`

完整的非 HTTP Flow 模块。它保存领域、用例、Executor、Worker、Queue 契约、插件
扩展、具名 Flow 数据源与 JOOQ 装配、PostgreSQL Repository 以及 DataPilot 兼容适配。Core
不包含 `Application`、HTTP Controller、HTTP 参数绑定或静态资源，可以由 CSES 等
宿主通过 Server 的传递依赖直接注入和调用公开 Service。

### `server/`

Flow 的薄 HTTP 服务和可启动 Micronaut 应用。它通过 `api(project(":core"))` 传递
暴露完整 Core，只保存 `Application`、Controller、静态资源
和运行配置。Server 既可由自身 `Application` 独立启动，也可作为普通 JAR 进入
CSES 的 ApplicationContext；模块边界见
[`ADR 0042`](decisions/0042-split-core-from-http-server.md)，两种运行形态见
[`ADR 0039`](decisions/0039-embed-complete-flow-server-in-cses.md)。

### `docs/`

```text
docs/
├── architecture.md      # 当前代码架构与核心流程图
├── project-structure.md # 本目录地图
├── agents/              # 项目专用 AI Agent 的职责和边界
├── decisions/           # 已确认架构决策及其原因
├── harness/             # 可复用的运行、调试和验证方法
├── standards/           # 开发和测试规范
├── test-reports/        # Test Agent 生成的历史测试报告
└── uc/                  # 基于已确认用户需求整理的真实用户场景
```

`standards` 只保存跨多个业务对象或开发任务复用的规范和方法；Flow、Task、
Execution、PAUSE、Data、State、模块形态和运行协议等具体选择统一放入
`decisions`。说明性目录地图统一放在本文，不放入 `standards`。

## 生产代码

Flow 生产代码分布在两个源码根：

```text
core/src/main/java/org/cses/flow/    # 完整非 HTTP 能力
server/src/main/java/org/cses/flow/  # 启动与 HTTP 服务
```

当前一级结构：

```text
core/src/main/java/org/cses/flow/
├── core/             # 工作流领域、Task 能力、用例和持久化端口
├── executor/         # Executor Command、队列路由/处理、状态机与 Worker 投递协调
├── extensions/       # 可插拔的工作流能力扩展
├── infrastructure/   # Flow 数据库、Repository 与 DataPilot 适配
├── queues/           # 类型化 Event、Dispatch Queue 契约与订阅生命周期
└── worker/           # RunnableTask 调用、Worker 投递与关联结果信封

server/src/main/java/org/cses/flow/
├── Application.java  # Micronaut 应用启动入口和组合根
└── controller/       # HTTP/Web 入站接口
```

### `Application.java`

Micronaut 应用启动入口。这里只负责启动和框架装配，不承载业务流程。

### `controller/`

Web 入站适配层，负责：

- 定义 HTTP 路由、请求和响应协议。
- 完成协议层参数转换和基础输入校验。
- 写操作优先直接接收 Core Command；没有一一对应 Command 的轻量 HTTP 字段使用
  `Map` 或路径参数接收，由 Service 构建 Executor Command，不在 Controller 中定义
  `*Request` DTO。
- 调用 `core/services` 提供的公开业务入口。
- 将稳定的业务结果或异常转换为 HTTP 响应。

Controller 不实现 Flow 状态流转、Task 调度、数据库访问或事务编排。当前
`controller/plugins` 桥接全局只读插件查询，`controller/flow` 提供基于真实
`@UserSession` 的 Flow 管理 HTTP 入口。Flow Server 同时发布 `/flow/**` 下的正式管理
页面；默认由 `controller/session/AdminSessionArgumentBinder` 注入临时 `admin` 管理员身份，
可通过 `flow.management.admin-session.enabled=false` 关闭并交回宿主认证。页面不启用
Demo/Memory 运行时，所有写操作仍通过 Core Service 进入 PostgreSQL 和 Dispatch Queue。

### `core/`

工作流核心。它保存与 HTTP、数据库产品和外部中间件无关的业务模型、统一 State
及迁移规则、用例入口、事务内 Handler 和持久化端口。Execution 的编排推进计算、
Worker 调度与异步消息传输契约不放在 `core`，分别由同级的 `executor`、`worker`
和 `queues` 包负责。

Flow 的草稿与正式版本统一由 `Flow` 表达，并始终保留原始 YAML `source`。
草稿由默认 `draft=true` 表达，创建时必须提供业务 key，同一个 `companyId + key`
只能有一个草稿；正式版本由 `companyId + key + version` 精确选择。Flow、Execution、Task 与
TaskRun 都通过 `String id()` 暴露稳定实体标识；跨对象引用使用 `executionId`、
`taskId`、`taskRunId` 等明确字符串字段；`FlowId` 仅在 Flow Repository 中表达业务
选择器，不建立实体 `*Id` 包装领域。Execution 始终通过
`companyId + key + version` 恢复历史 Flow，不使用 `flows.id` 作为业务选择器。

外部调用方优先通过 `core/services` 使用核心能力，不能越过 Service 直接组合
Handler、Repository 或领域内部状态。

`core` 先按技术职责分目录，再在每个技术目录下按业务模块分包：

```text
core/
├── commands/       # 写操作的命令对象和命令执行协议
├── domains/        # 领域对象、聚合和值域状态
├── exceptions/     # 核心业务异常
├── handlers/       # Command 处理及运行生命周期协调
├── plugins/        # Plugin 契约、发现注册、元信息与 Jackson 多态绑定
├── queries/        # 只读查询处理
├── repositories/   # 核心定义的持久化端口
├── serializers/    # 严格 Jackson/YAML、Flow 物化与定义 Schema
├── services/       # 对 Controller 和其他调用方公开的业务入口
└── validations/    # 领域模型的统一主动校验入口
```

#### Core 技术目录职责

| 目录 | 职责 | 不应放入 |
| --- | --- | --- |
| `commands` | 表达一次写操作的意图和参数 | 查询实现、领域状态修改逻辑 |
| `domains` | 保存共享领域能力与 ActorRef 值对象，以及 Flow、Execution、TaskRun 等领域对象、Task 能力及状态规则 | DTO、数据库 Record、Controller 模型 |
| `exceptions` | 保存稳定的核心业务异常 | HTTP 响应和数据库厂商异常 |
| `handlers` | 执行 Command，协调领域、Repository 和事务内运行流程 | HTTP 协议处理 |
| `plugins` | 提供 Plugin 契约、`@Plugin`、Micronaut 编译期发现、按真实 Java 包分组的两级只读目录、精确类注册和 Jackson 多态绑定 | 具体 Task、RunnableTask 执行逻辑、OrchestrationTask 编排逻辑、页面布局元数据 |
| `queries` | 执行只读查询并维护查询边界 | 写状态和推进 Execution |
| `repositories` | 定义 Core 所需的持久化接口 | DataPilot/JOOQ Record 等具体技术实现 |
| `serializers` | 集中配置严格 Jackson/YAML，解析通用 YAML，把 Flow 定义物化为完整领域对象，并按同一契约生成插件定义 Schema；目录保持扁平 | Controller 协议、执行调度、持久化 SQL |
| `services` | 提供稳定、少量的公开业务入口 | 具体 HTTP 或数据库代码 |
| `validations` | 对框架绑定或项目代码直接创建的模型执行统一主动校验 | YAML 语法解析、Flow 树身份生成 |

`serializers` 是 Core 技术目录中的明确例外，保持扁平。`JacksonMapper` 只负责
集中创建和配置受控的 JSON/YAML Mapper，并提供通用对象转换；
`YamlParser` 是无状态静态类，只通过静态方法直接使用 `JacksonMapper` 提供的 YAML
Mapper，负责严格 YAML 树、只读 Map 和通用目标类型解析。它不导入或创建
Flow、Task 等业务类型。`PluginModule` 在 Mapper 创建时注册
`PluginDeserializer`；YAML Mapper 使用其 source 定义配置，自动拒绝 Task 系统字段
并生成首次身份，JSON Mapper 则按原值恢复持久化身份。`PublishFlowHandler` 只调用
`parse(source, Flow.class)`，并在绑定后补充 Session、状态、版本和原始 source；
`PluginDeserializer` 通过构造器接收注册中心，解析具体 Task 并由 Jackson 自然
递归绑定嵌套插件，不再由 Flow 反射扫描插件字段。
`plugins` 的运行机制放在包根，只有注解位于 `plugins/annotations`；
`DefaultPluginRegistry` 直接从插件类的 `Class#getPackageName()` 取得真实包路径，
并以此构造全局只读目录。`PluginSchemaGenerator` 位于
保持扁平的 `serializers`，使用 `JacksonMapper` 的隔离副本按需生成定义 Schema。具体扩展
实现仍位于 `extensions`。

#### Core 业务模块

每个技术职责目录下继续使用一致的业务模块名：

```text
core/
├── commands/
│   ├── flows/
│   ├── executions/
│   └── shared/
├── handlers/
│   ├── flows/
│   ├── executions/
│   └── shared/
├── repositories/
│   ├── flows/
│   ├── executions/
│   └── shared/
└── services/
    ├── flows/
    ├── executions/
    ├── plugins/
    └── shared/
```

当前主要业务模块：

| 业务模块 | 负责内容 |
| --- | --- |
| `flows` | 统一的 `Flow` 定义、原始 YAML `source`、`draft` 状态、审计删除事实、发布、升级和版本读取 |
| `executions` | 使用统一 State 的 Execution 创建、推进、恢复、取消和 TaskRun 历史 |
| `expressions` | 受限条件与模板表达式的解析和求值；Express 由 Route、Loop Until 复用，TemplateExpression 由需要渲染运行输入的 Task 复用 |
| `plugins` | 按真实 Java 包分组的全局只读插件、Task 元信息和具体定义 Schema 查询 |
| `tasks` | Task 抽象定义、RunnableTask/OrchestrationTask 能力及其直接调用契约 |
| `shared` | 被多个业务模块稳定复用的核心协议，不作为兜底目录 |

同一条业务链路在不同技术目录中必须使用相同模块名。例如：

```text
commands/flows/PublishFlowCommand.java
handlers/flows/PublishFlowHandler.java
services/flows/FlowService.java
repositories/flows/FlowRepository.java
```

详细分包约束见
[`docs/decisions/README.md`](decisions/README.md) 中的 Core 组件决策，
类型归属判断见
[`docs/standards/domain-object-modeling.md`](standards/domain-object-modeling.md)。

### `executor/`

Execution 编排推进组件。它与 `core` 平级，负责：

- 在 `executor/commands` 定义统一 `ExecutionCommand` 和具体 `Create`、`Resume`、`Cancel`
  Command；`Create` 只传递 `company`、`flowKey`、`flowVersion`、`inputs`，
  `ExecutionService` 只等待 Queue 接受，不等待 Execution 创建或运行完成。
- `DefaultExecutor` 同时订阅外部 Executor Command Queue 和内部 Executor Event Queue。
  外部消息只路由给 `executor/handlers/ExecutionCommandEventHandler`；该 Handler 恢复
  宿主 Session、校验并物化 Execution，然后原子投递 `ExecutorEvent`。
- `executor/handlers/ExecutorEventHandler` 是内部状态循环的唯一处理器：每次领取一个
  `ExecutorEvent`，从 Repository 锁定并加载精确 Flow/Execution，创建一个
  `ExecutorContext`，推进一个周期，保存本轮变化，再把后续周期投回 Event Queue。
- 使用 `ExecutorContext` 组合 Execution、精确 Flow、nexts、workerTasks、
  orchestrationCompletions、本轮 states 与变更标记；Session 和 DSLContext 不进入
  Context。
- 由 `ExecutorService.handle` 循环调用 `handleNext` 与 `onNexts`：前者根据
  不可变 Flow 定义和 TaskRun 事实暂存下一批 TaskRun，后者原子应用该批次并
  判断 RunnableTask 与 OrchestrationTask。
- 创建、开始、完成、失败、恢复或取消 TaskRun，并判断 Execution 是否收敛；
  `handleNext` 本身不改变 Execution。
- 校验每个具体 Task 恰好实现 RunnableTask 或 OrchestrationTask；RunnableTask
  形成 WorkerTask 后返回提交边界，OrchestrationTask 直接在 `handle` 循环内完成
  Pause 前置 Task 子树执行、Pause TaskRun 暂停、编排作用域推进和收敛；Pause
  自身不形成 WorkerTask，其 `pause` 字段中的 RunnableTask 仍按正常 Worker 链路执行。
- 通过 `WorkerTaskResult` 合并 Worker 返回的运行事实。
- 内部 `ExecutorEventHandler` 统一保存已更新聚合、同步投递 WorkerTask、应用结果；
  Worker 结果应用后通过新的 `ExecutorEvent` 再次进入下一周期。`ExecutorContext` 不
  跨 Queue、不进入持久化载荷。

`ExecutorService` 可以依赖 Core 领域对象与 Worker 稳定结果协议，但不定义第二
套状态类型、不直接修改 State、不访问 Repository/JOOQ，也不执行 RunnableTask。
`ExecutionCommandEventHandler` 依赖 Repository 端口和内部 Event Queue，负责 Command
分派、输入规范化、创建或锁定 Execution 以及加载精确 Flow Reversion；内部
`ExecutorEventHandler` 依赖当前 DSLContext、ExecutorService、WorkerDispatcher 和
Event Queue，形成一个 Event 周期的运行提交边界。`DefaultExecutor` 只负责两条 Queue
的生命周期和路由。Context 自身仍不保存任何可持久化状态；恢复时由内部 Handler
重新从 Execution.inputs 读取 Flow inputs。

### `queues/`

类型化异步消息传输契约。它与 `core`、`executor` 和 `worker` 平级，只保存公开
Interface；`Event` 只表达业务 key，`DispatchQueue.emitInTransaction(...)` 使用 JOOQ
`DSLContext` 显式表达一次发布的调用方事务；Event 不携带事务状态。该目录不包含
存储、序列化或中间件产品实现：

```text
queues/
├── Queue.java
├── DispatchQueue.java
├── QueueSubscription.java
├── QueueException.java
└── event/
    ├── Event.java
    └── DispatchEvent.java
```

业务 Module 拥有具体 Event 的字段、key 和内部业务分类；每个 Event 契约对应一个
类型化 Dispatch Queue。`DispatchQueue` 提供单条与批量、同步与异步发布，并使用
Java `Consumer` 注册竞争消费者；`QueueSubscription` 独立管理一次注册的暂停、恢复
和关闭生命周期。普通 `emit(...)` 由具体 Adapter 使用 Queue 自有事务；调用方已经
持有事务且要求业务写入与消息原子提交时，必须通过 `emitInTransaction(...)` 在调用点
显式传入。事务是发布操作元数据，不属于 Event，也不能进入持久化消息。

本目录不执行 JOOQ SQL，也不保存消息表、JSONB 转换、后台轮询器、ACK、重试或具体
Consumer。业务 Event 的内部 `eventType` 仍由所属 Module 自行维护，Queue 不建立中心
类型目录。当前只定义 Dispatch Interface；Broadcast Interface、消费游标和保留清理
尚未定义。数据库 Adapter 的所有传输类别共用 `queues` 载荷表，并通过
`queue_type + queue_name` 逻辑隔离；未来专属消费状态可以独立建表，但不拆分载荷表。

当前 Execution 启动和内部周期交接均依赖 Dispatch Queue；外部 Command Handler 与
内部 Event Handler 各自在一个消费事务中完成自己的边界，Worker 在 Event 周期内同步
调用。具体 Queue Adapter 放入对应基础设施目录；Default Adapter 负责事务内持久化和
周期轮询消费生命周期。完整决策见
[`ADR 0046`](decisions/0046-define-typed-dispatch-queue-framework.md) 与
[`ADR 0047`](decisions/0047-implement-default-dispatch-queue.md)，Execution 接入见
[`ADR 0051`](decisions/0051-start-executions-through-dispatch-queue.md)。

### `worker/`

Task Worker 调度边界。它与 `core` 平级，保存：

```text
worker/
├── WorkerDispatcher.java
├── WorkerTask.java
└── WorkerTaskResult.java
```

`RunnableTask`、`OrchestrationTask` 及 Runnable 的直接调用契约 `RunContext`、
`RunResult` 归属于 `core/domains/tasks`；它们离开 Task 定义没有独立意义。
Worker 只消费这些能力：接收包装 RunnableTask 的不可变 `WorkerTask`，为一次
调用创建包含 Session 和只读 `variables` 的 `RunContext`，其中保留键
`$flow.execution` 保存当前 `Execution`，`$flow.taskRunId` 和可选的
`$flow.parentTaskRunId` 保存当前/直接父 TaskRun 的调用期技术身份，`$flow.inputs`
保存 Execution.inputs 中的 Flow 级输入，`$flow.taskInputs` 保存当前 TaskRun 的业务
输入；`RunContext` 通过 `executionId()`、`taskRunId()`、`parentTaskRunId()`、
`inputs()` 和 `taskInputs()` 提供类型化便捷访问，再直接调用具体 Task 的
`run(RunContext)`，返回使用统一 `State.Type targetState` 的
`WorkerTaskResult`。结果只允许 COMPLETED 或 TERMINATED；PAUSED 只由 Executor
处理明确的 Pause OrchestrationTask 时产生。Worker 不发现或选择
WorkerTaskHandler，也不能推进或修改 Execution、TaskRun 状态、决定下一项 Task 或
伪造 State History；上述身份不作为新状态写入 Flow、Execution 或 TaskRun。

### `extensions/`

工作流能力扩展层。它实现 Core 预留的 Task 类型扩展协议，并让具体 Task 实现
Task 领域定义的一种能力：

```text
extensions/
├── flow/               # Flow 自有编排 Task：Pause、Parallel，以及后续 Loop 等
├── tasks/              # 尚未迁移类型地址的 AutomaticTask
├── log/                # 独立的 Log 扩展能力
└── <extension-name>/   # 其他独立扩展能力
    └── <ExtensionName>.java
```

适合放入：

- 标注 `@Plugin`、实现 `Plugin` 和一种 Task 运行能力的新 Task 类型。
- RunnableTask 的具体 `run(RunContext)` 执行逻辑。
- OrchestrationTask 的固定编排特征；实际状态推进仍由 Executor 完成。

不适合放入：

- Flow、Execution 等核心状态机规则。
- 插件发现、注册和 Task 类型分派运行机制；这些能力统一位于 `core/plugins`。
- HTTP Controller。
- 数据库、消息队列或第三方 SDK 的通用连接适配。

当前 `Pause` 是 Flow Core 自带并标注 `@Plugin` 的 OrchestrationTask 暂停能力，
位于 `extensions/flow/Pause.java`。位于 `extensions` 表示它通过 Task 扩展协议
装配，不表示审批或其他外部业务对象属于 Flow Core。外部业务能力通过宿主自定义
RunnableTask 与 `Pause.pause` 对接，Task 使用 `RunContext` 调用宿主 Service；跨进程
场景才通过公开 `ExecutionService.resume(...)` 恢复 Pause。Task 不能直接调用 Worker、
Executor 或 Handler。Pause 直接声明唯一必填的 `pause` Task、允许为空的 `resume` Input
列表以及可选且成对配置的 `duration + behavior`；继承的 `tasks` 保持普通完成后子任务
语义，在 Resume 后执行，`definitionChildren()` 按 `pause`、`tasks` 顺序暴露完整定义树。
完整定义遍历通过 `Task.definitionChildren()` 识别类型专有 Task，跨字段约束继续由
`ModelValidator` 统一调用 `ModelInvariant` 校验。

当前 `Parallel` 是 Flow Core 自带并标注 `@Plugin` 的显式并行 OrchestrationTask
作用域，位于 `extensions/flow/Parallel.java`。普通 Task 的直接子任务默认串行；
只有 Parallel 的直接子任务由 Executor 组成同一批次。Parallel TaskRun 在全部实际
选中分支子树收敛前保持 RUNNING，且不投递 Worker。

当前 `Loop` 与 `LoopUntil` 同样属于 Flow Core 自带的 OrchestrationTask：Loop 按
固定正整数次数串行重复子 Task，LoopUntil 在每轮收敛后检查受限条件并受最大轮数
保护。循环体每轮形成独立 TaskRun，Executor 从 iteration、parentId 和有序历史
恢复进度，不保存循环游标。

`flow` 目录按语义所有权归纳 Flow 自身提供的编排 Task，而不是按每个具体能力再拆
一层目录。Pause、Parallel、Loop、Loop Until 以及后续 Subflow 都属于该目录。
Log、Notification 等能够作为独立扩展能力演进的 Task，才使用自己的能力目录。

扩展可以依赖 Core 提供的插件 SPI 与 Task 能力接口，不应为了声明 Task 能力而
依赖 Worker 或 Executor；Core、Executor 和 Worker 调度器不能依赖某个具体扩展实现。

领域对象的创建入口属于领域类型自身。`core/factories` 不属于项目目录结构，
不得新增或恢复。`Plugin`、`PluginRegistry`、`DefaultPluginRegistry`、
`RegisteredPlugin`、`PluginMetadata`、`PluginDeserializer` 和
`PluginModule` 位于 `core/plugins`，发现注解位于 `core/plugins/annotations`；
`PluginSchemaGenerator` 位于 `core/serializers`。
新的独立项目内 Task 扩展在 `extensions/<extension-name>` 中以能力名称提供一个
具体类；Flow 自有 OrchestrationTask 统一放在 `extensions/flow`。两者都继承
`Task`、实现 `RunnableTask` 或 `OrchestrationTask` 之一，并标注
`@Plugin`，类名不强制增加 `Task` 后缀。宿主应用只需把自己的插件类放在有名称的
真实 Java 包中并标注 `@Plugin`，无需声明额外的来源对象或标识。Micronaut 在编译期
发现这些 Bean，注册表按 `Class#getPackageName()` 分组，再按
`Class#getCanonicalName()` 建立精确、只读映射；新增类型不得修改 Flow、中心枚举
或中心 `switch`。现有 `AutomaticTask` 暂时保留原类型地址；Pause 与 Parallel 按照
ADR 0029 一次性迁移为 `org.cses.flow.extensions.flow.Pause` 和
`org.cses.flow.extensions.flow.Parallel`，不提供旧地址兼容。
`core/services/plugins` 对外提供目录和按需 Schema 查询，`controller/plugins` 只把
Java Class 转换为字符串 DTO。

当前版本支持宿主源码或普通构建依赖贡献的编译期插件，不支持 ServiceLoader、插件
目录动态 JAR、运行时安装卸载、插件版本选择和独立 ClassLoader。接入步骤见
[`docs/harness/task-plugin.md`](harness/task-plugin.md)。

### `infrastructure/`

非 HTTP 基础设施适配位于 Core，并按技术职责分包：

```text
core/src/main/java/org/cses/flow/infrastructure/
├── jooq/            # 具名 flow 数据源和 JOOQ 装配
├── queues/          # Default Dispatch Queue、统一消息表、JsonFactory 类型恢复与周期轮询
│   └── entries/     # 保存业务 payload、queue_type 与 queue_name 的 Queue Message Entry
├── repositories/    # Repository 的具体生产实现
│   └── <业务模块>/
│       └── postgres/
│           ├── XxxPostgresRepository.java
│           └── entries/
│               └── XxxEntry.java
└── datapilot/       # Flow DataPilot 兼容接线

```

适合放入：

- Repository 的 PostgreSQL、DataPilot 等生产实现。
- `entries` 子包中的数据库 Entry，以及 Entry 与领域对象之间的转换。
- `queues` 中实现 `queues` Interface 的 `DefaultDispatchQueue`；普通发布使用 Queue
  自有事务，显式事务发布使用调用方传入的 `DSLContext`。它使用项目现有 `JsonFactory`
  把 Event 重组为只含业务数据的 JSONB Queue Entry，并通过装配时传入的 `Class<T>`
  恢复业务类型。所有传输类别的
  Entry 写入统一 `queues`，用可扩展 `queue_type + queue_name` 隔离；当前
  Default Adapter 固定使用 `DISPATCH`。Adapter 使用具名 `flow` JOOQ、周期轮询和
  `FOR UPDATE SKIP LOCKED` 竞争消费；异步发布始终使用 Queue 自有事务。
  `ExecutorCommandQueueFactory` 和 `ExecutorEventQueueFactory` 是两条具名
  Executor Queue 的业务组合根。
- 缓存、远程服务等其他技术适配器。
- 只与具体框架或外部系统有关的配置和连接代码。

基础设施实现 Core 定义的端口，可以依赖 Core；Core 领域对象和状态机不能反向依赖
DataPilot、JOOQ Record 或具体数据库实现。

JOOQ 生成的 `XxxObject` 不能直接作为领域对象使用。具体 Repository 或数据库 Queue
Adapter 必须在自己的 `entries` 子包建立继承生成对象的 `XxxEntry`，由 Entry 集中
完成数据库字段与项目对象的转换。详细规则见
[`docs/standards/jooq.md`](standards/jooq.md)。

测试专用替身和辅助代码统一放在对应模块的 `src/test/java`，不能作为生产 Bean 放入
`src/main`。数据库集成测试统一使用 PostgreSQL 测试适配器，单元测试替身只保留在
测试类内部。相关决策见
[`docs/decisions/0007-keep-test-adapters-out-of-production.md`](decisions/0007-keep-test-adapters-out-of-production.md)。

## Server 资源目录

```text
server/src/main/resources/
├── application.yml       # 独立和嵌入形态共享的安全 flow.* 默认配置
├── application-flow-standalone.yml # Flow 独立启动的应用与端口配置
├── bootstrap-flow-standalone.yaml # Flow 独立启动的 Consul 等早期配置
├── logback.xml           # 日志配置
└── jiguang.json          # 当前业务资源配置
```

密钥、Token 和环境专属凭证不能提交到资源目录，应通过环境变量或部署配置提供。

## 测试目录

测试代码跟随生产边界，测试辅助代码归属各模块自己的测试源码：

```text
core/src/test/java/    # Core、Executor、Worker、Repository 与非 HTTP 装配测试及测试辅助代码
server/src/test/java/  # HTTP、Session、启动装配与 Server 边界测试及测试辅助代码
```

测试包必须镜像生产代码包。例如：

```text
生产：
core/src/main/java/org/cses/flow/core/services/flows/FlowService.java

测试：
core/src/test/java/org/cses/flow/core/services/flows/Uc01FlowLifecycleTest.java
```

测试专用基础设施替身，例如内存 Repository、测试事务、测试 Session 和测试装配
Bean，应放在所属模块的 `src/test/java` 中与所替代生产边界对应的包内，不能反向
进入生产源码。

UC 测试的目录和一对一映射要求见
[`docs/standards/uc-testing.md`](standards/uc-testing.md)。

## 依赖方向

```text
gen（生成类和数据库基线来源）
  -> core 编译

core（完整非 HTTP Flow 能力）
  -> server（HTTP Adapter 与可启动应用）
  -> CSES ApplicationContext（经 server 传递依赖）
CSES Approval -> core 中公开的 ExecutionService Resume 能力

server
  -> core/services

HTTP 请求
  -> controller
  -> core/services
  -> core/serializers
  -> core/commands 或 core/queries
  -> core/handlers
  -> executor
  -> worker

executor
  -> core/domains（包括 OrchestrationTask 能力）
  -> worker 的结果协议

worker
  -> core/domains/tasks 的 RunnableTask、RunContext、RunResult
  -> core 的 Task 定义与统一 State 词汇

extensions
  -> core/plugins 的 Plugin 接口与 @Plugin 注解
  -> core/domains/tasks 的 RunnableTask 与 OrchestrationTask 能力

controller/plugins
  -> core/services/plugins
  -> core/plugins 的只读目录与定义 Schema

infrastructure
  -> core/repositories 等核心端口
  -> 具名 flow JOOQ、PostgreSQL 或其他外部技术

infrastructure/queues
  -> queues 的类型化 Event、发布和订阅契约
  -> 具名 flow JOOQ 与 gen 生成的统一 Queue Message 表类型

queues/event
  -> JOOQ DSLContext 类型（只表达同步发布可空事务）
```

核心依赖方向：

- Gradle 模块为 `gen`、`core` 与 `server`；依赖方向固定为 `gen -> core -> server`。
  Flow 建表基线由人工执行，不进入 `core` 或 `server` 运行资源。
- Approval 是 CSES 业务模块，不在 Flow 仓库保存领域代码或表结构。CSES 单向依赖
  完整 Flow JAR，并通过公开的 `ExecutionService` 恢复精确的 Pause TaskRun；Flow
  不依赖 CSES Approval。完整边界见
  [`ADR 0038`](decisions/0038-keep-approval-business-in-cses.md)。
- `server` 只能通过公开 Core 能力提供 HTTP 和启动装配；`executor`、`worker`、
  `queues`、`extensions`、`infrastructure` 与 `core` Java 包共同位于 Gradle `core`
  模块。
- `core/services` 通过 Handler 调用 `core/serializers`，由定义反序列化器把严格
  YAML 一次性物化为 Flow；领域对象不直接依赖 Jackson。
- `core/plugins` 可以依赖 Task 领域类型和统一模型校验；具体扩展实现依赖其公开
  Plugin 契约，
  `core/plugins` 不能反向依赖 `extensions`。
- `core` 不能依赖 `controller` 或具体基础设施实现。
- `queues` 不依赖 Executor、Worker、Core Domain 或具体 Queue Adapter；只有
  `DispatchQueue.emitInTransaction(...)` 的公开发布契约依赖 JOOQ `DSLContext` 类型，
  `Event` 不依赖 JOOQ，Queue 契约不执行 SQL。Executor 的具体启动 Event 依赖该公开
  契约，消费后再进入 Worker 链路。
- `core` 内不能重新建立 `executors` 或 `workers` 技术目录。
- 具体扩展实现和 Worker 不能接管 Executor 的 Execution 状态推进。
- Controller 不能绕过 Core Service 直接访问 Repository。

## 新增代码定位表

| 要新增的内容 | 目录 |
| --- | --- |
| HTTP 接口 | `server/src/main/java/org/cses/flow/controller/` |
| 对外业务入口 | `core/src/main/java/org/cses/flow/core/services/<业务模块>/` |
| 写操作参数 | `core/src/main/java/org/cses/flow/core/commands/<业务模块>/` |
| 写操作处理 | `core/src/main/java/org/cses/flow/core/handlers/<业务模块>/` |
| 跨领域共享能力接口与 ActorRef 值对象 | `core/src/main/java/org/cses/flow/core/domains/` 根目录，不再按单项能力建立子目录 |
| 领域对象或状态 | `core/src/main/java/org/cses/flow/core/domains/<业务模块>/` |
| Express 条件与 TemplateExpression 模板值对象 | `core/src/main/java/org/cses/flow/core/domains/expressions/` |
| 查询处理 | `core/src/main/java/org/cses/flow/core/queries/<业务模块>/` |
| 持久化接口 | `core/src/main/java/org/cses/flow/core/repositories/<业务模块>/` |
| YAML、严格 Jackson、Flow 定义物化与插件定义 Schema | `core/src/main/java/org/cses/flow/core/serializers/`，保持扁平 |
| Plugin 契约、目录、发现注册与多态绑定 | `core/src/main/java/org/cses/flow/core/plugins/`；注解放其 `annotations/` |
| 插件目录与详情查询 Service | `core/src/main/java/org/cses/flow/core/services/plugins/` |
| 插件全局 HTTP 查询 | `server/src/main/java/org/cses/flow/controller/plugins/` |
| Flow 管理 HTTP Controller 与协议模型 | `server/src/main/java/org/cses/flow/controller/flow/` |
| Flow 管理页面与布局资源 | `server/src/main/resources/flow/` |
| 模型主动校验 | `core/src/main/java/org/cses/flow/core/validations/` |
| 工作流统一运行 State 与迁移规则 | `core/src/main/java/org/cses/flow/core/domains/flows/State.java` |
| Flow 聚合与删除生命周期事实 | `core/src/main/java/org/cses/flow/core/domains/flows/` |
| Task 能力接口及 RunnableTask 直接调用契约 | `core/src/main/java/org/cses/flow/core/domains/tasks/` |
| Execution 编排推进、单轮上下文或 nexts 批次逻辑 | `core/src/main/java/org/cses/flow/executor/` |
| Execution 启动 Queue Command、Publisher 与 Consumer | `core/src/main/java/org/cses/flow/executor/` |
| Worker 调度器、投递信封或关联结果信封 | `core/src/main/java/org/cses/flow/worker/` |
| 类型化 Event、Dispatch Queue 与订阅生命周期契约 | `core/src/main/java/org/cses/flow/queues/` |
| Queue Event 分类 Interface | `core/src/main/java/org/cses/flow/queues/event/` |
| Default Dispatch Queue、统一消息表、Event JSONB 重组、类型恢复与轮询订阅 | `core/src/main/java/org/cses/flow/infrastructure/queues/` |
| Executor Command/Event Queue 的 Micronaut 组合根 | `core/src/main/java/org/cses/flow/infrastructure/queues/ExecutorCommandQueueFactory.java`、`ExecutorEventQueueFactory.java` |
| 包含 `queue_type + queue_name` 的统一 Queue Message JOOQ Entry | `core/src/main/java/org/cses/flow/infrastructure/queues/entries/` |
| PostgreSQL Repository 实现 | `core/src/main/java/org/cses/flow/infrastructure/repositories/<业务模块>/postgres/` |
| JOOQ Entry 与领域转换 | 具体 Repository 实现下的 `entries/` 子包 |
| Flow YAML 数据源配置、JOOQ 与数据库接线 | `core/src/main/java/org/cses/flow/infrastructure/jooq/` |
| Flow 的 DataPilot 接线 | `core/src/main/java/org/cses/flow/infrastructure/datapilot/` |
| Flow 自有 OrchestrationTask | `core/src/main/java/org/cses/flow/extensions/flow/<TypeName>.java` |
| 标注 `@Plugin` 的独立项目内 Task 扩展 | `core/src/main/java/org/cses/flow/extensions/<extension-name>/<ExtensionName>.java` |
| RunnableTask 具体执行逻辑 | 对应扩展目录中的具体类 |
| 生产配置 | `server/src/main/resources/` |
| Core 生产代码对应测试 | 与生产包一致的 `core/src/test/java/` |
| 测试辅助代码和替身 | 对应模块的 `src/test/java/` |
| HTTP 与启动装配测试 | 与生产包一致的 `server/src/test/java/` |
| 开发期数据库建表基线入口 | `gen/sql/flow/001_create_flow_tables.sql` |
| 单表建表、约束和索引 | `gen/sql/flow/tables/<table_name>.sql` |
| 开发或测试规范 | `docs/standards/` |
| 架构决策 | `docs/decisions/` |
| UC 场景 | `docs/uc/<领域>/` |
| UC 测试报告 | `docs/test-reports/<领域>/` |
| Agent 职责 | `docs/agents/` |
| 可复用运行手册 | `docs/harness/` |

新增类前还必须执行“先搜索、优先复用”的检查。无法从本表确定目录，或者新增内容会
改变以上边界时，不应创建临时 `common`、`misc`、`util` 等兜底目录；应先确认模块
归属，必要时记录新的 ADR。

## AI Agent 开始工作时的使用方式

AI Agent 接到开发、测试、审查或文档任务后：

1. 先用本文确定目标模块、公开入口和允许修改的目录。
2. 再读取 `docs/standards/project-development.md`。
3. 根据任务读取对应 `docs/agents/`、`docs/standards/`、`docs/decisions/` 和
   `docs/uc/`。
4. 修改后检查新增文件是否位于本文规定的目录，并检查测试包是否镜像生产包。

如果实际目录与本文不一致，应先判断是尚未完成的迁移、空目录，还是架构边界已经
变化。架构边界已经变化时，应同步更新 ADR、本文和 `AGENTS.md`，不能只修改代码。
