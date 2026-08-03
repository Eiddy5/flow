# Flow 项目目录结构

## 文档定位

本文是 Flow 项目目录结构和目录职责的唯一总览，供开发者和 AI Agent 在定位、
新增或移动代码前阅读。

本文回答以下问题：

- 项目包含哪些 Gradle 模块和根目录。
- `org.cses.flow` 下每个一级目录负责什么。
- `core` 内部如何按技术职责和业务模块组织。
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
├── gen/               # 数据库脚本及 JOOQ 代码生成模块
├── server/            # Flow 服务端应用模块
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
├── sql/production-release/   # 按发布日期保存的生产数据库变更脚本
└── src/main/
    ├── java/
    │   ├── org/flow/builder/ # JOOQ Generator 启动和配置
    │   └── org/flow/gen/flow/ # Flow 数据库生成类
    └── resources/            # 生成器配置
```

数据库表结构变更放在 `gen/sql/production-release/`，生成器逻辑放在
`gen/src/main/java/org/flow/builder/`，生成结果位于
`gen/src/main/java/org/flow/gen/flow/`。生成结果不能手工修改，业务代码不能放入
`gen`。生成类的使用规则见
[`docs/standards/jooq.md`](standards/jooq.md)。

### `server/`

Flow 的 Micronaut 服务端应用。生产代码、运行配置和测试都位于该模块。

### `docs/`

```text
docs/
├── project-structure.md # 本目录地图
├── agents/              # 项目专用 AI Agent 的职责和边界
├── decisions/           # 已确认架构决策及其原因
├── harness/             # 可复用的运行、调试和验证方法
├── standards/           # 开发和测试规范
├── test-reports/        # Test Agent 生成的历史测试报告
└── uc/                  # 基于已确认用户需求整理的真实用户场景
```

`standards` 只保存规范；说明性目录地图统一放在本文，不放入 `standards`。

## Server 生产代码

生产代码根包为：

```text
server/src/main/java/org/cses/flow/
```

当前一级结构：

```text
org/cses/flow/
├── Application.java  # Micronaut 应用启动入口和组合根
├── controller/       # HTTP/Web 入站接口
├── core/             # 工作流领域、Task 能力、用例和持久化端口
├── executor/         # 单轮调度、BranchTask 解释、状态机、保存与 Worker 投递协调
├── extensions/       # 可插拔的工作流能力扩展
├── infrastructure/   # 数据库及外部技术适配
└── worker/           # RunnableTask 调用、Worker 投递与关联结果信封
```

### `Application.java`

Micronaut 应用启动入口。这里只负责启动和框架装配，不承载业务流程。

### `controller/`

Web 入站适配层，负责：

- 定义 HTTP 路由、请求和响应协议。
- 完成协议层参数转换和基础输入校验。
- 调用 `core/services` 提供的公开业务入口。
- 将稳定的业务结果或异常转换为 HTTP 响应。

Controller 不实现 Flow 状态流转、Task 调度、数据库访问或事务编排。当前目录已
建立，但尚未实现具体 Controller。

### `core/`

工作流核心。它保存与 HTTP、数据库产品和外部中间件无关的业务模型、统一 State
及迁移规则、用例入口、事务内 Handler 和持久化端口。Execution 的编排推进计算
与 Worker 调度不放在 `core`，分别由同级的 `executor` 和 `worker` 包负责。

外部调用方优先通过 `core/services` 使用核心能力，不能越过 Service 直接组合
Handler、Repository 或领域内部状态。

`core` 先按技术职责分目录，再在每个技术目录下按业务模块分包：

```text
core/
├── commands/       # 写操作的命令对象和命令执行协议
├── domains/        # 领域对象、聚合和值域状态
├── exceptions/     # 核心业务异常
├── handlers/       # Command 处理及运行生命周期协调
├── plugins/        # 插件 SPI、发现、注册与 Task 类型分派全部运行机制
├── queries/        # 只读查询处理
├── repositories/   # 核心定义的持久化端口
├── serializers/    # YAML 等定义格式的通用序列化能力，保持扁平
└── services/       # 对 Controller 和其他调用方公开的业务入口
```

#### Core 技术目录职责

| 目录 | 职责 | 不应放入 |
| --- | --- | --- |
| `commands` | 表达一次写操作的意图和参数 | 查询实现、领域状态修改逻辑 |
| `domains` | 保存 Flow、Execution、TaskRun 等领域对象、Task 能力及状态规则 | DTO、数据库 Record、Controller 模型 |
| `exceptions` | 保存稳定的核心业务异常 | HTTP 响应和数据库厂商异常 |
| `handlers` | 执行 Command，协调领域、Repository 和事务内运行流程 | HTTP 协议处理 |
| `plugins` | 提供插件 SPI、classpath 发现、唯一注册及 Task 类型分派 Interface 与实现；目录保持扁平 | 具体 Task、RunnableTask 执行逻辑、BranchTask 编排逻辑 |
| `queries` | 执行只读查询并维护查询边界 | 写状态和推进 Execution |
| `repositories` | 定义 Core 所需的持久化接口 | DataPilot/JOOQ Record 等具体技术实现 |
| `serializers` | 提供与业务类型解耦的 YAML 等格式解析能力；目录保持扁平 | Flow 字段映射、Task 类型分派、领域对象创建 |
| `services` | 提供稳定、少量的公开业务入口 | 具体 HTTP 或数据库代码 |

`serializers` 与 `plugins` 是 Core 技术目录中的明确例外，不继续按业务模块分
子目录。当前 YAML 唯一入口是 `serializers/YamlParser.java`；它只返回通用只读
映射，现有 Flow 聚合直接消费该映射并解释 Flow 字段和递归 Task 定义。
`plugins` 保存宿主侧插件运行机制，具体扩展实现仍位于 `extensions`。

#### Core 业务模块

每个技术职责目录下继续使用一致的业务模块名：

```text
core/
├── commands/
│   ├── flows/
│   ├── executions/
│   ├── externaltasks/
│   └── shared/
├── handlers/
│   ├── flows/
│   ├── executions/
│   ├── externaltasks/
│   └── shared/
├── repositories/
│   ├── flows/
│   ├── executions/
│   ├── externaltasks/
│   └── shared/
└── services/
    ├── flows/
    ├── executions/
    ├── externaltasks/
    └── shared/
