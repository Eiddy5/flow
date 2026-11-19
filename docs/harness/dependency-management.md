# TOML 依赖管理

版本管理依据 [ADR 0123](../decisions/0123-manage-dependencies-with-version-catalog.md)，
采用与 CSES 相同的 Gradle Version Catalog 方式。独立 `platform` 模块已删除。
Micronaut 按模块声明的边界依据
[ADR 0124](../decisions/0124-scope-micronaut-dependencies-by-module.md)，内部框架接入依据
[ADR 0126](../decisions/0126-use-internal-micronaut.md)。当前生成对象与必要 jOOQ/PaaS
依赖依据 [ADR 0127](../decisions/0127-use-generated-database-objects.md)，修订此前
“只使用内部 Micronaut、不引入 PaaS”的限制。文末历史记录保留各次验证当时的版本
和范围，不能作为新依赖组合的验收结果。
独立 `tests`、`storage-local` 模块已按
[ADR 0129](../decisions/0129-remove-tests-and-local-storage-modules.md) 删除：依赖它们的
测试和专用资源一并删除，不迁移测试支持；生产存储由宿主提供。
数据库底层及持久化归属按
[ADR 0130](../decisions/0130-co-locate-persistence-with-owning-modules.md) 调整：删除
queue-jdbc、jdbc-postgres；按 ADR 0131 移除 plugin-fixture，当前注册子模块为 13 个，script 保持独立。

## 维护位置

- `gradle/libs.versions.toml`：外部库的坐标和显式版本；无版本的库由第三方 BOM 管理。
- 各模块 `build.gradle`：通过 `libs` 声明所需依赖和作用域。
- `core/build.gradle`：导出 Micronaut BOM 和公开框架 API，声明核心运行装配库；
  依赖 jdbc/gen 以提供通用仓储基类、core 自身仓储和迁移适配，通过官方
  Micrometer/Netty BOM 保留这两组第三方组件的既有版本。
- `jdbc/build.gradle`：数据库底层的 jOOQ、数据源、事务执行及通用工具依赖；
  不通过 core 获得依赖或约束，按需显式声明平台与实际组件。
- queue、executor、worker、scheduler、indexer 的 `build.gradle`：按本模块的
  持久化和表配置职责依赖 core/jdbc/gen，以及所需处理器和测试库。
- `gen/build.gradle`：生成对象与独立 codegen source set；为生成和运行 classpath
  声明配套 Micronaut 平台及内部 jOOQ/PaaS BOM，数据库适配器消费该模块。
- 根 `build.gradle`：通用共享依赖、Jackson/Jetty BOM 和少量传递依赖约束。共享配置中使用
  `rootProject.libs`，避免从子项目隐式查找根项目属性。

例如在 TOML 中统一定义版本和坐标：

```toml
[versions]
postgresql = "42.7.13"

[libraries]
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
```

模块按实际职责选择作用域：

```groovy
dependencies {
    implementation libs.postgresql
}
```

使用内部 Micronaut 平台 BOM，Jackson、Jetty、JUnit、Micrometer 和 Netty 使用官方 BOM；直接
引用 BOM 的 Gradle `platform(...)` 语法不表示存在本地 `platform` 子模块。
gen 使用内部 jOOQ、jooq-paas 和 codegen extension，允许 `JooqPojo` 必要的 PaaS
common、json、sql、exception，以及内部 Bean 装配所需的 paas-pulsar 和必要客户端。
core 另以非传递 api 依赖引入 paas-session，使用会话合同。Session 开关由宿主管理。
不引入 PaaS 聚合包、AuthX 或 CSES 业务。
PostgreSQL JDBC 驱动仍使用现有官方依赖，Flow 工作队列仍采用 JDBC。

版本目录负责提供依赖声明，不能独自约束所有传递依赖。根共享配置保留必要的
Commons Compress、Gson、jOOQ、Plexus Utils 和 Jakarta Annotation 约束；处理器
classpath 还保留 SLF4J 的版本约束。版本只在 TOML 中维护。

