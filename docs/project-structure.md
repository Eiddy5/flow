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
└── uc/                  # 基于已确认需求与架构生成的 UC 验收契约
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
├── core/             # 工作流领域、用例和持久化端口
├── executor/         # Execution 状态机和下一任务计算
├── extensions/       # 可插拔的工作流能力扩展
├── infrastructure/   # 数据库及外部技术适配
└── worker/           # Task Worker 调度协议、输入和执行结果
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

工作流核心。它保存与 HTTP、数据库产品和外部中间件无关的业务模型、状态规则、
用例入口、事务内 Handler 和持久化端口。Execution 状态机与 Worker 调度不放在
`core`，分别由同级的 `executor` 和 `worker` 包负责。

外部调用方优先通过 `core/services` 使用核心能力，不能越过 Service 直接组合
Handler、Repository 或领域内部状态。

`core` 先按技术职责分目录，再在每个技术目录下按业务模块分包：

```text
core/
├── commands/       # 写操作的命令对象和命令执行协议
├── domains/        # 领域对象、聚合和值域状态
├── exceptions/     # 核心业务异常
├── handlers/       # Command 处理及运行生命周期协调
├── queries/        # 只读查询处理
├── repositories/   # 核心定义的持久化端口
├── serializers/    # YAML 等定义格式的通用序列化能力，保持扁平
└── services/       # 对 Controller 和其他调用方公开的业务入口
```

#### Core 技术目录职责

| 目录 | 职责 | 不应放入 |
| --- | --- | --- |
| `commands` | 表达一次写操作的意图和参数 | 查询实现、领域状态修改逻辑 |
| `domains` | 保存 Flow、Execution、TaskRun 等领域对象及状态规则 | DTO、数据库 Record、Controller 模型 |
| `exceptions` | 保存稳定的核心业务异常 | HTTP 响应和数据库厂商异常 |
| `handlers` | 执行 Command，协调领域、Repository 和事务内运行流程 | HTTP 协议处理 |
| `queries` | 执行只读查询并维护查询边界 | 写状态和推进 Execution |
| `repositories` | 定义 Core 所需的持久化接口 | DataPilot/JOOQ Record 等具体技术实现 |
| `serializers` | 提供与业务类型解耦的 YAML 等格式解析能力；目录保持扁平 | Flow 字段映射、Task 类型分派、领域对象创建 |
| `services` | 提供稳定、少量的公开业务入口 | 具体 HTTP 或数据库代码 |

`serializers` 是 Core 技术目录中的明确例外，不继续按业务模块分子目录。
当前 YAML 唯一入口是 `serializers/YamlParser.java`；它只返回通用只读映射，
现有 Flow 聚合直接消费该映射并解释 Flow 字段和递归 Task 定义。

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
| `flows` | Flow 定义、DRAFT、发布、升级、关闭和版本读取 |
| `executions` | Execution 创建、推进、恢复、取消和 TaskRun 历史 |
| `externaltasks` | PAUSE 对应的外部任务等待、完成和查询 |
| `tasks` | Task 抽象定义、具体类型创建约束及类型分派协议 |
| `shared` | 被多个业务模块稳定复用的核心协议，不作为兜底目录 |

同一条业务链路在不同技术目录中必须使用相同模块名。例如：

```text
commands/flows/SaveFlowDraftCommand.java
handlers/flows/SaveFlowDraftHandler.java
services/flows/FlowService.java
repositories/flows/FlowRepository.java
```

详细分包约束见
[`docs/standards/development-basics.md`](standards/development-basics.md)。

### `executor/`

Execution 运行状态机。它与 `core` 平级，负责：

- 使用 `ExecutorContext` 组合当前命令的 Session、DSLContext、Flow 和
  Execution。
- 根据不可变 Flow 定义和 TaskRun 事实计算下一项可运行 Task。
- 创建、开始、完成、失败、恢复或取消 TaskRun，并判断 Execution 是否收敛。
- 通过 `WorkerTaskResult` 合并 Worker 返回的运行事实。

`executor` 可以依赖 Core 的领域对象，也可以依赖 Worker 的稳定结果协议；它不
访问 Repository/JOOQ，不执行具体 Task，也不保存可持久化状态。Repository 加载、
同事务中间保存和 Worker 派发仍由 `core/handlers/executions/ExecutionHandler`
协调。

### `worker/`

Task Worker 调度边界。它与 `core` 平级，保存：

