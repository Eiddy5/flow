# Kestra 源码来源与本地适配

- 上游：https://github.com/kestra-io/kestra
- 版本：v2.0.5
- 固定提交：172dd0f3760fb1255d3b22bd1fd07ce31b173fa3
- 下载：https://codeload.github.com/kestra-io/kestra/tar.gz/172dd0f3760fb1255d3b22bd1fd07ce31b173fa3
- 许可：本目录 `LICENSE`，保留上游版权说明。
- `upstream-manifest.json` 记录归档 SHA-256 和每个原始迁入文件的 SHA-256。
- `upstream-build/` 保存未改写的上游根构建文件，实际根构建排除上游发布、扫描上传及
  UI 打包流程。当前依赖管理按 ADR 0123 使用 TOML 目录，保留后端编译/测试配置。

仓库根目录直接采用 Kestra 原生后端模块架构，是唯一的源码二开构建，不依赖机器上的
另一个 Kestra checkout。最初迁入 22 个后端模块；第一轮按 ADR 0117 移除 CLI/Web/
MySQL，提取离线 `tooling`。当时保留 19 个原生模块及 embedded、plugin-fixture、
tooling，共 22 个注册模块。
第二轮按 ADR 0118 清理 core/script 中的产品功能及专用接口，模块数不变。
随后清除退出功能的源码残留、旧类型与关联依赖，范围见源码残留清理记录。
随后按 ADR 0119 删除 H2 及两个 memory 包装模块，数据库与测试统一 PostgreSQL；
当前模块清单见 `docs/project-structure.md`；原生 Java 入口按 ADR 0121 实施，工具不进入生产依赖。来源文件数不代表完整能力验收通过。

## 已修改的上游文件

| 文件 | 修改原因 |
| --- | --- |
| `core/src/main/java/org/flow/core/migration/MigrationStartupRunner.java` | 默认不自动迁移，仅 `flow.migration.auto=true` 时执行启动迁移 |
| `core/build.gradle` | 版本资源生成到 build 目录；删除 OSHI、生产 HTTP Server Netty 和仅供 Web 密码使用的 bcprov 直接依赖 |
| `jdbc/src/main/java/org/flow/jdbc/DatasourcePoolSizeDefaulter.java` | 仅为 flow 数据源补充队列所需的池默认值，保留显式设置及其它业务池 |
| `jdbc-postgres/src/main/java/org/flow/repository/postgres/migration/PostgresMigrationHistoryStore.java` | 原生历史存储直接绑定 flow 数据源，不再经过宿主替换工厂 |
| `tests/build.gradle` | 删除 Web 专用断言的编译依赖 |
| `tests/src/main/java/org/flow/core/utils/TestsUtils.java` | 删除指向已裁剪 CLI 类的 Javadoc 链接，方法行为不变 |

上述列出部分适配及其当前行为。第二轮还修改插件注册/生成器、Flow 校验、JDBC
普通查询与 Dashboard 查询的分离边界、Worker 配置下发、脚本基类及相关测试。
已删除 AI/MCP、匿名上报、Dashboard、官方插件目录/自动安装、管理端点 Basic Auth、
升级提示、内置 Docker 及对应专用兼容接口；原生 SQL 文件保持原始哈希，历史表暂留。
通用插件 SPI、手动 Maven/JAR 管理、TaskRunner、原生控制流和任务运行协议保留。

源码残留清理还移除 Web 日志广播、BasicAuth Java 密码迁移、Web/CLI DTO 与编辑器
服务、插件 UI manifest，以及不再使用的旧类型；日志 JDBC 持久化保留，gRPC 后续按 ADR 0120 退出。

原始清单不删除条目。`upstream-changes.json` 逐项登记退出文件、迁移目标和
原路径适配的当前哈希；以来源核对输出为当前数量。`verify-upstream.py` 同时核对原始清单和变更
记录，拒绝未登记缺失/变更，以及退出路径重新出现。
`.gitattributes` 对两个 Windows 脚本保留精确字节，避免 Git 换行转换破坏来源哈希；
提交前同时核对工作区和暂存内容重新检出的结果。

按 [ADR 0123](docs/decisions/0123-manage-dependencies-with-version-catalog.md) 移除独立
`platform` 模块，外部依赖坐标和版本集中到 `gradle/libs.versions.toml`。根构建直接
引用必要的 BOM 与传递依赖约束；Micronaut 按
[ADR 0126](docs/decisions/0126-use-internal-micronaut.md) 使用内部平台 `4.10.6.52`，
排除 PaaS/Redisson 导入，其他第三方库保持官方制品。原始构建快照保留，当前维护方式和验证证据见
[依赖管理指南](docs/harness/dependency-management.md)。