AssertJ 有两个别名：`libs.assertj.core.managed` 显式记录原 BOM 选定的版本，
`libs.assertj.core.pinned` 在 core、executor、worker、jdbc、scheduler、queue、indexer
及 tooling 中显式保留已有版本；indexer 承接日志 PostgreSQL 测试，script 已无对应
测试，不再声明该依赖。
升级时应检查两者对测试 classpath 中 Byte Buddy 等传递依赖的影响。

## 内部 Micronaut 与消费方仓库

当前 TOML 选择 `io.micronaut.platform:micronaut-platform:5.0.3.29`，对齐本地 PaaS
`pass-2.0-neworg`。用户已确认本次采用 Java 25 联合构建，修订此前 Java 21/Micronaut 4
组合。SessionUser 尚未发布，settings.gradle 默认 includeBuild('../paas')，路径可用
`-PpaasSourcePath` 或 `FLOW_PAAS_SOURCE_PATH` 覆盖；不向 Maven 旧版接口回退。
本地 PaaS 的必要模块整体按源码替换，避免 Session 和生成对象依赖的类协议错配。

各处显式 Micronaut BOM 声明均排除其自带的 PaaS BOM，例如 core 的导出声明：

```groovy
api(platform(libs.micronaut.platform)) {
    exclude group: 'org.paas'
    exclude group: 'org.paas.nlp'
    exclude group: 'org.paas.service'
    exclude group: 'org.redisson', module: 'redisson-micronaut-50'
}
```

`annotationProcessor` 和 `testAnnotationProcessor` 的独立 BOM 声明也使用同样
排除。gen 另外显式引入 PaaS `2.5.0.29` 与 jOOQ `3.21.4.1` BOM，避免由框架
平台隐式选择业务库版本。gen 的普通依赖和 codegen 依赖都采用普通 Micronaut
`platform(...)`、jOOQ/PaaS `enforcedPlatform(...)`；排除 jooq-paas 固定引入的
Micronaut JOOQ 版本，并显式使用所选平台管理的版本。

BOM 导入仅产生约束；根生产隔离对实际制品限定六个 PaaS 模块、五个 Apache Pulsar
客户端配套模块以及 jooq/jooq-paas，具体名称见 [生成指南](jooq-generation.md)。
内部 DBEventSender 及条件 Bean 定义需要 Pulsar 类型，因此保留必要客户端；PaaS
聚合包、AuthX、CSES 业务、HTTP Server Netty 和 jOOQ codegen/meta 不进入根运行产物。

相关依赖边局部排除 `micronaut-http-server-netty`。jdbc 通过
`FlowDatabaseContextConfigurer` 注册顺序为 `Integer.MIN_VALUE` 的
`flow-jdbc-runtime-defaults` PropertySource，使 `jooq.send-event`、
`table.db-change.listeners.pulsar.enabled`、`pulsar.consumer.enabled` 默认为 false。
宿主 YAML、环境变量、系统属性和 builder 显式配置可覆盖这些默认值；5 项默认值与
覆盖专项测试已通过。Flow 队列保持 JDBC，客户端类存在不等于需要连接消息服务。

内部平台还以 BOM 方式导入 `redisson-micronaut-50`，其父级约束会改变
Testcontainers 和 AssertJ 版本。Flow 直接声明的平台边排除该导入；本地 PaaS 项目导出的平台仍可能解析其 BOM 元数据，
因此内部仓库规则覆盖该四段版本坐标。根与 tooling 的隔离检查明确禁止任何
org.redisson 运行制品，解析 BOM 不代表引入 Redis 客户端。
core 显式导出官方 Micrometer `1.15.12` 和 Netty `4.2.18.Final` BOM，保留切换前
对应的第三方组件版本；BOM 坐标和版本仍由 TOML 管理。

根构建的 `repoUrl` 指向与 main 相同的内部仓库。仓库规则接管 `io.micronaut` 及其
子组和 `org.jooq` 下的四段及以上数字版本，并接管 `org.paas` 及其子组。其余官方
依赖继续由 Maven Central 解析。不能将整个
`io.micronaut.*` 组都独占交给内部仓库，因为它不代理所有官方组件。

