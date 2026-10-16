# TOML 依赖管理

版本管理依据 [ADR 0123](../decisions/0123-manage-dependencies-with-version-catalog.md)，
采用与 CSES 相同的 Gradle Version Catalog 方式。独立 `platform` 模块已删除。
Micronaut 按模块声明的边界依据
[ADR 0124](../decisions/0124-scope-micronaut-dependencies-by-module.md)，内部框架接入依据
[ADR 0126](../decisions/0126-use-internal-micronaut.md)。当前生成对象与必要 jOOQ/PaaS
依赖依据 [ADR 0127](../decisions/0127-use-generated-database-objects.md)，修订此前
“只使用内部 Micronaut、不引入 PaaS”的限制。文末历史记录保留各次验证当时的版本
和范围，不能作为新依赖组合的验收结果。

## 维护位置

- `gradle/libs.versions.toml`：外部库的坐标和显式版本；无版本的库由第三方 BOM 管理。
- 各模块 `build.gradle`：通过 `libs` 声明所需依赖和作用域。
- `core/build.gradle`：导出 Micronaut BOM 和公开框架 API，声明核心运行装配库；
  通过官方 Micrometer/Netty BOM 保留这两组第三方组件的既有版本。
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
不引入 PaaS 聚合包、AuthX 或 CSES 业务。
PostgreSQL JDBC 驱动仍使用现有官方依赖，Flow 工作队列仍采用 JDBC。

版本目录负责提供依赖声明，不能独自约束所有传递依赖。根共享配置保留必要的
Commons Compress、Gson、jOOQ、Plexus Utils 和 Jakarta Annotation 约束；处理器
classpath 还保留 SLF4J 的版本约束。版本只在 TOML 中维护。

AssertJ 有两个别名：`libs.assertj.core.managed` 显式记录原 BOM 选定的版本，
`libs.assertj.core.pinned` 保留 `tests`、`tooling` 原有的显式版本。升级时应检查两者
对测试 classpath 中 Byte Buddy 等传递依赖的影响。

## 内部 Micronaut 与消费方仓库

当前 TOML 选择 `io.micronaut.platform:micronaut-platform:4.10.6.335`，平台管理内部
核心 `4.10.12.23` 与 SQL 集成 `6.3.2.13`。沿用的是 main 的仓库和 BOM 接入方式，不复制 main 的平台
`5.0.3.51`：其内部核心 `5.0.4.2` 已包含 Java 25 字节码，不满足 Java 21 下限。
版本是否可用仍需检查所用扩展、处理器和运行链路，不能仅凭核心 JAR 判断。

各处显式 Micronaut BOM 声明均排除其自带的 PaaS BOM，例如 core 的导出声明：

```groovy
api(platform(libs.micronaut.platform)) {
    exclude group: 'org.paas'
    exclude group: 'org.paas.nlp'
    exclude group: 'org.paas.service'
    exclude group: 'org.redisson', module: 'redisson-micronaut-40'
}
```

`annotationProcessor` 和 `testAnnotationProcessor` 的独立 BOM 声明也使用同样
排除。gen 另外显式引入 PaaS `1.0.0.161` 与 jOOQ `3.21.4.1` BOM，避免由框架
平台隐式选择业务库版本。gen 的普通依赖和 codegen 依赖都采用普通 Micronaut
`platform(...)`、jOOQ/PaaS `enforcedPlatform(...)`；排除 jooq-paas 固定引入的
Micronaut JOOQ 7.x，并显式使用平台管理的 6.x。

BOM 导入仅产生约束；根生产隔离对实际制品限定五个 PaaS 模块、五个 Apache Pulsar
客户端配套模块以及 jooq/jooq-paas，具体名称见 [生成指南](jooq-generation.md)。
内部 DBEventSender 及条件 Bean 定义需要 Pulsar 类型，因此保留必要客户端；PaaS
聚合包、AuthX、CSES 业务、HTTP Server Netty 和 jOOQ codegen/meta 不进入根运行产物。

相关依赖边局部排除 `micronaut-http-server-netty`。jdbc 通过
`FlowDatabaseContextConfigurer` 注册顺序为 `Integer.MIN_VALUE` 的
`flow-jdbc-runtime-defaults` PropertySource，使 `jooq.send-event`、
`table.db-change.listeners.pulsar.enabled`、`pulsar.consumer.enabled` 默认为 false。
宿主 YAML、环境变量、系统属性和 builder 显式配置可覆盖这些默认值；5 项默认值与
覆盖专项测试已通过。Flow 队列保持 JDBC，客户端类存在不等于需要连接消息服务。

内部平台还以 BOM 方式导入 `redisson-micronaut-40`，其父级约束会改变
Testcontainers 和 AssertJ 版本。本项目没有 Redis 职责，因此一并排除这个导入。
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

