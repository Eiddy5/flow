# 直接通过 Java 使用流程引擎

本项目的根 Java Library 聚合原生运行模块，公开服务在 `core`，实现位于各原生模块。
在同一应用的 Micronaut ApplicationContext 中注入即可使用，无 embedded 模块、
替换工厂或额外启用开关。直接模块消费方需带齐 executor、worker、
scheduler、indexer、jdbc-postgres、queue-jdbc、storage-local 和需要的 script。

## 从另一个 Java 工程引用

当前源码尚未发布新的 Maven 制品。Gradle 调用工程可以通过 composite build 直接消费
本项目，模块版本和传递依赖使用本仓库配置，不需要复制 JAR：

框架使用内部 Micronaut 平台 `4.10.6.52`（核心 `4.10.12.20`）。调用工程也必须
配置内部框架仓库；仓库配置不会随 `includeBuild` 或发布元数据传递。具体仓库
过滤与 `repoUrl` 配置见 [依赖管理指南](dependency-management.md#内部-micronaut-与消费方仓库)。
引入 Flow 不需要添加其他 PaaS 模块。

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
通过运行依赖带入实现模块；tooling、tests、plugin-fixture 不进入运行 classpath。

## Java 版本选择

Flow 不固定 Java 25，也不设置固定的 `sourceCompatibility`、`targetCompatibility`
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

最低支持 Java 21：当前源码使用虚拟线程、`List.getFirst()` 等 Java 21 API，
现有依赖也有 Java 21 要求。
选择其他 JDK 时仍需完成编译和运行验证。以下历史记录中的 Java 25 是当次验证
环境，不是当前构建限制。决策见 [ADR 0125](../decisions/0125-use-build-selected-java-version.md)。

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
    type: local
    local:
      base-path: ${FLOW_STORAGE_PATH}
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

## Java 入口

下面对象均由同一个 Context 注入。初始化数据库是部署准备步骤，完成后启动运行角色：

```java
import org.flow.core.executor.command.Create;
import org.flow.core.executor.command.ExecutionCommand;
import org.flow.core.migration.MigrationRunnerInterface;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.flow.core.runners.FlowEngine;
import org.flow.core.services.FlowService;
import jakarta.inject.Inject;

@Inject MigrationRunnerInterface migrations;
@Inject FlowEngine engine;
@Inject FlowService flows;
@Inject DispatchQueueInterface<ExecutionCommand> commands;
@Inject ExecutionRepositoryInterface executions;

// 部署准备阶段显式调用；运行阶段不重复执行。
migrations.runOrFailIfLocked();
engine.start();

var flow = flows.create(GenericFlow.fromYaml("main", """
    id: hello
    namespace: example
    tasks:
      - id: message
        type: org.flow.plugin.core.log.Log
        message: Hello Flow
    """));
var command = Create.of(flow);
commands.emit(command);
String executionId = command.executionId();

// 这是一次异步执行；按需查询，直到观察到终态。
var execution = executions.findById("main", executionId);
```

`FlowParsingService.parse(tenantId, yaml, true)` 提供严格解析；
`FlowService.validate(tenantId, sources)` 提供完整定义校验，二者不保存 Flow。

`engine.close()` 幂等停止 Scheduler、Worker、SystemWorker、Executor、Indexer，
保留调用应用 Context 和数据源。应用关闭时也会回收引擎。
已尝试启动的原生服务停止后不可在同一 Context 重启，应由调用方重建 Context。
使用 FlowEngine 时，原生致命错误通过 `FlowContext.shutdown()` 请求停止这些运行角色。
未捕获异常处理器在非测试环境也不退出 JVM，调用应用继续拥有进程生命周期。

## 插件和导出

`flow.plugins.path` 非空时必须指向存在的目录，原生 Registry 工厂在返回注册表前
扫描该目录。外部插件由 processor 生成注册资源，使用独立 JAR/ClassLoader 加载。
`plugin-fixture` 仅用于验证，不能加入生产依赖。

注入 `org.flow.core.docs.PluginExportService`：`catalog()` 获取实际插件目录，
`export(destination, includeCore)` 导出 Markdown、JSON Schema、SVG。目标目录必须
尚不存在且父目录存在。离线聚合/配置 Schema 仍由 tooling 生成：

```bash
./gradlew :tooling:exportPluginSchemas :tooling:exportConfigurationSchema
```

## 验证

```bash
bash docs/harness/verify-flow.sh
bash docs/harness/verify-flow.sh --full-regression
```

根测试直接验证原生入口、数据源隔离、显式迁移、插件、执行和强杀恢复；报告在
`build/reports/tests/test/`。完整回归还执行所有原生模块测试，使用独立测试库和 schema。
当前源码仍有历史回归失败，具体状态必须结合本轮实际报告，不以旧报告代表通过。

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