外部消费工程的 `build.gradle` 也需要配置相同规则，`repoUrl` 由该工程自己的
Gradle 属性提供：

```groovy
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = 'internalLibraries'
                url = uri(providers.gradleProperty('repoUrl').get())
                allowInsecureProtocol = true
            }
        }
        filter {
            includeVersionByRegex 'io[.]micronaut([.].*)?', '.*', '[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+.*'
            includeVersionByRegex 'org[.]jooq', '.*', '[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+.*'
            includeGroupByRegex 'org[.]paas([.].*)?'
            includeVersionByRegex 'org[.]redisson', 'redisson-micronaut-50', '[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+.*'
        }
    }
}
```

仓库地址使用 `repoUrl=http://maven.wehanyu.com:8082/artifactory/java3/`，与根项目
一致。Gradle 仓库配置不会写入库的传递依赖元数据；无论使用已发布制品还是
`includeBuild`，消费工程解析这些依赖时都需要自己的仓库配置。内部 Micronaut、
jOOQ 和 PaaS 显式版本继续在 TOML 中维护，不需要额外构建插件。

当前未配置或执行 Maven 发布。此前内部 Micronaut 接入时的临时元数据探针发现：
Gradle Module Metadata 保留平台排除，而 Maven POM 的平台 import 不保留这些
排除。gen 新增 enforced BOM 和运行依赖后，发布元数据与目标消费者还需重新验证，
不能直接套用此前 Gradle composite build 或探针结果。

当前采用 Java 25，JDK 选择原则仍按
[ADR 0125](../decisions/0125-use-build-selected-java-version.md)：不固定 toolchain、
source、target 或 release；发布制品的实际字节码由其构建环境决定。内部 BOM 对
第三方组件也有约束，调整版本时必须比较全部配置的实际解析结果，区分本次授权的
内部 jOOQ/PaaS 变化与其他非预期升级、降级。

## Micronaut 的模块边界

不在根 `allprojects` 中配置 Micronaut 依赖、处理器或测试框架。各模块分别选择
`api`、`implementation`、`runtimeOnly`、`annotationProcessor` 和测试作用域。
core 导出的 BOM 沿正常项目依赖提供版本约束；jdbc 不依赖 core，须声明自身所需
平台与组件。处理器 classpath 独立于普通编译 classpath，使用 Micronaut 处理器的
配置需显式引用 BOM。

| 模块 | 生产或工具需求 | 处理器与测试需求 |
| --- | --- | --- |
| 根 flow | 聚合原生运行模块，无生产源码 | 根测试源码已删除 |
| core | 公开 Context/Data Model/HTTP API；运行装配、通用仓储基类、自身仓储与迁移适配 | 主源码生成注入及校验元数据；保留数据库技术测试按需使用测试处理器及 Micronaut Test |
| gen | 数据库生成对象与 JooqPojo；Micronaut Data 注解及内部 jOOQ 配套依赖 | 独立 codegen source set 显式连接数据库；普通编译不执行生成，不配置 Micronaut 注入处理器 |
| jdbc | 数据源、jOOQ、事务执行和通用数据库工具；不依赖 core | 按底层装配需要生成元数据；测试按实际需求声明 |
| queue | 队列 Bean、配置、生命周期、持久化及定时清理 | 主源码生成所需 Bean/配置元数据 |
| executor、worker、scheduler、indexer | 运行角色及各自持久化与表配置；端点归 worker/scheduler | 主源码处理器；测试侧仅声明保留测试实际需要的依赖 |
| script | 无直接 Micronaut 库声明，依赖 core 的协议及运行环境 | 保留插件 SPI 处理器；依赖已删除模块的集成测试退出 |
| plugin-api、processor | 无自身 Micronaut 需求 | 不配置 Micronaut 处理器或 Micronaut Test |
| tooling | Context API 用于读取 Bean 元数据；经 core 带入 jdbc/gen 及数据库库，不启动容器或连接数据库 | 无主源码或测试 Micronaut 处理器，单元测试不声明 Micronaut Test |
| http | 可选宿主 HTTP 适配模块。编译期使用 PaaS 响应包装注解，运行时使用宿主 PaaS Web | 主源码生成路由元数据。测试侧启动 Netty 并加载 PaaS 响应过滤器 |