```

当前主要业务模块：

| 业务模块 | 负责内容 |
| --- | --- |
| `flows` | FlowDraft、Flow 定义、统一运行 `State`、`deleted` 生命周期事实、草稿、发布、升级、删除和版本读取 |
| `executions` | 使用统一 State 的 Execution 创建、推进、恢复、取消和 TaskRun 历史 |
| `externaltasks` | PAUSE 旧恢复方案的迁移遗留；新代码使用 `executions` 下的统一 Resume 用例 |
| `tasks` | Task 抽象定义、RunnableTask/BranchTask 能力及其直接调用契约 |
| `shared` | 被多个业务模块稳定复用的核心协议，不作为兜底目录 |

同一条业务链路在不同技术目录中必须使用相同模块名。例如：

```text
commands/flows/SaveFlowDraftCommand.java
handlers/flows/SaveFlowDraftHandler.java
services/flows/FlowService.java
repositories/flows/FlowRepository.java
```

详细分包约束见
[`docs/standards/workflow-core-java-model.md`](standards/workflow-core-java-model.md)，
类型归属判断见
[`docs/standards/domain-object-modeling.md`](standards/domain-object-modeling.md)。

### `executor/`

Execution 编排推进组件。它与 `core` 平级，负责：

- 使用 `ExecutorContext` 组合 Execution、精确 Flow、nexts、workerTasks、
  branchTaskRuns 与本轮 states；Session 和 DSLContext 不进入 Context。
- 由 `ExecutorService.advance` 在模块内部依次调用 `handleNext` 与 `onNexts`：
  前者根据不可变 Flow 定义和 TaskRun 事实暂存下一批可运行 TaskRun，后者原子
  应用该批次。
- 创建、开始、完成、失败、恢复或取消 TaskRun，并判断 Execution 是否收敛；
  `handleNext` 本身不改变 Execution。
- 校验每个具体 Task 恰好实现 RunnableTask 或 BranchTask；BranchTask 直接在
  Executor 内完成等待、结构节点完成和并行展开，不形成 WorkerTask。
- 通过 `WorkerTaskResult` 合并 Worker 返回的运行事实。
- 由 `DefaultExecutor` 统一保存已更新聚合并投递 WorkerTask，持续推进到稳定点。

`ExecutorService` 可以依赖 Core 领域对象与 Worker 稳定结果协议，但不定义第二
套状态类型、不直接修改 State、不访问 Repository/JOOQ，也不执行 RunnableTask。
`DefaultExecutor` 额外依赖 ExecutionRepository 端口、当前 DSLContext 和
WorkerDispatcher，以形成唯一提交边界；Context 自身仍不保存任何可持久化状态。
Core CommandHandler 负责用例校验和加载精确 Flow Reversion，不再保留
ExecutionHandler；推进、恢复或取消已有 Execution 时，Handler 先通过
ExecutionRepository 锁定读取聚合。

### `worker/`

Task Worker 调度边界。它与 `core` 平级，保存：

```text
worker/
├── WorkerDispatcher.java
├── WorkerTask.java
└── WorkerTaskResult.java
```

`RunnableTask`、`BranchTask` 及 Runnable 的直接调用契约 `RunContext`、
`RunResult` 归属于 `core/domains/tasks`；它们离开 Task 定义没有独立意义。
Worker 只消费这些能力：接收包装 RunnableTask 的不可变 `WorkerTask`，为一次
调用创建只包含 Session、DSLContext 与只读 inputs 的 `RunContext`，直接调用具体 Task 的
`run(RunContext)`，再返回使用统一 `State.Type targetState` 的
`WorkerTaskResult`。结果只允许 COMPLETED 或 TERMINATED；WAITING 只由 Executor
处理 BranchTask 时产生。Worker 不发现或选择 WorkerTaskHandler，也不能读取或
修改 Execution、TaskRun、决定下一项 Task 或伪造 State History。

### `extensions/`

工作流能力扩展层。它实现 Core 预留的 Task 类型扩展协议，并让具体 Task 实现
Task 领域定义的一种能力：

```text
extensions/
└── tasks/    # 具体 Task 子类型及其 TaskExtension 实现
```

适合放入：

- 新 Task 类型及其 `TaskExtension`。
- RunnableTask 的具体 `run(RunContext)` 执行逻辑。
- BranchTask 的固定编排特征；实际状态推进仍由 Executor 完成。

不适合放入：

- Flow、Execution 等核心状态机规则。
- 插件发现、注册和 Task 类型分派运行机制；这些能力统一位于 `core/plugins`。
- HTTP Controller。
- 数据库、消息队列或第三方 SDK 的通用连接适配。

当前 PauseTask 和实现 `TaskExtension` 的 PauseTaskPlugin
是 Flow Core 自带的 PAUSE BranchTask
编排能力；位于 `extensions` 表示它们通过 Task 扩展协议装配，不表示审批或其他
外部业务对象属于 Flow Core。外部业务能力只通过公开
`ExecutionService.resume(...)` 与 PAUSE 对接，不能直接调用 Worker、Executor
或 Handler。

当前 ParallelTask 和 ParallelTaskPlugin 是 Flow Core 自带的显式并行
BranchTask 能力。
普通 Task 的直接子任务默认串行；只有 ParallelTask 的直接子任务由 Executor
组成同一批次。Executor 直接完成结构节点本身，不投递 Worker。

扩展可以依赖 Core 提供的插件 SPI 与 Task 能力接口，不应为了声明 Task 能力而
依赖 Worker 或 Executor；Core、Executor 和 Worker 调度器不能依赖某个具体扩展实现。

领域对象的创建入口属于领域类型自身。`core/factories` 不属于项目目录结构，
不得新增或恢复。通用 `Plugin` SPI、`PluginLoader`、`PluginRegistry`、Task
扩展点 `TaskExtension`、`TaskTypeDispatcher` 和注册式类型分派实现统一位于
`core/plugins`。每个 Task
类型在 `extensions/tasks` 中只提供
具体 Task 子类型和对应 `TaskExtension` 实现；内置实现由 Micronaut Bean 装配，
普通外部 JAR 可由 classpath `ServiceLoader<Plugin>` 装配。启动时所有来源按
“扩展点 Interface + 规范化 type”建立唯一注册表。Task 类型分派通过注册表找到
TaskExtension，再由扩展直接调用具体 Task
的静态 `create(...)` 或 `rehydrate(...)`。新增类型不得修改 Flow、中心枚举或
中心 `switch`。

外部 Runnable Task 的具体类直接实现 `run(RunContext)`，不提供第二个 Service
Loader 文件。运行时安装、卸载、插件版本选择和独立 ClassLoader 扫描尚未纳入本目录职责，若
引入应单独记录架构决策。接入步骤见
[`docs/harness/task-plugin-classpath.md`](harness/task-plugin-classpath.md)。

### `infrastructure/`

基础设施适配层，负责连接数据库、DataPilot 和未来的外部技术系统：

```text
infrastructure/
├── datapilot/       # 当前 DataPilot/JOOQ 数据源接线
├── session/         # PAAS Session 的环境级适配
└── repositories/    # Repository 的具体生产实现
    └── <业务模块>/
        └── postgres/
            ├── XxxPostgresRepository.java
            └── entries/
                └── XxxEntry.java
