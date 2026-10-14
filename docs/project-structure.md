# Flow 项目目录结构

本文是项目目录和职责的唯一总览。新增、移动或审查代码前阅读本文件。
源码架构按 [ADR 0116](decisions/0116-replace-flow-root-with-kestra-sources.md)，
当前直接 Java 使用方式按 [ADR 0121](decisions/0121-use-native-modules-directly.md)，
命名和配置按 [ADR 0122](decisions/0122-use-flow-namespace-and-configuration.md)，
版本管理按 [ADR 0123](decisions/0123-manage-dependencies-with-version-catalog.md)，
Micronaut 依赖归属按 [ADR 0124](decisions/0124-scope-micronaut-dependencies-by-module.md)，
Java 版本选择按 [ADR 0125](decisions/0125-use-build-selected-java-version.md)，
内部 Micronaut 来源按 [ADR 0126](decisions/0126-use-internal-micronaut.md)。
[ADR 0117](decisions/0117-prune-kestra-product-entrypoints.md)、
[0118](decisions/0118-prune-kestra-core-product-features.md)、
[0119](decisions/0119-use-postgresql-only.md)、
[0120](decisions/0120-replace-worker-grpc-with-jdbc-queues.md) 记录产品功能、数据库和通信裁剪。

## 当前模块

根 `flow` 是直接可消费的 Java Library，聚合 16 个注册子模块中的运行依赖。
项目代码使用 `org.flow` 包，职责在所属模块内实现。旧 Flow 的 `org.cses.flow.core`、
Pulsar、gen、server、buildSrc 及本轮退出的 embedded 均不参与当前构建；旧项目恢复见
[备份记录](harness/kestra-source-backup.md)。

| 位置 | 职责与入口 |
| --- | --- |
| 根 `build.gradle`、`src/test` | Java 依赖聚合、生产依赖隔离，以及直接 Java 使用的跨模块技术测试和强杀探针；根无生产适配实现 |
| `gradle/libs.versions.toml` | 外部依赖坐标、版本和第三方 BOM；所有模块共享，独立 platform 模块已移除 |
| `model`、`processor` | 基础插件契约、注解与编译期插件注册资源生成 |
| `core` | Flow/Task/Execution、服务、表达式、原生 Task、插件注册和导出；公开入口为 FlowEngine、FlowService、Repository Interface、PluginExportService；不含 AI/MCP、Dashboard、匿名上报和自动安装 |
| `queue`、`queue-jdbc` | 队列协议与原生 JDBC Dispatch/Broadcast 实现 |
| `jdbc`、`jdbc-postgres` | 原生 Repository/状态存储、事务、PostgreSQL 迁移 SQL 与迁移器；直接绑定 datasources.flow |
| `executor` | 原生执行状态推进与命令消费 |
| `worker` | Task/Trigger 执行、直接队列领取与结果发布、PG 心跳和恢复；普通和 SystemWorker 独立线程池 |
| `scheduler` | 原生触发器、时间调度与调度状态 |
| `indexer` | 日志、指标与执行统计异步入库 |
| `storage-local` | 本地文件存储 |
| `script` | 通用脚本基类、CommandsWrapper 和 ScriptOutput；TaskRunner SPI/Process 位于 core，脚本显式选择执行器，无内置 Docker |
| `tooling` | 离线聚合/配置 Schema 生成器；不启动 Context，不进入根生产依赖 |
| `tests` | 原生模块共享测试工具 |
| `plugin-fixture` | 无业务的独立插件制品，用真实 processor/ClassLoader 验证扩展和导出 |

模块内部沿用 `src/main/java`、`src/main/resources`、`src/test/java`、`src/test/resources`。
源码变更维护在 `UPSTREAM.md` 和逐文件来源账本。

## 依赖和运行

`model/processor` 支撑模型和扩展编译，自身不使用 Micronaut。外部依赖通过 TOML
目录管理，根保留通用依赖及约束；内部 Micronaut BOM 与公开框架 API 由 core 导出，
模块显式声明自身使用的库、生成处理器和测试依赖，不再继承整组框架配置。
内部发布的 Micronaut 组件通过限定范围的内部仓库解析；其余官方组件仍使用
Maven Central，不引入 PaaS 模块。外部调用工程也需配置内部仓库，仓库配置不随
依赖发布元数据传递；具体规则见 [依赖管理](harness/dependency-management.md)。
运行实现依赖 core 中的角色契约；
FlowEngine 在 core 中通过这些契约协调五类运行服务，core 不反向依赖具体运行模块。
根依赖聚合不包含第二套引擎、Context 或数据源。调用应用在自己的同一 Context 内
配置 `datasources.flow`，显式迁移后启动 FlowEngine；完整入口见
[直接 Java 使用指南](harness/java-engine.md)。