主源码没有框架 import 不足以证明某个运行库可以删除。core 保留校验、指标、
Prometheus、Tracing 和响应式集成所需的自动装配；生产 HTTP 调用使用 Apache
HttpClient。HTTP 协议类型和通用 Tracing 继续保留；测试依赖按剩余测试的实际调用
清理。PaaS 依赖边局部排除 HTTP Server Netty，Pulsar 类型为内部 Bean 装配保留。
实际制品隔离与 Context 启动是不同验证层次，前者不能证明后者通过。

数据库实现使用 `micronaut-data-tx-jdbc` 提供 jOOQ 事务和 JDBC 连接管理，不引入
未使用的 `micronaut-data-jdbc` 生成式 Repository/异步层。测试依赖不能补齐缺少的
生产事务装配。

项目依赖方向为 core → jdbc/gen，各运行能力模块 → core/jdbc/gen。jdbc 不依赖
core 或运行角色；core 继续通过角色契约协调运行，不反向依赖这些实现模块。各能力
数据库实现统一位于 `org.flow.<owner>.jdbc`，底层为 `org.flow.jdbc`。

上述方向指生产依赖。core 的数据库技术测试通过 `testRuntimeOnly` 加载 queue、
executor、worker、scheduler、indexer 的迁移和装配，使真实 PostgreSQL 测试能够
使用完整 Schema；这些依赖不传递给 core 的生产消费者。

tooling 通过 core 得到数据库 classpath 是本次模块边界调整的取舍。工具运行隔离
允许 jdbc、gen 及所需数据库库，仍禁止实际运行角色和 jOOQ codegen/meta；导出
过程仍不创建 ApplicationContext、不连接数据库。依赖库包含 Bean 定义不等于
工具启动了这些 Bean，需分别验证依赖组成和离线导出行为。

删除直接声明不会消除通过 core 等模块传入的框架依赖，也不会把框架改成可选的
运行环境。插件 SPI 处理器生成服务注册文件，Micronaut 处理器生成 Bean/配置/校验
元数据，两者不能互相替代。

