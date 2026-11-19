# Flow 项目目录结构

本文是项目目录和职责的唯一总览。新增、移动或审查代码前阅读本文件。
源码架构按 [ADR 0116](decisions/0116-replace-flow-root-with-kestra-sources.md)，
当前直接 Java 使用方式按 [ADR 0121](decisions/0121-use-native-modules-directly.md)，
命名和配置按 [ADR 0122](decisions/0122-use-flow-namespace-and-configuration.md)，
版本管理按 [ADR 0123](decisions/0123-manage-dependencies-with-version-catalog.md)，
Micronaut 依赖归属按 [ADR 0124](decisions/0124-scope-micronaut-dependencies-by-module.md)，
Java 版本选择按 [ADR 0125](decisions/0125-use-build-selected-java-version.md)，
内部 Micronaut 来源按 [ADR 0126](decisions/0126-use-internal-micronaut.md)，
数据库生成对象与必要 PaaS 依赖按 [ADR 0127](decisions/0127-use-generated-database-objects.md)，
存储直接消费生成表和字段按 [ADR 0128](decisions/0128-use-generated-tables-directly.md)，
测试支持与存储实现边界按 [ADR 0129](decisions/0129-remove-tests-and-local-storage-modules.md)，
数据库底层及持久化归属按 [ADR 0130](decisions/0130-co-locate-persistence-with-owning-modules.md)，
插件验证模块移除及显式导出目录按 [ADR 0131](decisions/0131-remove-plugin-fixture.md)，
插件 API 模块和包名按 [ADR 0132](decisions/0132-rename-model-to-plugin-api.md)。
Session 服务与 Java 25 / 本地 PaaS 联合构建按 [ADR 0133](decisions/0133-session-aware-public-services.md)。
后台命令的身份和宿主 Session 配置按 [ADR 0134](decisions/0134-limit-session-to-user-service-entrypoints.md)。
[ADR 0117](decisions/0117-prune-kestra-product-entrypoints.md)、
[0118](decisions/0118-prune-kestra-core-product-features.md)、
[0119](decisions/0119-use-postgresql-only.md)、
[0120](decisions/0120-replace-worker-grpc-with-jdbc-queues.md) 记录产品功能、数据库和通信裁剪。

## 当前模块

根 `flow` 是直接可消费的 Java Library，聚合 12 个注册子模块中的运行依赖。
项目代码使用 `org.flow` 包，职责在所属模块内实现。旧 Flow 的 `org.cses.flow.core`、
Pulsar 队列、server、buildSrc 及 embedded 均不参与当前构建。当前 gen 为原生数据库
结构重新生成，不恢复旧 gen 的 Schema 或领域模型；旧项目恢复见
[备份记录](harness/kestra-source-backup.md)。

| 位置 | 职责与入口 |
| --- | --- |
| 根 `build.gradle` | Java 依赖聚合、生产依赖隔离；根无生产适配实现和测试源码 |
| `gradle/libs.versions.toml` | 外部依赖坐标、版本和第三方 BOM；所有模块共享，独立 platform 模块已移除 |
| `plugin-api`、`processor` | 基础插件契约、注解与编译期插件注册资源生成 |
| `gen` | PostgreSQL 生成对象及独立 codegen 入口；运行生成类型供数据库适配器使用，普通编译不连接数据库 |
| `jdbc` | 不依赖 core 的数据库底层：datasources.flow、jOOQ、事务执行、通用工具和宿主可覆盖的内部 DB 事件/消费者默认配置；不承载能力专属仓储 |
| `core` | Flow/Task/Execution、服务、表达式、原生 Task、插件注册和导出；通用仓储基类、ServiceInstance 等 core 仓储、迁移 SPI 适配、迁移历史和锁；公开入口位于 `org.flow.core.services`，FlowService、ExecutionService 的用户操作接收宿主 Session，PluginService 直接查询全局插件目录；FlowEngine 管理生命周期 |
| `queue` | 队列协议、原生 JDBC Dispatch/Broadcast 实现及队列持久化和表配置 |
| `executor` | 原生执行状态推进与命令消费；ExecutionRepository、ExecutionStateStore、执行器专属持久化和表配置 |
| `worker` | Task/Trigger 执行、队列领取与结果发布、PG 心跳和恢复，以及 Worker 专属持久化和表配置；普通和 SystemWorker 独立线程池 |
| `scheduler` | 原生触发器、时间调度、调度状态及其持久化和表配置 |
| `indexer` | 日志、指标与执行统计异步入库，以及相关持久化和表配置 |
| `script` | 通用脚本基类、CommandsWrapper 和 ScriptOutput；TaskRunner SPI/Process 位于 core，脚本显式选择执行器，无内置 Docker |
| `tooling` | 离线聚合/配置 Schema 生成器；不启动 Context，不进入根生产依赖 |