```

适合放入：

- Repository 的 PostgreSQL、DataPilot 等生产实现。
- `entries` 子包中的数据库 Entry，以及 Entry 与领域对象之间的转换。
- 消息队列、缓存、远程服务等技术适配器。
- 只与具体框架或外部系统有关的配置和连接代码。

基础设施实现 Core 定义的端口，可以依赖 Core；Core 领域对象和状态机不能反向依赖
DataPilot、JOOQ Record 或具体数据库实现。

JOOQ 生成的 `XxxObject` 不能直接作为领域对象使用。具体 Repository 必须在自己的
`entries` 子包建立继承生成对象的 `XxxEntry`，由 Entry 集中完成数据库字段与领域
对象的双向转换。详细规则见
[`docs/standards/jooq.md`](standards/jooq.md)。

测试使用的内存 Repository 和无连接基础设施替身只放在 `src/test`，不能作为生产
Bean 放入 `src/main`。相关决策见
[`docs/decisions/0007-keep-test-adapters-out-of-production.md`](decisions/0007-keep-test-adapters-out-of-production.md)。

## Server 资源目录

```text
server/src/main/resources/
├── application.yml       # Micronaut 应用配置
├── application-demo.yml  # 独立用户 Demo 的应用覆盖配置
├── bootstrap.yaml        # 配置中心、Consul 等早期启动配置
├── bootstrap-demo.yml    # Demo 的早期启动覆盖配置
├── flow-demo/            # 由同一 Flow 服务托管的 Demo 页面静态资源
├── logback.xml           # 日志配置
└── jiguang.json          # 当前业务资源配置
```

密钥、Token 和环境专属凭证不能提交到资源目录，应通过环境变量或部署配置提供。

## Server 测试目录

测试代码根目录为：

```text
server/src/test/java/
```

测试包必须镜像生产代码包。例如：

```text
生产：
server/src/main/java/org/cses/flow/core/services/flows/FlowService.java