`tests` 中的辅助代码和架构测试不迁移到其他模块，不保留本地存储的测试替代实现。
依赖这两个模块的测试和专用资源一并删除；失去使用方的测试依赖、处理器和配置
随调用链清理，保留不依赖二者的普通测试和数据库技术测试所需依赖。移除 `storage-local`
后，宿主存储接入方式见
[Java 使用指南](java-engine.md#存储实现)。

gen 的结构来源、临时 PostgreSQL 生成入口、可写列与原生 JSONB 边界见
[生成指南](jooq-generation.md)。core 和各能力模块中的数据库实现依赖 gen，
core 的领域和服务契约不使用生成对象。混合基线 SQL 留在 core，专属 SQL 随能力
归入对应模块的 migrations 资源目录，保持原 classpath 路径和迁移协议。普通编译和
离线 Schema 导出不会隐式运行迁移或生成器。

## 比较依赖变更

在修改前后分别运行以下命令，调整输出文件名：

```bash
./gradlew -I docs/harness/capture-dependency-versions.init.gradle \
  captureDependencyVersions \
  -PdependencySnapshot=build/dependency-before.json --offline --warning-mode all

# 完成依赖修改后
./gradlew -I docs/harness/capture-dependency-versions.init.gradle \
  captureDependencyVersions \
  -PdependencySnapshot=build/dependency-after.json --offline --warning-mode all

diff -u build/dependency-before.json build/dependency-after.json
```

该入口只解析依赖，不执行测试；离线执行要求本机已经缓存对应制品，首次拉取可去掉
`--offline`。快照包含各配置的组件坐标，以及实际制品标识和文件名。BOM 是版本元数据，
应分别比较 `components` 和 `artifacts`；快照不比较依赖边、制品内容或 classpath 顺序。

依赖调整后验证编译、剩余测试、运行隔离与 Schema：

```bash
./gradlew clean classes testClasses jar verifyRuntimeIsolation \
  :tooling:verifyRuntimeIsolation :tooling:exportPluginSchemas \
  :tooling:exportConfigurationSchema -PschemaPlugins=/absolute/path/plugins --offline
bash docs/harness/verify-flow.sh --full-regression --offline
```

修改注解处理器后先执行 `clean`，避免旧的 Bean Definition 或校验元数据残留导致
假通过。完整回归通过脚本创建的独立 PostgreSQL 执行保留测试，其中数据库技术
测试覆盖相应组件的装配和读写；默认入口选择 core 模块使用模拟角色的
`FlowEngineTest`，以及已迁入 `org.flow.core.jdbc.postgres` 的
`PostgresSettingRepositoryTest` 和 `PostgresSortMetadataTest`。这些检查不再覆盖
已删除的完整引擎和存储集成场景，不能证明宿主存储接入及完整引擎运行已经通过。
宿主实际接入时需要另外执行真实存储和 PostgreSQL 环境下的运行验证。

## 2026-10-14 对齐记录

- TOML 包含 100 个库别名和 45 个版本项；根及 16 个子模块使用同一目录。
- 已移除本地平台、四个直接 BOM 引用以及 33 个无用库别名；原始构建快照保持不变。
- 共核对 119 个配置。仅移除本地平台时，除 `org.flow:platform:2.0.5` 外的解析组件
  全部一致；精简 BOM 后，119 个配置的实际制品与版本全部一致。
- 编译、测试源码编译、JAR、根和 tooling 依赖隔离、插件和配置 Schema 导出通过。
- 仅依赖 `org.flow:flow:2.0.5` 的独立消费工程通过 API 编译和原生运行类加载检查；
  该检查不启动 Context。真实 PostgreSQL 上的 16 项根模块测试全部通过，覆盖执行、
  插件、生命周期与进程强杀后的恢复。
- 完整回归耗时 25 分 10 秒：5,147 项中 5,118 项通过、26 项跳过、3 项失败；
  所有包含测试的模块均实际执行，未复用测试结果缓存。PostgreSQL 模块 983 项全部通过。
- 3 项失败均来自已有的 `PebbleDocBasicMdDriftTest`，分别检查 Filter、执行上下文和
  Function 文档。它们依赖已删除的 UI `basic.md`，在迁移前已失败。本次没有新增失败，
  但完整回归命令仍返回失败，不能记为全绿。
- 来源核对与 `git diff --check` 通过。构建中原有的 Lombok/JDK 弃用提示保留；本次
  未新增 Gradle 隐式父项目属性查找警告。

本机详细证据保留在忽略目录 `build/catalog-validation/20261014/`。上述历史结果不代表
已经验证内部 Micronaut，也不代表真实 CSES 应用已经接入。

## 2026-10-14 Micronaut 按模块收窄记录

- 根共享配置不再创建 `micronaut` 依赖组，不再统一加入 Micronaut BOM、注入/校验
  处理器或 Micronaut Test；模块直接使用 TOML 别名声明实际用途。
- 已比较调整前后 119 个解析配置：所有保留的实际制品版本一致。根运行制品由
  226 个降至 205 个，core 运行制品由 200 个降至 177 个；减少项包括未使用的
  Micronaut HTTP/Netty 和 Data Repository 层，不把 BOM 元数据计入该数量。
- `model`（现名 `plugin-api`）、`processor` 的主源码处理器依赖由 26 个降至 1 个（仅 Lombok），
  两模块的运行依赖不变，且生产和测试处理器中均不再含 Micronaut。
- 编译、测试源码编译、JAR、根及 tooling 依赖隔离通过；插件和配置 Schema
  实际重新导出成功。清理编译输出后执行禁用构建缓存的强制回归，避免残留生成物。
- Micronaut Bean Definition、拦截器与内省生成类清单和调整前一致。

- 完整回归使用 `bash docs/harness/verify-flow.sh --full-regression --offline
  --no-build-cache --rerun-tasks --console=plain`，Gradle 耗时 26 分 14 秒。14 个有测试的
  工程全部实际执行；5,147 项中 5,118 项通过、26 项跳过、3 项失败。
- 与上一次完整回归的 509 份 JUnit XML 比较，各模块及每个测试套件的测试数量
  完全相同，无套件增减，无新增失败。3 项失败仍来自 `PebbleDocBasicMdDriftTest`
  无法找到已退出的 `ui/src/assets/docs/basic.md`；完整回归命令仍返回非零，不能记为全绿。
- 根模块的 16 项真实 PostgreSQL/引擎验证与 PostgreSQL 模块的 983 项测试全部通过，
  包含队列回滚、独立事务、插件装配、生命周期和进程强杀后的恢复。
- 仅依赖 `org.flow:flow:2.0.5` 的独立临时工程执行 `clean run` 通过，输出
  `scoped-micronaut-consumer-ok`。它验证公开 API（含宿主 ApplicationContext 入口）的
  编译和原生运行角色类加载，不启动 Context；真实容器装配由前述 PostgreSQL 测试覆盖。
- 来源账本验证与 `git diff --check` 通过，测试专用 PostgreSQL 容器已清理。

本轮解析快照、编译日志、509 份 JUnit XML、基线比较和独立消费日志位于本机忽略目录
`build/micronaut-scope-validation/`。上述收窄验证当时使用官方 Micronaut，未切换到内部框架。

## 2026-10-14 内部 Micronaut 接入记录

本次使用内部平台 `4.10.6.52`，保留 TOML 管理、各模块框架作用域与 Java 21 下限，
每处显式平台引用排除 PaaS BOM 及无关的 Redisson 导入。仓库只路由内部 Micronaut
版本；非 Micronaut 依赖继续保持原有官方来源及版本。

已解析全部 119 个配置，对生产、测试和注解处理器 classpath 合计 47 个实际
Micronaut JAR 检查普通类及 Java 21 会使用的 Multi-Release 类，未发现高于
Java 21 的字节码。这项检查不覆盖运行时 API 和行为兼容性。

- 修改前后全部 119 个配置的非 Micronaut 实际制品及版本完全一致；解析组件中
  不含 PaaS、AuthX 或 Redisson，连对应 BOM 导入也已排除。
- Java 21.0.12 下清理旧生成物后，全模块生产/测试编译、JAR、根与 tooling 的
  运行依赖隔离、插件及配置 Schema 导出均通过。16 个模块的 2,537 个生产类
  最高字节码版本为 65（Java 21）；未添加固定 toolchain 或伪造目标元数据。
- 完整回归采用 `bash docs/harness/verify-flow.sh --full-regression --offline
  --no-build-cache --rerun-tasks --console=plain`，32 分 33 秒，95 个任务实际执行。
  14 个有测试的工程、510 个测试套件共 5,197 项：5,168 通过、26 跳过、3 失败。
- 3 项失败均为既有 `PebbleDocBasicMdDriftTest`，依赖已删除的 UI `basic.md`；
  与前次完整回归逐项一致，无新增失败、缺失工程或缺失套件。完整命令返回非零，
  不能记为全绿。比旧全量基线多出的 3 项来自上一轮已加入的 Unicode IP 参数用例，
  本轮未修改测试源码；当前 IP 字面量测试 50 项全部通过。
- 根模块 16 项真实引擎/PostgreSQL 验证、PostgreSQL 模块 983 项、Executor 199 项
  （8 跳过）、Scheduler 140 项、Worker 67 项均无失败。专用 PostgreSQL 容器已清理。
- 仅依赖 `org.flow:flow:2.0.5` 的独立临时 Gradle 工程完成串行 `clean run
  verifyDependencies`，输出 `host-java-consumer-ok java=21` 和
  `internal-micronaut-consumer-dependencies-ok`。它验证公开 API、运行角色类加载、
  内部 Context `4.10.12.20`、官方 jOOQ `3.21.7` 及无 PaaS 依赖；不启动 Context，
  真实装配由前述数据库测试覆盖。
- 元数据探针确认 `.module` 保留平台排除，POM 不保留；本次未发布制品，也未验证
  Maven POM 消费。探针为生成元数据临时允许了原有 enforced BOM，未增加正式发布配置。
- 首次候选编译受到未排除的 Redisson BOM 影响；排除后第三方依赖恢复。首次完整
  回归又因同时运行 composite 消费构建发生生成文件竞争，该次结果不作为证据。
  结束并行构建、清理输出后，上述完整回归及最终消费复验均串行执行。
- 来源账本与 `git diff --check` 通过；本轮未修改生产 Java 源码，未提交或推送。

本机详细证据位于 `build/internal-micronaut-validation/`，包括前后依赖快照、字节码
清单、编译日志、510 份 JUnit XML 压缩包、基线比较、独立消费与元数据探针结果。
此前官方框架验证记录保留为历史基线；本次结果不代表真实 CSES 应用已接入。

## 2026-10-15 gen 与内部 jOOQ 接入记录

- 清理全部子模块后，Java 21 全模块编译、testClasses 和 JAR 构建通过；73 个生成
  Java 文件重复生成的 SHA-256 完全一致。
- 完整回归约 27 分钟，101 个任务全部实际执行。15 个有测试的工程、514 个套件
  共 5,246 项：5,216 通过、26 跳过、4 失败。相对 `fc1e3f4` 归档基线新增 49 项
  全部通过，没有缺失工程或套件；完整命令返回失败，不能记为全绿。
- 3 项仍为 `PebbleDocBasicMdDriftTest` 缺少已退出的 UI `basic.md`；另 1 项
  `PurgeKVTest.version_filter_by_date` 预期 1、实际 0，已在当前代码与提交前
  `fc1e3f4` 的同一 PostgreSQL 容器中复现。原始用例与临时诊断用例在两边各失败
  1 项：既有触发器在旧版本 `last=false` 时刷新物理 `updated`，分别晚于 cutoff
  670/823 微秒，而 JSON 时间未变。四组离线 SQL 及绑定精度一致；未为该失败
  修改业务语义、原测试断言或迁移。此前通过时没有精确时序证据，不推测原因。
  诊断证据位于 `build/gen-validation/kv-diagnosis/README.md`。
- 根模块 16 项真实 PostgreSQL/引擎/生命周期/强杀测试通过；PostgreSQL 模块 984 项
  通过，覆盖自定义带引号表名与 schema。gen 36 项、JDBC 新增 7 项元数据及
  5 项默认值/宿主覆盖专项均通过。
- 全量统计保存在 `build/gen-validation/test-summary.json`，JUnit 归档为
  `build/gen-validation/junit-results.zip`。后续 focused 复验单独保存，不覆盖全量统计。
- 最终 129 个配置全部解析成功；410 个外部 JAR 的普通类与 Java 21 生效的
  Multi-Release 类检查无缺失、无高于 major 65 的类。2,612 个生产类最高 major 65。
- 根 runtime 共 278 个制品（含项目制品），gen 为 152 个，tooling 为 181 个。
  根与 gen 均无 HTTP Server Netty 和 jOOQ codegen/meta；实际五个 PaaS 模块
  均为 `1.0.0.161`，jooq/jooq-paas 及 codegen 配置的 jOOQ 家族统一为 `3.21.4.1`。
- 仅依赖 `org.flow:flow:2.0.5` 的独立 composite 工程 `clean run verifyDependencies`
  用时 9 秒通过，输出 `host-java-consumer-ok java=21` 和
  `gen-consumer-dependencies-ok`。覆盖 API 编译、角色类加载及最终依赖，不启动
  Context；真实装配由根 PostgreSQL/引擎测试覆盖，不代表 Maven 发布验收。
- 最终来源账本校验和 `git diff --check` 通过。

完整回归仍保留 4 项失败，不能记为全绿。生成与运行证据及适用边界见
[生成指南](jooq-generation.md)。此前无 PaaS 的内部 Micronaut 验证记录保留原意，
不作为本次新依赖组合的通过证据；本轮未发布 Maven 制品。

## 2026-10-19 Session 与本地 PaaS 联合构建

联合构建见 [ADR 0133](../decisions/0133-session-aware-public-services.md)，Session 使用范围见
[ADR 0134](../decisions/0134-limit-session-to-user-service-entrypoints.md)。core 的
paas-session 为非传递 api 依赖，并显式导出公共签名涉及的 paas-common、paas-json
及 micronaut-json-core 类型。`paas.session.enabled` 由宿主管理。
与现有 gen 所需的 common/json/sql/exception/pulsar 一起使用本地 neworg 源码。
未使用的 pulsar-client-admin-original 在 gen 和 jdbc 的相关依赖边排除，保留既有
Admin API 类型；运行隔离检查继续禁止其实现、Redis、HTTP Server 和其他 PaaS 模块。

Micronaut 5 自身使用 Jackson 3；Flow 的原生队列、数据库 JSON 和插件序列化继续使用
Jackson 2，并由独立 ObjectMapper 工厂提供，不再替换框架的 Jackson 3 工厂。
上传资源按 Micronaut 5 CompletedFileUpload.closeAsync 协议释放；Swagger 注解统一为
2.2.50，删除旧版本 defaultValue 哨兵修正。Schema 导出纳入本次回归。

## IDEA 模块同步与源码标红排查

模块删除、更名或本地 PaaS 分支切换后，先使用当前依赖所需的 Java 25 执行
`./gradlew clean classes testClasses :gen:codegenClasses --no-build-cache`，区分源码
错误与 IDE 模型问题。`Session`、`SessionUser` 使用处必须导入 `org.paas.session`
中的类型；不要用旧依赖或通配泛型掩盖缺少导入的编译错误。

若命令行通过而 IDEA 仍无法解析 `org.flow.plugin.api.Plugin` 或 PaaS Session，
检查 Gradle 同步日志和模块依赖。当前 core 必须依赖 plugin-api 及本地 paas-session；
旧 model、tests、storage-local、plugin-fixture、queue-jdbc、jdbc-postgres 不应残留。

本机曾出现 `removeContentEntry: removed content entry url .../gen/build/generated/
sources/annotationProcessor/java/test still exists after removing`，导致同步中断，
IDE 保留旧模块图。仅修改源码或重复点击同步不能清除已加载的旧记录。处理顺序：

1. 关闭 Flow 工程，保留其他工程；备份本工程的 IDEA 模块元数据与缓存。
2. 清理本工程 `.idea/modules.xml`、`.idea/modules/`、`.idea/compiler.xml`，以及
   IDEA 缓存目录中本工程标识下的 `external_build_system` 和 `project-model-cache`。
   保留 `.idea/gradle.xml`、编辑器偏好、运行配置和其他项目缓存。
3. 重新打开工程，使用 Java 25 从 Gradle 重建模块、注解处理路径与依赖；等待索引结束，
   确认同步日志成功且不再出现上述错误，再核对原先标红的位置。

这些是本机可重建的 IDE 数据，不提交到仓库，也不通过手写模块依赖替代 Gradle 模型。

2026-10-19 验证：补齐 FlowService 的两个 Session 导入后，干净构建覆盖所有生产、
测试及 codegen 源码并通过；IDE 项目模型重建成功，用户确认 Plugin 导入和 Session
方法两处标红均消失。隔离 PostgreSQL 17 完整回归 2454 项，2453 通过、1 项既有
跳过、0 失败，JAR、运行依赖隔离和 Schema 导出通过。日志与结果汇总保存在本机
忽略目录 `build/ide-validation/`。