`plugin-api` 的接口为 `org.flow.plugin.api.Plugin`，注解与枚举分别位于
`org.flow.plugin.api.annotations`、`org.flow.plugin.api.enums`。core 与 processor
依赖该模块，插件 API 不反向依赖 core；Flow、Task、Execution 等领域模型仍在 core。

模块内部沿用 `src/main/java`、`src/main/resources`、`src/test/java`、`src/test/resources`。
各能力的数据库实现使用 `org.flow.<owner>.jdbc` 包，数据库底层仍为 `org.flow.jdbc`。
PostgreSQL 具体实现位于 `org.flow.<owner>.jdbc.postgres`；迁移代码分别位于所属
数据库包的 `migration` 与 `postgres.migration`。
`queue-jdbc`、`jdbc-postgres` 已移除，`script` 保持独立模块。
源码变更通过 Git 历史追踪；根来源清单、变更账本与原始构建备份已删除。
独立 `tests`、`storage-local` 模块及依赖它们的测试、专用资源已删除；不迁移共享
测试辅助代码、架构测试或本地存储实现。各模块保留不依赖这两个模块的普通测试
和数据库技术测试，包括 PostgreSQL 设置仓储、迁移及生成元数据验证。

## 依赖和运行

`plugin-api/processor` 支撑插件契约和扩展编译，自身不使用 Micronaut。外部依赖通过 TOML
目录管理，根保留通用依赖及约束；内部 Micronaut BOM 与公开框架 API 由 core 导出，
模块显式声明自身使用的库、生成处理器和测试依赖，不再继承整组框架配置。
内部发布的 Micronaut、jOOQ 及生成对象必要的 PaaS 组件通过限定范围的内部仓库解析；
其余官方组件仍使用 Maven Central。PaaS 范围包括 common、json、sql、exception，
以及内部 Bean 装配所需的 paas-pulsar 和必要客户端；原有 Service 的 Session 接口另引入非传递的
paas-session，联合构建本地 PaaS pass-2.0-neworg，采用 Java 25。见 [ADR 0133](decisions/0133-session-aware-public-services.md)。不引入 PaaS 聚合包、AuthX 或
CSES 业务模块。
外部调用工程也需配置内部仓库，仓库配置不随依赖发布元数据传递；框架规则见
[依赖管理](harness/dependency-management.md)，jOOQ/PaaS 增量规则见
[生成指南](harness/jooq-generation.md)。
运行实现依赖 core 中的角色契约；
FlowEngine 在 core 中通过这些契约协调五类运行服务，core 生产依赖不反向引用具体运行模块。
core 依赖 jdbc/gen；queue、executor、worker、scheduler、indexer 按职责依赖
core/jdbc/gen。jdbc 不依赖 core 或运行角色，gen 不依赖 Flow 运行模块。
core 的数据库技术测试在测试运行 classpath 引入所需能力模块，以加载完整迁移与
装配；这些测试依赖不进入 core 的生产制品依赖。
根依赖聚合不包含第二套引擎、Context 或数据源。调用应用在自己的同一 Context 内
配置 `datasources.flow`，显式迁移后启动 FlowEngine；完整入口见
[直接 Java 使用指南](harness/java-engine.md)。

core、queue、jdbc、executor、worker、scheduler 和 indexer
拥有 Micronaut 装配职责。script 的主源码不直接声明 Micronaut，
通过 core 间接依赖框架。tooling 运行时读取 Bean 元数据，不进入根生产依赖。
具体作用域及验证方法见 [依赖管理](harness/dependency-management.md)。

core 及各能力模块的数据库实现直接消费 gen 的 Table、Field、Record 和 `JooqPojo`
子类；Core Domain 和服务契约不使用生成类型。各模块通过自己的
`<Owner>JdbcTableConfigsFactory` 显式绑定生成表，逐表读取
`flow.jdbc.tables.<name>.table`，未配置时使用生成表名称；jdbc 不维护中央表名映射。
生成器在独立 codegen source set 中，普通编译使用已提交代码。混合基线 SQL 留在
core，queue、executor、scheduler、indexer 的专属 SQL 归各自
`src/main/resources/migrations/`；classpath 的 `/migrations/...` 路径、文件内容、
脚本标识、checksum 和执行顺序不变，由现有迁移入口协调执行。生成脚本只在临时
PostgreSQL 上执行这些 SQL，gen 不维护第二份 SQL 基线。

