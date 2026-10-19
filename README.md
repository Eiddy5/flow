# Flow

本项目提供可直接通过 Java 使用的流程引擎，统一采用 `org.flow.*` 包和 `flow.*` 配置。
通过同一应用中的 Java 调用直接使用原生模型、服务、执行器、Worker、PostgreSQL
队列、调度、存储和插件能力。根 Java Library 聚合这些运行模块；职责和公共入口
位于原生模块内，决策见 [ADR 0121](docs/decisions/0121-use-native-modules-directly.md) 和
[ADR 0122](docs/decisions/0122-use-flow-namespace-and-configuration.md)。

## 使用

调用应用依赖根 `flow` Java Library，在自己的 Micronaut ApplicationContext 中配置
`datasources.flow`。显式准备数据库后，注入 `org.flow.core.runners.FlowEngine` 并调用
`start()`；通过接收宿主 Session 的 `FlowService` 创建流程、`ExecutionService`
创建和查询执行、`PluginService` 查询注册插件。三个服务统一位于
`org.flow.core.services`，由原有 Service 直接接收 Session 并实现业务。业务调用
统一从 Session 提取租户和操作者。

完整配置与 Java 示例见 [直接使用指南](docs/harness/java-engine.md)。根制品负责依赖
聚合，需连同依赖使用；单独复制根 JAR 不会包含所有模块。目前没有发布新 Maven 制品。

Micronaut 按 main 的内部仓库与 BOM 方式接入，依赖统一由 TOML 管理，各模块继续
按实际需要声明框架组件。数据库对象由 gen 使用 CSES 同款内部 jOOQ 生成器和
`JooqPojo` 生成，数据库存储直接使用生成表及字段；保留生成对象和内部 Bean 装配必要的 PaaS/Pulsar 依赖；Flow 队列仍
使用 JDBC，内部 DB 事件与消费者默认关闭，宿主显式配置可覆盖。生产依赖排除
HTTP Server Netty 和生成工具。调用工程需自行
配置内部仓库，框架规则见 [依赖管理](docs/harness/dependency-management.md#内部-micronaut-与消费方仓库)，
jOOQ/PaaS 依赖边界和生成命令见 [生成指南](docs/harness/jooq-generation.md)。

## 构建与验证

使用固定 Gradle Wrapper 9.7.1。项目不固定 Java 版本：编译、测试及工具执行遵循
所属项目的标准 Gradle Java 配置，未配置 toolchain 时使用 Gradle 运行的 JDK。
当前使用 Java 25 联合构建本地 PaaS `pass-2.0-neworg`，默认源码路径为 `../paas`，
可通过 `-PpaasSourcePath` 或 `FLOW_PAAS_SOURCE_PATH` 指定；见 [ADR 0133](docs/decisions/0133-session-aware-public-services.md)。同一多模块
构建可由宿主统一配置 toolchain。独立构建和已发布 JAR 的边界见
[Java 版本选择](docs/harness/java-engine.md#java-版本选择)。

```bash
./gradlew classes jar verifyRuntimeIsolation
./gradlew :tooling:exportPluginSchemas -PschemaPlugins=/absolute/path/plugins
python3 verify-upstream.py
bash docs/harness/verify-flow.sh
bash docs/harness/verify-flow.sh --full-regression
```

普通编译使用已提交的数据库生成对象，不连接数据库；结构变更后显式运行
`bash docs/harness/generate-jooq.sh`，在临时 PostgreSQL 中重新生成并审查差异。

验证脚本通过 Docker 创建并清理一次性 PostgreSQL，隔离 flow、对照库、上游测试库
和独立日志测试库。根 `src/test` 验证直接 Java 使用及跨模块技术链路；模块自身测试
仍在各自 `src/test`。Schema 工具、共享测试库和验证插件不进入生产运行依赖。

- [项目目录和依赖方向](docs/project-structure.md)
- [TOML 依赖管理与版本比较](docs/harness/dependency-management.md)
- [原生 Java 使用与配置](docs/harness/java-engine.md)
- [完整核心迁移台账及历史证据](docs/harness/kestra-core-migration.md)
- [源码来源与适配记录](UPSTREAM.md)
- [旧项目完整备份与恢复](docs/harness/kestra-source-backup.md)

当前仍未加入 Route 与 CSES 业务适配，真实 CSES 工程和业务数据库尚未切换。