core、queue、queue-jdbc、jdbc、jdbc-postgres、executor、worker、scheduler 和 indexer
拥有 Micronaut 装配职责。storage-local 和 script 的主源码不直接声明 Micronaut，
集成测试仍需容器；它们通过 core 间接依赖框架。tooling 运行时读取 Bean 元数据，
tests 提供框架测试支持，plugin-fixture 使用插件 SPI；三者均不进入根生产依赖。
具体作用域及验证方法见 [依赖管理](harness/dependency-management.md)。

事务、队列、默认日志和迁移绑定 flow 数据源，不回退 default。原生独立日志扩展仍
保留，但不是默认装配。迁移默认显式执行；自动迁移需明确开启 flow.migration.auto。
测试也使用 flow 数据源，通过不同临时数据库和 schema 隔离；不连接业务数据库。

Worker 直接领取 JDBC 队列，在同源 PG 事务中登记归属、确认消息和发布结果，无
Controller、gRPC、Protobuf RPC 或远程存储代理。多进程 Worker 需共享 PG、存储和
加密配置。jdbc/jdbc-postgres/queue-jdbc 保留职责分层，只支持 PostgreSQL。

`plugin-fixture` 仅编译期依赖 core/script/processor，测试从目录加载独立制品，不加入
测试或生产的普通 classpath。插件目录由原生 Registry 工厂加载；聚合 Schema 保留
Flow、Task、Trigger 三个根。Task 文档/Schema/图标导出属于 core 的 PluginExportService。

CLI、Web、MySQL、H2、memory 和 embedded 已退出。HTTP Server Netty 仅为测试
依赖，根生产依赖和 tooling 的隔离检查禁止引入。普通日志继续通过 Dispatch/Indexer
入库，Webhook 继续使用执行事件流。历史迁移 SQL 及原文 checksum 保留；当前迁移历史表为 flow_migration_history。

## 构建、来源和产物

- `build.gradle`、`settings.gradle`：唯一根构建；根 Java Library 与 16 个子模块。
- `gradle/libs.versions.toml`：唯一外部依赖版本目录；维护与比较方法见 [依赖管理](harness/dependency-management.md)。
- `gradle/`、`gradlew*`：固定 Wrapper 9.7.1；Java 版本由标准 Gradle Java 配置或构建 JDK 决定，不固定 toolchain、source、target 或 release。
- `lombok.config`：原生构造器元数据协议，不能遗漏。
- `LICENSE`、`UPSTREAM.md`、`upstream-manifest.json`、`upstream-changes.json`、`upstream-build/`：许可、来源哈希、变更登记与原始构建依据。
- `verify-upstream.py`：验证原始来源及已登记删除、迁移和修改。
- `build/libs/`：根依赖聚合 JAR，使用时需带齐依赖；各实现制品在对应模块的 build/libs。
- `build/exports/`：聚合 Schema；`plugin-fixture/build/plugin-release/`：验证插件 JAR。
- `build/test-results/test/`、`build/reports/tests/test/`：根跨模块技术验证结果。
- `docs/harness/verify-flow.sh`：自动创建和清理临时 PG 的目标验证及完整回归入口。
- `docs/harness/capture-dependency-versions.init.gradle`：按配置导出解析组件和制品，用于依赖调整前后比较。

## 文档与 Agent

`.codex/agents/` 保存 UC/Test Agent 入口和文档包；`.codex/skills/` 保存项目技能。
业务 UC 由 UC Agent 维护，业务 UC 验证由 Test Agent 完成。原生架构调整使用独立
技术测试，不能把技术职责和 ADR 转成业务 UC。

`docs/decisions/` 保存具体架构决定，`docs/standards/` 保存通用规范，`docs/harness/`
保存当前可复用执行入口及历史技术证据。旧 UC、ADR、报告中的源码路径和命令保留
其当时含义，不覆盖当前原生模块、协议或使用入口。