事务、队列、默认日志和迁移绑定 flow 数据源，不回退 default。原生独立日志扩展仍
保留，但不是默认装配。迁移默认显式执行；自动迁移需明确开启 flow.migration.auto。
数据库技术测试使用独立测试库和 schema，不连接业务数据库。

Worker 直接领取 JDBC 队列，在同源 PG 事务中登记归属、确认消息和发布结果，无
Controller、gRPC、Protobuf RPC 或远程存储代理。多进程 Worker 需共享 PG、存储和
加密配置。数据库底层与能力专属持久化按职责分层，只支持 PostgreSQL。

`storage-local` 已移除，项目不再提供默认文件存储实现。core 保留 `StorageInterface`
和存储插件 SPI；宿主必须提供实际存储 Bean，或添加存储插件并配置 `flow.storage.type`。
宿主 Bean 由现有条件工厂让位，不需要替换 Flow 工厂。实现及生命周期要求见
[直接 Java 使用指南](harness/java-engine.md#存储实现)。
不在生产或测试源码中保留本地文件存储替代实现。

`plugin-fixture` 已移除。tooling 导出时通过 `-PschemaPlugins` 显式指定已有插件目录，
空目录表示仅使用内置插件。插件目录由原生 Registry 加载；聚合 Schema 保留
Flow、Task、Trigger 三个根。Task 文档/Schema/图标导出属于 core 的 PluginExportService。

tooling 经 core 传递获得 jdbc、gen 及数据库依赖。它仍只读取类型和 Bean 元数据，
不启动 ApplicationContext、不连接数据库；运行隔离允许这些数据库库，继续禁止
引入实际运行角色。这是持久化归回所属模块后接受的工具 classpath 变化。

CLI、Web、MySQL、H2、memory、embedded、tests、storage-local、queue-jdbc 和 jdbc-postgres 已退出。gen 所需依赖局部排除 HTTP
Server Netty；根生产隔离同时禁止 jOOQ codegen/meta。内部 Bean 装配保留必要
Pulsar 类型，Flow 队列仍是 JDBC。jdbc 的 `FlowDatabaseContextConfigurer` 注册
最低优先级 PropertySource，默认关闭内部 DB 事件、数据库 Pulsar 监听和消费者，
宿主显式配置可以覆盖；配置键与实际验证结果见生成指南。
普通日志继续通过 Dispatch/Indexer
入库，Webhook 继续使用执行事件流。历史迁移 SQL 及原文 checksum 保留；当前迁移历史表为 flow_migration_history。

## 构建、来源和产物

- `build.gradle`、`settings.gradle`：唯一根构建；根 Java Library 与 12 个子模块。
- `gradle/libs.versions.toml`：唯一外部依赖版本目录；维护与比较方法见 [依赖管理](harness/dependency-management.md)。
- `gradle/`、`gradlew*`：固定 Wrapper 9.7.1；Java 版本由标准 Gradle Java 配置或构建 JDK 决定，不固定 toolchain、source、target 或 release。
- `lombok.config`：原生构造器元数据协议，不能遗漏。
- `build/libs/`：根依赖聚合 JAR，使用时需带齐依赖；各实现制品在对应模块的 build/libs。
- `build/exports/`：聚合 Schema。
- `<module>/build/test-results/test/`、`<module>/build/reports/tests/test/`：各模块的当前测试结果；旧根测试结果仅属历史证据。
- `docs/harness/verify-flow.sh`：在临时 PostgreSQL 上执行保留测试，以及构建、运行隔离与 Schema 检查；不代表完整引擎和宿主存储运行验收。
- `docs/harness/capture-dependency-versions.init.gradle`：按配置导出解析组件和制品，用于依赖调整前后比较。
- `gen/src/codegen/java/`、`docs/harness/generate-jooq.sh`：显式生成入口及临时 PostgreSQL 建库、生成与清理脚本；产物提交到 gen 的主源码目录。
- core、queue、executor、scheduler、indexer 的 `src/main/resources/migrations/`：按能力归属存放迁移 SQL，混合基线留在 core；保持原 classpath 路径、脚本标识、内容、checksum 和顺序。

## 文档与 Agent

`.codex/agents/` 保存 UC/Test Agent 入口和文档包；`.codex/skills/` 保存项目技能。
业务 UC 由 UC Agent 维护，业务 UC 验证由 Test Agent 完成。原生架构调整使用独立
技术测试，不能把技术职责和 ADR 转成业务 UC。

`docs/decisions/` 保存具体架构决定，`docs/standards/` 保存通用规范，`docs/harness/`
保存当前可复用执行入口及历史技术证据。旧 UC、ADR、报告中的源码路径和命令保留
其当时含义，不覆盖当前原生模块、协议或使用入口。