迁入 tooling 的三个来源文件：

- `PluginsSchemaCommand` → `PluginSchemaBundleGenerator`：去掉 CLI/容器装配，保留
  聚合算法；第二轮保留 Flow/Task/Trigger 三个根，任一根生成失败即中止。
- `ConfigurationSchemaGenerator` 与对应测试：仅改包名，算法与断言保持原样。

完整删除清单、原始哈希和迁移后哈希分别位于上述两个 JSON；第一轮恢复点与证据见
[第一轮记录](docs/harness/kestra-prune-round1.md)；第二轮恢复点与证据见
[第二轮记录](docs/harness/kestra-prune-round2.md)；后续源码清理见
[源码残留清理记录](docs/harness/kestra-prune-unused.md)；PG 收敛见
[PostgreSQL 清理记录](docs/harness/kestra-postgres-only.md)。

上游根 `lombok.config` 已按原始哈希迁入（共 3,508 个来源文件，包括原生 Wrapper 和编辑配置），特别保留构造器
`ConstructorProperties` 生成策略；WorkerTaskResult 等消息的反序列化依赖此配置。
根构建将其声明为 JavaCompile 输入，避免修改配置后旧字节码被误判为最新。

按 ADR 0121 移除 `embedded/`：数据源与迁移直接绑定原生 JDBC 实现，运行入口及插件导出归 core。
根 Java Library 聚合原生模块；`plugin-fixture/` 是验证用独立插件工程，不包含业务逻辑。
模型和 JDBC 消费行为保留；当前包名及配置由 ADR 0122 统一为 Flow 命名。已验证持锁消费者强杀后重领；Worker
运行中恢复、调度补偿和真实 CSES 集成仍按台账收口，不由专项成功推导整体完成。

## 构建

从仓库根目录运行固定上游 Gradle Wrapper 9.7.1：

```bash
./gradlew classes :plugin-fixture:jar
```

Java 版本由构建环境或标准 Gradle Java 配置选择，不再固定 toolchain 25；
兼容性边界见 [ADR 0125](docs/decisions/0125-use-build-selected-java-version.md)。
根 Java Library 的生产依赖不引入旧 Flow/Pulsar 或 embedded。
完整清单、实施次序与当前验证证据见
[迁移台账](docs/harness/kestra-core-migration.md)。

## JDBC Worker 通信替换

按 [ADR 0120](docs/decisions/0120-replace-worker-grpc-with-jdbc-queues.md) 删除
`worker-controller` 及 Worker gRPC 客户端、存储代理和专用协议。普通/SystemWorker
通过现有 JDBC 队列领取任务和回传结果，领取与归属、结果与完成标记分别原子提交。
保留执行、调度、插件 SPI 和 Task 导出，仍使用宿主 `datasources.flow`。本轮未加入
Pulsar、Route 或业务代码；历史回归记录只证明其记录时点。

## Flow 命名迁移

按 [ADR 0122](docs/decisions/0122-use-flow-namespace-and-configuration.md) 将当前源码、
资源、SPI、插件清单和测试迁入 `org.flow.*`，配置统一为 `flow.*`。原始清单和原始构建
快照不改写，迁移账本逐项关联原始路径与当前路径。历史 SQL 保留原文和 checksum。
旧 ADR、旧验收记录和上游来源 URL 保留其历史含义；当前使用方式见 Java 使用指南。

两个历史测试插件 JAR 的重定位脚本为 `docs/harness/relocate-plugin-fixtures.py`。
从固定版本原始归档解压后运行以下命令可复现：

```bash
python3 docs/harness/relocate-plugin-fixtures.py --source-root /path/to/original-source
```

脚本先校验原始 JAR 哈希，保留内含许可证和 Maven 来源信息，再迁移测试所需字节码、
SPI、Manifest、图标及文档资源。
脚本模块的 Kotlp 二进制和包装 API 已按用户确认移除。

## Micronaut 重试状态来源

`core/src/main/java/org/flow/core/retry/SimpleRetry.java` 来自
`io.micronaut:micronaut-retry:4.10.28:sources` 中的
`io/micronaut/retry/intercept/SimpleRetry.java`，原始 SHA-256 为
`d3b630968db66ca9162464e6745ba87976bec25f4829de3cf56352bfb4ca9857`，保留原 Apache-2.0 许可头。仅修改包名、删除包内私有
MutableRetryState 接口依赖及对应 Override 标记，算法不变；本地覆盖拦截器通过公开
RetryState 接口继续发布重试事件。该副本避免在框架包内放置项目代码。