最低支持 Java 21，JDK 选择仍按
[ADR 0125](../decisions/0125-use-build-selected-java-version.md)：不固定 toolchain、
source、target 或 release；发布制品的实际字节码由其构建环境决定。内部 BOM 对
第三方组件也有约束，调整版本时必须比较全部配置的实际解析结果，区分本次授权的
内部 jOOQ/PaaS 变化与其他非预期升级、降级。

## Micronaut 的模块边界

不在根 `allprojects` 中配置 Micronaut 依赖、处理器或测试框架。各模块分别选择
`api`、`implementation`、`runtimeOnly`、`annotationProcessor` 和测试作用域。
core 导出的 BOM 沿正常项目依赖提供版本约束；处理器 classpath 独立于普通编译
classpath，使用 Micronaut 处理器的配置需显式引用 BOM。

| 模块 | 生产或工具需求 | 处理器与测试需求 |
| --- | --- | --- |
| 根 flow | 聚合原生运行模块，无生产源码 | 跨模块集成测试使用测试注入处理器及 Micronaut Test |
| core | 公开 Context/Data Model/HTTP API；校验、Jackson、重试、指标和运行装配 | 主源码与测试按需生成注入及校验元数据 |
| gen | 数据库生成对象与 JooqPojo；Micronaut Data 注解及内部 jOOQ 配套依赖 | 独立 codegen source set 显式连接数据库；普通编译不执行生成，不配置 Micronaut 注入处理器 |
| queue、queue-jdbc | 队列 Bean、配置、生命周期及定时清理 | 主源码生成所需 Bean/配置元数据 |
| jdbc、jdbc-postgres | 数据源、事务、jOOQ、条件装配 | 主源码处理器；数据库集成测试使用测试注入处理器及 Micronaut Test |
| executor、worker、scheduler、indexer | 运行角色、事件、定时任务、指标；端点归 worker/scheduler | 主源码处理器；集成测试使用测试注入处理器及 Micronaut Test |
| storage-local、script | 无直接 Micronaut 库声明，依赖 core 的协议及运行环境 | 保留插件 SPI 处理器；集成测试使用测试注入处理器及 Micronaut Test |
| model、processor | 无自身 Micronaut 需求 | 不配置 Micronaut 处理器或 Micronaut Test |
| plugin-fixture | 编译依赖 core/script，独立制品由宿主加载 | 只使用插件 SPI 处理器，不生成自身 Micronaut Bean |
| tooling | Context API 用于读取 Bean 元数据，不启动容器 | 无主源码或测试 Micronaut 处理器，单元测试不声明 Micronaut Test |
| tests | 共享容器测试工具、测试 Bean 和 HTTP 测试客户端 | 主源码注入处理器；自身普通单元测试不配置 Micronaut 测试处理器 |

主源码没有框架 import 不足以证明某个运行库可以删除。core 保留校验、指标、
Prometheus、Tracing 和响应式集成所需的自动装配；生产 HTTP 调用使用 Apache
HttpClient。HTTP 协议类型和通用 Tracing 继续保留，HTTP Client/Server 的测试用途
显式声明。PaaS 依赖边局部排除 HTTP Server Netty，Pulsar 类型为内部 Bean 装配
保留；实际制品隔离、默认事件/消费者状态与 Context 启动分别验证。

数据库实现使用 `micronaut-data-tx-jdbc` 提供 jOOQ 事务和 JDBC 连接管理，不引入
未使用的 `micronaut-data-jdbc` 生成式 Repository/异步层。根技术测试直接需要的
连接 API 使用 `micronaut-data-connection-jdbc`，不以测试依赖补齐生产事务装配。

删除直接声明不会消除通过 core 等模块传入的框架依赖，也不会把框架改成可选的
运行环境。插件 SPI 处理器生成服务注册文件，Micronaut 处理器生成 Bean/配置/校验
元数据，两者不能互相替代。

gen 的结构来源、临时 PostgreSQL 生成入口、可写列与原生 JSONB 边界见
[生成指南](jooq-generation.md)。数据库适配器依赖 gen，core 的领域和服务契约
不依赖生成对象；生成器工具不会随普通编译连接数据库。

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

依赖调整后按影响范围验证编译、独立消费与真实运行：

```bash
./gradlew clean classes testClasses jar verifyRuntimeIsolation \
  :tooling:verifyRuntimeIsolation :tooling:exportPluginSchemas \
  :tooling:exportConfigurationSchema --offline
python3 verify-upstream.py
bash docs/harness/verify-flow.sh --full-regression --offline
```

修改注解处理器后先执行 `clean`，避免旧的 Bean Definition 或校验元数据残留导致
假通过。清理后还需完成独立消费者编译/类加载和真实 PostgreSQL 装配验证；仅编译
或离线 Schema 导出不能证明引擎运行时自动装配仍然完整。

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
- `model`、`processor` 的主源码处理器依赖由 26 个降至 1 个（仅 Lombok），
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