测试：
server/src/test/java/org/cses/flow/core/services/flows/Uc01FlowLifecycleTest.java
```

`src/test` 还可以保存只服务于测试的基础设施替身，例如内存 Repository、测试事务、
测试 Session 和测试装配 Bean。这些替身应放在其所替代生产边界对应的包中，不能
反向进入生产源码。

UC 测试的目录和一对一映射要求见
[`docs/standards/uc-testing.md`](standards/uc-testing.md)。

## 依赖方向

```text
HTTP 请求
  -> controller
  -> core/services
  -> core/serializers
  -> core/commands 或 core/queries
  -> core/handlers
  -> executor
  -> worker

executor
  -> core/domains（包括 BranchTask 能力）
  -> worker 的结果协议

worker
  -> core/domains/tasks 的 RunnableTask、RunContext、RunResult
  -> core 的 Task 定义与统一 State 词汇

extensions
  -> core/plugins 的 Task 扩展协议
  -> core/domains/tasks 的 RunnableTask 与 BranchTask 能力

infrastructure
  -> core/repositories 等核心端口
  -> DataPilot、PostgreSQL 或其他外部技术
```

核心依赖方向：

- `controller`、`executor`、`worker`、`extensions`、`infrastructure` 可以依赖
  `core`。
- `core/services` 可以调用 `core/serializers`，再通过 Command 把通用只读映射
  交给 Flow 聚合一次性消费；`core/serializers` 不反向依赖任何业务模块。
- `core/plugins` 可以依赖 Task 领域类型；具体扩展实现依赖其公开 SPI，
  `core/plugins` 不能反向依赖 `extensions`。
- `core` 不能依赖 `controller` 或具体基础设施实现。
- `core` 内不能重新建立 `executors` 或 `workers` 技术目录。
- 具体扩展实现和 Worker 不能接管 Executor 的 Execution 状态推进。
- Controller 不能绕过 Core Service 直接访问 Repository。

## 新增代码定位表

| 要新增的内容 | 目录 |
| --- | --- |
| HTTP 接口 | `server/src/main/java/org/cses/flow/controller/` |
| 对外业务入口 | `core/services/<业务模块>/` |
| 写操作参数 | `core/commands/<业务模块>/` |
| 写操作处理 | `core/handlers/<业务模块>/` |
| 领域对象或状态 | `core/domains/<业务模块>/` |
| 查询处理 | `core/queries/<业务模块>/` |
| 持久化接口 | `core/repositories/<业务模块>/` |
| YAML 等通用格式解析 | `core/serializers/`，保持扁平 |
| 插件 SPI、发现、注册和 Task 类型分派 Interface 与实现 | `core/plugins/`，保持扁平 |
| 工作流统一运行 State 与迁移规则 | `core/domains/flows/State.java` |
| Flow 草稿聚合与删除生命周期事实 | `core/domains/flows/FlowDraft.java` 与 `Flow.java` 的 `deleted` 字段 |
| Task 能力接口及 RunnableTask 直接调用契约 | `core/domains/tasks/` |
| Execution 编排推进、单轮上下文或 nexts 批次逻辑 | `executor/` |
| Worker 调度器、投递信封或关联结果信封 | `worker/` |
| PostgreSQL Repository 实现 | `infrastructure/repositories/<业务模块>/postgres/` |
| JOOQ Entry 与领域转换 | 具体 Repository 实现下的 `entries/` 子包 |
| DataPilot 接线 | `infrastructure/datapilot/` |
| 新 Task 类型及其 `TaskExtension` | `extensions/tasks/` |
| RunnableTask 具体执行逻辑 | 对应的 `extensions/tasks/<Task>.java` |
| 生产配置 | `server/src/main/resources/` |
| 对应测试 | 与生产包一致的 `server/src/test/java/` |
| 数据库变更脚本 | `gen/sql/production-release/` |
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