```text
worker/
├── WorkerContext.java
├── WorkerDispatcher.java
├── WorkerTask.java
├── WorkerTaskHandler.java
├── WorkerTaskOutcome.java
└── WorkerTaskResult.java
```

Worker 只接收一个不可变 `WorkerTask`，通过 `WorkerContext` 使用当前命令上下文，
并返回 `WorkerTaskResult`。它不能直接读取或修改 Execution、TaskRun，也不能决定
下一项 Task。AUTO、PAUSE 等具体 `WorkerTaskHandler` 仍放在
`extensions/workers`。

### `extensions/`

工作流能力扩展层。它实现 Core 预留的 Task 类型扩展协议和顶层 Worker 包定义的
执行协议：

```text
extensions/
├── tasks/    # 具体 Task、TaskPlugin、注册表与类型分派
└── workers/  # 各 Task 类型对应的 WorkerTaskHandler
```

适合放入：

- 新 Task 类型及其 `TaskPlugin`。
- Task Plugin 注册和类型分派；最终调用具体 Task 类型的静态 `create(...)`
  或 `rehydrate(...)`。
- Task 的具体执行、暂停或取消行为。

不适合放入：

- Flow、Execution 等核心状态机规则。
- HTTP Controller。
- 数据库、消息队列或第三方 SDK 的通用连接适配。

扩展可以依赖 Core 与 Worker 提供的稳定扩展接口；Core、Executor 和 Worker
调度器不能依赖某个具体扩展实现。

领域对象的创建入口属于领域类型自身。`core/factories` 不属于项目目录结构，
不得新增或恢复。每个 Task 类型在 `extensions/tasks` 中同时提供具体 Task
子类型和一个 `TaskPlugin` Bean；Micronaut 在启动时收集插件并按规范化后的
`type` 建立唯一注册表。类型分派通过注册表找到插件，再由插件直接调用具体 Task
的静态 `create(...)` 或 `rehydrate(...)`。新增类型不得修改 Flow、中心枚举或
中心 `switch`。

当前插件装配范围是应用 classpath 中的 Micronaut Bean。运行时安装、卸载、插件
版本选择和独立 ClassLoader 扫描尚未纳入本目录职责，若引入应单独记录架构决策。

### `infrastructure/`

基础设施适配层，负责连接数据库、DataPilot 和未来的外部技术系统：

```text
infrastructure/
├── datapilot/       # 当前 DataPilot/JOOQ 数据源接线
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
├── application.yml  # Micronaut 应用配置
├── bootstrap.yaml   # 配置中心、Consul 等早期启动配置
├── logback.xml      # 日志配置
└── jiguang.json     # 当前业务资源配置
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
  -> core/domains
  -> worker 的结果协议

worker
  -> core 的 Task 定义

extensions
  -> core 的 Task 扩展协议
  -> worker 的 WorkerTaskHandler 协议

infrastructure
  -> core/repositories 等核心端口
  -> DataPilot、PostgreSQL 或其他外部技术
```

核心依赖方向：

- `controller`、`executor`、`worker`、`extensions`、`infrastructure` 可以依赖
  `core`。
- `core/services` 可以调用 `core/serializers`，再通过 Command 把通用只读映射
  交给 Flow 聚合一次性消费；`core/serializers` 不反向依赖任何业务模块。
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
| Execution 状态机、上下文或下一任务模型 | `executor/` |
| Worker 调度协议、输入或结果模型 | `worker/` |
| PostgreSQL Repository 实现 | `infrastructure/repositories/<业务模块>/postgres/` |
| JOOQ Entry 与领域转换 | 具体 Repository 实现下的 `entries/` 子包 |
| DataPilot 接线 | `infrastructure/datapilot/` |
| 新 Task 类型及其 `TaskPlugin` | `extensions/tasks/` |
| Task Worker 实现 | `extensions/workers/` |
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
2. 再读取 `docs/standards/development-basics.md`。
3. 根据任务读取对应 `docs/agents/`、`docs/standards/`、`docs/decisions/` 和
   `docs/uc/`。
4. 修改后检查新增文件是否位于本文规定的目录，并检查测试包是否镜像生产包。

如果实际目录与本文不一致，应先判断是尚未完成的迁移、空目录，还是架构边界已经
变化。架构边界已经变化时，应同步更新 ADR、本文和 `AGENTS.md`，不能只修改代码。
