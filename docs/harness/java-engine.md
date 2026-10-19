# 直接通过 Java 使用流程引擎

本项目的根 Java Library 聚合原生运行模块，公开服务在 `core`，实现位于各原生模块。
在同一应用的 Micronaut ApplicationContext 中注入即可使用，无 embedded 模块、
替换工厂或额外启用开关。直接模块消费方需带齐 core、queue、executor、worker、
scheduler、indexer 和需要的 script，以及其 jdbc/gen 依赖，并由宿主提供存储实现。
持久化已归所属能力模块，不再引用 `jdbc-postgres` 或 `queue-jdbc`；模块边界见
[ADR 0130](../decisions/0130-co-locate-persistence-with-owning-modules.md)。

## 从另一个 Java 工程引用

当前源码尚未发布新的 Maven 制品。Gradle 调用工程可以通过 composite build 直接消费
本项目，模块版本和传递依赖使用本仓库配置，不需要复制 JAR：

框架使用与本地 PaaS `pass-2.0-neworg` 对齐的内部 Micronaut 平台 `5.0.3.29`。调用工程也必须
配置内部框架仓库；仓库配置不会随 `includeBuild` 或发布元数据传递。具体仓库
过滤与 `repoUrl` 配置见 [依赖管理指南](dependency-management.md#内部-micronaut-与消费方仓库)。
gen 通过内部 jOOQ 传递引入必要的 PaaS 组件和 Pulsar 客户端类型，调用工程不必
另行添加 PaaS 聚合服务。Flow 队列仍使用 JDBC，不要求运行 Pulsar。

JDBC 模块自动提供最低优先级默认值：`jooq.send-event=false`、
`table.db-change.listeners.pulsar.enabled=false`、`pulsar.consumer.enabled=false`，
避免内部数据库事件和消费者随 Context 自动启动。宿主确实使用这些能力时，可以
通过自己的 YAML、环境变量、系统属性或 builder 配置显式开启；这些宿主值优先。
版本及生成边界见 [数据库对象生成指南](jooq-generation.md)。

```groovy
// 调用工程的 settings.gradle
includeBuild('/absolute/path/to/flow')
```

```groovy
// 调用工程的 build.gradle
dependencies {
    implementation 'org.flow:flow:2.0.5'
}
```

调用应用继续使用自己的 Micronaut ApplicationContext。根依赖通过 core 公开 Java API，
通过运行依赖带入实现模块；tooling 不进入运行 classpath。
`tests` 与 `storage-local` 已移除，当前边界见
[ADR 0129](../decisions/0129-remove-tests-and-local-storage-modules.md)。

## Java 版本选择

Flow 不硬编码 toolchain，也不设置固定的 `sourceCompatibility`、`targetCompatibility`
或 `options.release`。编译和测试遵循标准 Gradle Java 配置；Schema 导出等
`JavaExec` 任务使用所属项目的 `java.toolchain`。未配置 toolchain 时，默认使用
运行 Gradle 的 JDK，可通过构建环境的 `JAVA_HOME` 或 Gradle JVM 配置选择。

- 若 Flow 模块加入宿主的同一多模块构建，宿主可通过公共构建配置统一设置各项目的
  `java.toolchain`，Flow 不再将其覆盖为 25。
- `includeBuild` 是独立构建。调用工程设置的 `java.toolchain` 不会自动传给 Flow；
  两个构建都未指定 toolchain 时使用各自的 Gradle JVM，通常由同一构建环境提供。
  调用工程显式选择另一 JDK 时，也需在 Flow 构建中使用相同的 Java 配置或构建环境。
- 已发布 JAR 的字节码在构建时确定，不会随调用应用的 JDK 重新编译。Gradle 的
  Java 目标版本元数据保留真实值；发布时应选择与目标使用环境兼容的 JDK，不能靠
  删除元数据绕过兼容性检查。

本次 Session 接入采用 Java 25：本地 PaaS neworg 使用 Micronaut 5 和 Java 25
字节码，不能继续声明兼容 Java 21。Flow 仍沿用标准构建环境选择，不硬编码 toolchain；
请设置 JAVA_HOME 指向 Java 25。当前组合见 [ADR 0133](../decisions/0133-session-aware-public-services.md)，
其修订 ADR 0125 的 Java 21 下限。

## 本地 PaaS 源码

当前 SessionUser 接口尚未发布到内部 Maven 仓库，需联合构建本地 PaaS
`pass-2.0-neworg`。默认检查 Flow 同级目录 `../paas`，不会替用户切换分支。
自定义路径使用 `./gradlew -PpaasSourcePath=/absolute/path/paas ...`，或设置
`FLOW_PAAS_SOURCE_PATH` 环境变量；CI 和 includeBuild 消费方也需提供该源码。
未提供正确源码时构建会明确报错，不回退到旧版 Session。

core 只引入 Session 合同所在制品，关闭该依赖的传递基础设施，并默认设置
`paas.session.enabled=false`。宿主已有 Session 基础设施配置可覆盖这一最低优先级默认值。
宿主应显式注册自己的 SessionFactory；Flow 不创建认证服务或 Redis 缓存。

## 配置

调用应用提供具名 `datasources.flow`。Repository、JDBC 队列、默认日志和迁移共用
该连接池；存在 default 池时也不会回退到 default。数据库产品固定 PostgreSQL，
连接、schema、池大小可配置。不要将原生表直接建入旧 Flow 已使用的同名表空间。

```yaml
datasources:
  flow:
    url: ${FLOW_DATASOURCE_URL}
    username: ${FLOW_DATASOURCE_USERNAME}
    password: ${FLOW_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver
    schema: ${FLOW_DATASOURCE_SCHEMA:flow_engine}
    maximum-pool-size: ${FLOW_DATASOURCE_MAXIMUM_POOL_SIZE:24}
flow:
  server-type: STANDALONE
  repository:
    type: postgres
  queue:
    type: postgres
  storage:
    type: ${FLOW_STORAGE_TYPE}
  plugins:
    path: ${FLOW_PLUGINS_PATH:}
  migration:
    auto: false
  engine:
    worker-threads: 4
    start-timeout: PT1M
  server:
    service:
      purge:
        initial-delay: PT1H
        fixed-delay: P1D
```

`flow.migration.auto` 默认 false。由部署准备程序显式调用
`MigrationRunnerInterface.runOrFailIfLocked()` 完成建库或升级；需要启动时自动迁移的
环境可以明确设为 true。FlowEngine.start 不执行迁移。SQL 与 advisory lock 保持原有协议，历史表使用 `flow_migration_history`；
`pendingScripts()` 会 bootstrap 历史表，并非零 DDL 查询。
迁移 SPI 的数据库适配、历史表与锁归 core，底层连接与事务执行归 jdbc。混合基线
SQL 留在 core，专属 SQL 归 queue、executor、scheduler、indexer；资源继续使用
原 `/migrations/...` classpath 路径，由现有迁移入口协调执行。移动资源不改变脚本
标识、内容、checksum 或执行顺序，直接消费模块时需带齐所需迁移资源。

当前 `FlowEngine` Bean 仅在 `flow.server-type: STANDALONE` 时装配，因此以上直接
Java 示例保留该值。显式设置 `flow.server-type` 同时会启用
`ServerCommandValidator`，要求 `flow.queue.type`、`flow.repository.type` 和
`flow.storage.type` 均存在；提供宿主存储 Bean 不能绕过这项配置校验。

## 存储实现

项目保留 `org.flow.core.storages.StorageInterface` 及插件 SPI，不再附带本地文件
存储实现。运行引擎前，宿主必须采用以下一种方式提供实际存储：

- 在同一个 Micronaut Context 中提供 `StorageInterface` Bean。现有
  `FlowBeansFactory` 使用 `@Requires(missingBeans = StorageInterface.class)`，
  检测到宿主 Bean 后不会再创建存储插件，无需 `@Replaces`。通过上述 `FlowEngine`
  入口运行时，仍需提供 `flow.storage.type`；例如设置 `FLOW_STORAGE_TYPE=host`
  满足现有配置校验。工厂已让位给宿主 Bean，不会用 `host` 查找插件。宿主负责
  该 Bean 的初始化、配置和关闭。
- 引入实现了 `StorageInterface` 的存储插件，按插件要求完成注册和配置，设置
  `flow.storage.type` 为实际已注册的插件标识，并提供该插件所需配置。现有工厂会解析插件、调用 `init()`，并在
  Context 关闭时调用 `close()`。

仅设置旧的 `flow.storage.type: local` 或 `flow.storage.local.base-path` 不再提供
存储能力；缺少宿主 Bean 或可解析的插件时会装配失败。文件输入输出、命名空间文件
及 KV 内容仍依赖存储契约，不能用空实现代替。多进程 Worker 的宿主实现必须让各
进程访问同一份数据；数据库连接和存储是分别配置的职责。

## Java 入口

下面对象均由同一个 Context 注入。初始化数据库是部署准备步骤，完成后启动运行角色：

```java
import org.flow.core.migration.MigrationRunnerInterface;
import org.flow.core.runners.FlowEngine;
import org.flow.core.services.FlowService;
import org.flow.core.services.ExecutionService;
import org.flow.core.services.PluginService;
import jakarta.inject.Inject;

@Inject MigrationRunnerInterface migrations;
@Inject FlowEngine engine;
@Inject FlowService flows;
@Inject ExecutionService executions;
@Inject PluginService plugins;

// 部署准备阶段显式调用；运行阶段不重复执行。
migrations.runOrFailIfLocked();
engine.start();

// session 由宿主提供，保留具体 Session<U> 与 U extends SessionUser 类型。
var flow = flows.create(session, """
    id: hello
    namespace: example
    tasks:
      - id: message
        type: org.flow.plugin.core.log.Log
        message: Hello Flow
    """);
var command = executions.create(session, "example", "hello", flow.getRevision(), java.util.Map.of());
String executionId = command.executionId();

// 命令返回表示已入队；实例由执行器异步创建，首次查询可能为空。
var execution = executions.findById(session, executionId);
var registeredPlugins = plugins.plugins(session);
```

三个服务统一位于 `org.flow.core.services`，每个名称只有一个业务实现。宿主直接
注入原有 Service，由服务本身完成身份校验、存储和命令投递。业务入口均接收
`<S extends Session<U>, U extends SessionUser>`，从
`session.getCompanyId()` 提取租户，从 `session.getUser().getId()` 提取操作者。
缺少租户、用户或用户 ID 时拒绝调用，YAML 中的 tenantId 不覆盖 Session。
流程创建和更新也支持 GenericFlow 对象，其 tenantId 必须与 Session 一致；更新和
删除在服务中重新读取本租户流程，调用方不传入 previous 对象。
宿主负责登录、企业成员关系与权限检查；Flow 的 Session 校验和租户条件不替代授权。

流程服务提供创建、更新、删除、查询与定义校验；执行服务提供创建、查询、暂停、
恢复、重启、重放。执行写操作返回原生命令，其中 executionId 用于查询实例，operationId
用于关联原生异步操作结果。恢复操作将 actorId 写入 Pause 的 resumed.by。
执行器消费控制命令和重放来源时再次检查租户、命名空间和流程。

宿主还需注册自己的 `SessionFactory<S, U>` Bean。异步队列使用 `SessionCommand`
包装原生命令，仅保存租户和 actorId，不序列化 Session、cookie、token 或用户资料。
消费者通过该工厂创建会话、装载用户并调用 `onSessionBound()`，校验恢复后的身份，
然后执行原生命令；身份恢复失败会记录异步失败，不执行命令。
恢复只重建后台操作身份，不代表恢复登录状态；工厂应能创建独立的新会话和用户对象。
操作者标识保留在队列消息和消费日志中，本次没有新增执行审计表。

`FlowService.validate(session, sources)` 提供定义校验且不保存流程；导入、依赖查询、
可执行流程查询以及 `LocalFlowRepositoryLoader.load` 均显式传入 Session。
`ExecutionService` 的实例查询和暂停状态查询直接使用 Session，无 withACL 开关。

同一个 FlowService、ExecutionService 也服务引擎运行。后台已有 Flow/Execution
对象的状态推进与执行清理继续使用已有运行协议，无需伪造用户 Session。
`FlowEngine` 的启动、关闭属于应用生命周期，不需要用户 Session。

`engine.close()` 幂等停止 Scheduler、Worker、SystemWorker、Executor、Indexer，
保留调用应用 Context 和数据源。应用关闭时也会回收引擎。
已尝试启动的原生服务停止后不可在同一 Context 重启，应由调用方重建 Context。
使用 FlowEngine 时，原生致命错误通过 `FlowContext.shutdown()` 请求停止这些运行角色。
未捕获异常处理器在非测试环境也不退出 JVM，调用应用继续拥有进程生命周期。

## 插件和导出

插件公共接口位于 `org.flow.plugin.api.Plugin`，注解位于
`org.flow.plugin.api.annotations`，语言枚举位于 `org.flow.plugin.api.enums`。
独立制品坐标为 `org.flow:plugin-api`；core 会传递导出该依赖。旧 `model` 制品与旧包名
不提供兼容入口，外部插件须更新引用并重新运行 processor 编译，生成
`META-INF/services/org.flow.plugin.api.Plugin` 注册资源。

`flow.plugins.path` 非空时必须指向存在的目录，原生 Registry 工厂在返回注册表前
扫描该目录。外部插件由 processor 生成注册资源，使用独立 JAR/ClassLoader 加载。

业务插件查询统一使用 `PluginService.plugins(session)`、`plugin(session, identifier)` 和
`versions(session, type)`。注册表是引擎级共享目录，Session 校验不会产生租户专属插件列表。
离线导出工具内部使用 `org.flow.core.docs.PluginExportService.export(destination, includeCore)` 导出 Markdown、JSON Schema、SVG。目标目录必须
尚不存在且父目录存在。离线聚合/配置 Schema 仍由 tooling 生成：

```bash
./gradlew :tooling:exportPluginSchemas :tooling:exportConfigurationSchema \
  -PschemaPlugins=/absolute/path/plugins
```

插件目录必须显式指定且已存在；空目录仅导出内置插件，不再提供示例插件模块。

core 已包含自身持久化实现，因此 tooling 的 classpath 会传递包含 jdbc、gen 和
数据库依赖。工具仍不启动 ApplicationContext、不连接数据库，只读取类型和 Bean
元数据；工具运行隔离允许这些数据库库，继续禁止实际运行角色和代码生成器进入。

## 验证

```bash
bash docs/harness/verify-flow.sh
bash docs/harness/verify-flow.sh --full-regression
```

根 `src/test` 和其中的跨模块测试、强杀探针已删除。当前测试结果在各模块的
`build/reports/tests/test/`。用户确认将依赖 `tests`、`storage-local` 的测试及其
专用资源一并删除，不迁移辅助代码或保留测试存储实现；保留不依赖二者的普通测试
和数据库技术测试，包括 PostgreSQL 设置仓储、迁移与生成元数据验证。
默认入口执行 core 模块使用模拟角色的 `FlowEngineTest`，以及迁入
`org.flow.core.jdbc.postgres` 的真实 PostgreSQL 测试 `PostgresSettingRepositoryTest`、
`PostgresSortMetadataTest`，并检查构建、运行隔离
与 Schema。`--full-regression` 在独立 PostgreSQL 上执行当前仍保留的全部模块测试，
不包括已经删除的集成场景；临时数据库由脚本创建和清理。
数据库技术测试覆盖其目标组件的装配和读写；模拟角色测试只验证引擎生命周期协调。
这些检查不能证明完整引擎执行或宿主存储接入已经通过运行验收。
验证结论必须结合本轮实际报告，不以旧报告代表通过。
下面按日期记录的根测试、模块数量和来源校验结果属于当时版本，不表示这些入口
在当前版本仍然存在。

### 2026-10-14 Java 版本选择验证

按 ADR 0125 移除固定 Java 25，最低支持 Java 21。本机默认 Microsoft OpenJDK
21.0.12；通过外部 Gradle init 配置另选 25.0.4。17 个项目的编译器、测试 launcher
及目标版本元数据分别随之为 21 或 25，两个 Schema 工具也跟随所属项目配置。
没有通过删除目标版本元数据绕过兼容性检查。

Java 21 下生产及测试源码编译、JAR、Schema 导出、根和 tooling 运行隔离检查通过。
完整回归执行 `bash docs/harness/verify-flow.sh --full-regression --offline
--no-build-cache --rerun-tasks --console=plain`，耗时 27 分 15 秒；14 个有测试的工程
全部实际执行，5,194 项中 5,165 项通过、26 项跳过、3 项失败。与上一轮 509 份
JUnit XML 比较，仅新增地址解析套件的 47 项测试，无缺失套件或新增失败。
3 项失败仍是 `PebbleDocBasicMdDriftTest` 找不到已退出的
`ui/src/assets/docs/basic.md`，完整回归返回非零，不能记为全绿。

完整回归结束后，又补齐 `jdk.net.allowAmbiguousIPAddressLiterals` 的 Unicode
数字兼容分支，保持 JDK 已缓存的策略且不查询 DNS。最终源码重新执行根引擎 16 项、
HTTP 客户端 101 项、tooling 11 项，全部通过；地址解析套件扩至 50 项，在 Java
21/25、上述属性 false/true 四组实际 Test JVM 中全部通过。另用 4,226 个输入比较
原 Java 25 解析语义，覆盖默认值和初始化后改写属性等 12 组组合，均无差异。
最后这一小补丁执行了上述专项回归，没有再重复第二轮全部模块测试。

独立临时消费工程仅依赖 `org.flow:flow:2.0.5`，不配置 Java 版本，通过 composite
build 编译并加载原生运行角色，输出 `host-java-consumer-ok java=21`。此检查不启动
Context；真实数据库装配与生命周期由根集成测试覆盖。

本轮日志、版本选择记录、完整回归的 510 份 JUnit XML、基线比较、最终专项结果
及独立消费证据位于本机忽略目录 `build/java-version-validation/`。历史 Java 25
验证记录继续保留其当时含义。

### 命名迁移

按 ADR 0122，Java 包和 YAML 任务类型统一为 `org.flow.*`，根消费坐标为
`org.flow:flow:2.0.5`，配置前缀为 `flow.*`，插件清单使用 `X-Flow-*`。
内部存储 URI 使用 `flow://`，脚本日志控制标记使用 `##flow:log:...##`。
表达式中的 `flow.*` 继续表示流程身份；引擎 URL 和环境信息通过 `engine.url`、
`engine.environment` 读取，避免覆盖 `flow.id`。已移除 Kotlp 可选包装；普通脚本输出与通用 OTLP 解析保留。
外部插件须使用当前 processor 和 API 重新编译；不自动加载旧包名插件或旧配置别名。

这是源码开发阶段的命名切换。请使用独立的新数据库 schema 和存储目录验证。
已有实例的流程 YAML、序列化类型、存储 URI 和迁移历史表需要单独迁移；本轮不自动
修改外部应用或已有数据库。历史 SQL 原文不变，以保留 checksum 和来源追溯。
示例里的 `example.com` URL 是占位值，运行前替换为实际可访问的数据或代码仓库地址。

### 2026-10-14 命名迁移专项

在 Java 25 和临时 PostgreSQL 17 上，迁移专项 173 项全部通过：根集成 16、core 136、
script 10、tooling 11。覆盖新包名插件/SPI/Manifest、配置 Schema、表达式变量、HTTP、
重试及移除 Kotlp 后的脚本执行。独立 Java 工程以 `org.flow:flow:2.0.5` 消费根依赖，
公开 API 编译及全部运行角色加载通过，输出 `flow-namespace-consumer-ok`。

1,839 个 Java 文件的包名与目录一致；17 个模块 JAR 均无旧包名或 Kotlp 二进制。
配置 Schema 根为 `flow`。10 个历史迁移 SQL 的原始 SHA-256 全部保持不变。
测试插件的离线重定位重复执行得到相同 JAR 哈希。

[专项摘要](../../build/namespace-validation/20261014/focused/summary.json)、
[专项 JUnit 结果](../../build/namespace-validation/20261014/focused/junit-results.zip)、
[独立消费验证](../../build/namespace-validation/20261014/consumer/gradle.log)、
[运行制品审计](../../build/namespace-validation/20261014/artifact-audit.json)。
这些本地产物不提交。

完整回归合计 5,147 项：5,115 通过、6 失败、26 跳过。script 的 10 项和 tooling 的
11 项复用本轮专项的 UP-TO-DATE 结果，其余有测试的模块均执行。调度器 140 项、
Worker 67 项和本地存储 68 项通过；根与 tooling 的运行依赖隔离检查通过。

- core 的 3 项 `PebbleDocBasicMdDriftTest` 失败与迁移前基线相同，依赖已删除的
  UI 文档。迁移前另外 3 项 HTTP 回归失败本轮未复现。
- 本轮新增的 3 项失败分别是 PostgreSQL 查询测试保留了旧包名的部分字符串
  （CONTAINS、ENDS_WITH），以及架构规则误将 `@FlowTest` 注解当成普通测试类。
  已修正测试输入，并在同包规则中排除注解类型，未修改查询实现或降低查询断言。
- 修正后复验 117 项全部通过：执行记录查询完整测试类 84 项、架构检查 6 项、
  根集成 16 项实际执行，tooling 11 项复用本轮结果。来源核对继续通过。
  这两处测试文件修正后未再次执行全套回归；原始完整回归仍保留 6 项失败记录，
  当前尚未解决的是上述 3 项历史文档校验失败。

[完整回归摘要](../../build/namespace-validation/20261014/full/summary.json)、
[完整 JUnit 结果](../../build/namespace-validation/20261014/full/junit-results.zip)、
[基线对比及复验结论](../../build/namespace-validation/20261014/full-baseline-comparison.json)、
[修正后复验摘要](../../build/namespace-validation/20261014/followup/summary.json)。

修正后复验命令：

```bash
bash docs/harness/verify-flow.sh \
  :jdbc-postgres:test --tests 'org.flow.repository.postgres.PostgresExecutionRepositoryTest' \
  :tests:test --continue
```

### 2026-10-13 本轮验证

以下是命名迁移前的历史证据，包名和坐标按当时版本记录。


使用 Java 25 和脚本创建的 PostgreSQL 17 临时容器：

- 全模块源码及测试源码编译通过，根与 tooling 的生产依赖隔离、来源哈希校验通过。
- 最终专项 37 项全部通过：根跨模块 16、core 生命周期与异常处理及代理复验 6、
  数据源池配置 4、tooling 11。独立 JVM 用记录型 Context 验证非测试环境的异常处理器
  请求 shutdown 后返回调用方，不主动退出进程；真实角色停止及容器、连接池保留由根集成测试验证。
- 完整回归报告合计 5,152 项：5,120 通过、6 失败、26 跳过。processor 的 7 项复用
  Gradle UP-TO-DATE 结果，其余有测试的模块均执行。PostgreSQL 983 项和 Worker 67 项通过。
- 全量的失败均在 core：`PebbleDocBasicMdDriftTest` 3 项依赖已删除的 UI 文档；
  `RequestRunnerTest` 2 项仍有非法 Unicode 请求正文问题；`HttpClientTest.getProxy`
  访问 Google 时发生 TLS 握手中断。前 5 项与既有基线一致，代理测试在最终专项复验通过。
  保留完整回归的原始失败记录，不将专项通过改写为全量通过。
- 独立 Java 工程仅依赖 `org.cses.flow:flow:2.0.5`，通过 composite build 编译上述公开
  API，并成功加载全部运行角色、PostgreSQL、队列、存储和脚本实现；未包含旧 embedded 类。

本轮本地证据：
[完整回归摘要](../../build/native-validation/20261013/full/summary.json)、
[完整 JUnit 结果](../../build/native-validation/20261013/full/junit-results.zip)、
[最终专项摘要](../../build/native-validation/20261013/final-focused/summary.json)、
[独立消费工程日志](../../build/native-validation/20261013/consumer/gradle.log)。
`build` 内证据不提交，清理后可通过本节命令重新运行测试。

最终专项的命令为：

```bash
bash docs/harness/verify-flow.sh \
  :core:test --tests io.kestra.core.runners.FlowEngineTest \
  --tests io.kestra.core.utils.ThreadUncaughtExceptionHandlerTest \
  --tests 'io.kestra.core.http.client.HttpClientTest.getProxy' \
  :jdbc:test --tests io.kestra.jdbc.DatasourcePoolSizeDefaulterTest --continue
```

## 2026-10-19 Session 接入历史验证

以下记录产生于本次直接 Service 调整前，不能作为调整后的验证结果。

Java 25.0.4 和本地 PaaS pass-2.0-neworg 下，生产/测试源码、JAR、依赖隔离及独立
消费工程 compileJava 通过。独立 PostgreSQL 17 全量回归为 2442 通过、1 项既有跳过、
0 失败，配置和插件 Schema 导出通过。执行命令：

```bash
# JAVA_HOME 指向 Java 25；默认联合构建 ../paas。
./gradlew classes jar verifyRuntimeIsolation testClasses
bash docs/harness/verify-flow.sh --full-regression --no-build-cache --console=plain \
  build :tooling:exportPluginSchemas
```

范围与宿主运行前提见 [ADR 0133](../decisions/0133-session-aware-public-services.md#验证)。

## 2026-10-19 直接 Service 调整验证

三个原有 Service 直接接收 Session 后，生产与测试编译、JAR、根和 tooling 运行依赖
隔离、独立宿主消费工程编译及配置/插件 Schema 导出通过。Java 25 与隔离 PostgreSQL
17 下执行上述完整回归命令，共 2454 项：2453 通过、1 项既有跳过、0 失败；未改动
模块沿用 Gradle UP-TO-DATE 结果。

SessionServiceWiringTest 在真实 Micronaut 容器中装配三个唯一 Service，直接保存与
查询流程、验证跨租户隔离、读取插件目录，并从 gen 的 QUEUES 表确认 SessionCommand
实际持久化。目录导入新增逐文件失败隔离回归。存储仅使用该装配测试专属替身并断言
无文件操作；本次未启动引擎或执行宿主真实存储上的完整流程。

日志与结果汇总保存在本机忽略目录 `build/direct-service-validation/`；详细验证边界见
[ADR 0133](../decisions/0133-session-aware-public-services.md#本次直接-service-调整)。
