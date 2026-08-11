# CSES 嵌入完整 Flow Server 联调手册

## 运行形态

CSES 仍只依赖 Flow `server` 模块；Server 通过传递依赖带入完整 `core`。CSES
`Application` 是唯一组合根，Flow Server 的 Controller，以及 Core 的 Service、
Executor、Worker、插件和 PostgreSQL Adapter 进入同一个 Micronaut
ApplicationContext，并共享 CSES HTTP 端口。Flow 自身的 `Application` 不会因依赖
存在而再次启动。

Flow 独立启动使用 `flow-standalone` 环境读取应用名、端口和 Consul 配置；嵌入
CSES 时不激活该环境，因此不会覆盖 CSES 的全局应用配置。

## 数据库准备

Flow 不使用 Flyway。启动 CSES 前，先对 Flow 拥有的 PostgreSQL 数据库手工执行：

```bash
psql "$FLOW_POSTGRES_DSN" \
  -f ../flow/gen/sql/flow/001_create_flow_tables.sql
```

其中 `FLOW_POSTGRES_DSN` 使用 `psql` 可识别的 libpq 连接串；应用配置中的
`FLOW_DATASOURCE_URL` 使用 JDBC URL。

Flow 使用 Micronaut 标准的 `datasources.flow` 具名数据源；Micronaut 与 PAAS 自动
创建具名 `flow` DataSource、JOOQ Configuration 和 `org.x9.jooq.JOOQ`。CSES 的
YAML 或 Consul 最小配置为：

```yaml
datasources:
  flow:
    url: ${FLOW_DATASOURCE_URL}
    username: ${FLOW_DATASOURCE_USERNAME}
    password: ${FLOW_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver
    maximum-pool-size: 10
    minimum-idle: 2
```

CSES 不需要创建 Flow DataSource/JOOQ Bean。`jooq.datasources.flow` 只在需要显式
覆盖方言或 JOOQ Settings 时配置；连接池可以直接使用 Micronaut Hikari 支持的其他
属性。

Flow Repository 不会回退到 CSES 的 `default` 数据源。不使用 Flow 数据库时不要
声明 `datasources.flow`；一旦声明，URL 必须非空。未执行当前 SQL 基线时，数据库
装配或首次 Flow 请求会失败，应用不会自动创建或修复表结构。

## 本地 Composite Build

CSES 在 `feat-flow` 分支中声明 Maven 坐标：

```text
org.cses.flow:flow:0.1
```

本地联调把该坐标替换为相邻 Flow 仓库的 `:server` project：

```bash
cd ../cses
./gradlew :server:dependencyInsight \
  --dependency org.cses.flow:flow \
  --configuration runtimeClasspath \
  -PuseLocalFlow=true \
  -PlocalFlowPath=../flow
```

结果必须显示 composite build 的 `project :server` 替换，而不是 Maven Jar；其运行
依赖中还应包含本地 Flow `project :core`。CSES 不需要单独声明 Core。

编译组合应用：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
./gradlew :server:compileJava \
  -PuseLocalFlow=true \
  -PlocalFlowPath=../flow
```

准备数据库和平台配置后，使用相同开关启动 CSES：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
./gradlew :server:run \
  -PuseLocalFlow=true \
  -PlocalFlowPath=../flow
```

Flow Controller 此时监听 CSES 端口。CSES 业务模块可以直接构造注入
`FlowService`、`ExecutionService` 和 `PluginService`；不得自行组合 Flow Handler、
Repository 或 Executor。

## CSES Task 插件

CSES 新增 Task 插件时直接放在表达真实所有权的 package 中并标注 `@Plugin`：

```java
package org.cses.server.common.flow.plugins;

import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;

import java.util.Map;

@Plugin(
    title = "创建 CSES 任务",
    description = "在 CSES 中创建一条业务任务"
)
public final class CreateCsesTask extends Task implements RunnableTask {
    public CreateCsesTask() {
    }

    @Override
    public RunResult run(RunContext context) {
        // Host-owned Task code may use context.executionId() to associate
        // its business operation with the current Flow execution.
        return RunResult.success(Map.of());
    }
}
```

宿主编译必须继续让 Micronaut 为该类生成 BeanDefinition。无需修改 Flow 注册表、中心
枚举或 `switch`。启动组合应用后验证：

```text
GET /api/plugins
GET /api/plugins/org.cses.server.common.flow.plugins.CreateCsesTask
```

Flow 直接从插件类读取 `Class#getPackageName()`。上述 Task 在列表分组和单个 Task
元信息中的 `packageName` 都是 `org.cses.server.common.flow.plugins`，无需来源 Bean、
来源常量或中心注册。该机制只发现构建时已在 ApplicationContext 中的插件，不支持把
JAR 放入目录后动态加载或热卸载。

## 独立运行 Flow

无显式环境时，Flow `Application` 默认激活 `flow-standalone`：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :server:run
```

需要叠加环境时，将 `flow-standalone` 放在前面，使后续环境能够覆盖独立运行默认值：

```text
MICRONAUT_ENVIRONMENTS=flow-standalone,dev
```

生产依赖应使用普通 Maven JAR 和依赖元数据，不使用 `shadowJar` 作为 CSES 依赖。
