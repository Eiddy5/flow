# ADR 0030：拆分可嵌入 Flow Core 与可启动 Server

## 状态

Superseded by
[`ADR 0032`](0032-restore-single-server-runtime-module.md)

## 背景

Flow 当前只有 `gen` 和 `server` 两个 Gradle 模块。`server` 同时包含领域与运行时、
PostgreSQL Repository、JOOQ 事务边界、HTTP Controller、Micronaut 启动类和独立
部署配置。CSES 需要在同一进程中直接调用 Flow 的公开 Service，并为 Flow 提供一
个独立的 PostgreSQL 数据源；如果直接依赖现有 `server`，会同时引入 Flow 的启动
类、HTTP 入站接口和独立应用配置。

`gen` 同时保存 JOOQ Generator、生成类和生产数据库变更脚本。运行时需要生成类和
数据库脚本，但不应获得 Generator 或代码生成依赖。

## 备选方案

### 方案一：CSES 直接依赖 Server

改动最少，但会把可嵌入框架和可启动应用混为同一个模块，并可能造成 Application、
Controller、配置和 HTTP 运行时冲突。

### 方案二：继续拆分 Domain、Runtime、Postgres、Starter 和 Server

依赖方向最细，但当前只有一个 Micronaut/PostgreSQL 运行形态。过早建立多个浅模块
会增加发布坐标和装配接口，而没有带来实际可替换实现。

### 方案三：保留 Gen，将 Server 拆成 Core 与薄 Server

`core` 集中提供完整的嵌入式 Flow 能力，`server` 只作为该能力的一个 HTTP/独立
部署入口。发布时只暴露一个 Flow 坐标，并把 Gen 中运行必需的生成类和迁移资源合
入普通库 JAR。

## 决策

采用方案三。

### Gradle 模块

- `gen`：继续维护 PostgreSQL 生产变更脚本、JOOQ Generator 和生成代码；不作为
  外部运行时制品发布。
- `core`：包含 Flow Domain、Service、Command/Query、Executor、Worker、内置
  Extension、PostgreSQL Repository、Micronaut Bean 和数据库迁移接线。
- `server`：只包含 `Application`、HTTP Controller、静态资源、Session 入站适配
  和独立应用配置，并依赖 `core`。

Core、Executor 和 Worker 在 Java 包内继续保持 ADR 0012 确认的平级关系；本决策
只改变 Gradle 模块和部署边界，不合并既有 Java 包职责。

### 对外制品

`core` 使用 Maven 坐标：

```text
org.cses.flow:flow:<version>
```

发布的 `flow` 是普通 Java Library JAR。它包含：

- `org.cses.flow` 下的 Core 运行代码和 Micronaut Bean 元数据。
- `org.flow.gen.flow` 下的 JOOQ 生成类。
- `db/migration/flow` 下的 Flow 数据库迁移资源。

它不包含：

- `org.flow.builder.JOOQFlowGenerator`。
- JOOQ Codegen、Gradle 插件或其他构建工具实现。
- `server` 的 Application、Controller、静态资源和配置。
- Micronaut、JOOQ、PostgreSQL Driver 等第三方依赖的 class 文件；这些仍由正常
  Maven 元数据解析。

### 数据源和迁移

- 嵌入方必须配置名为 `flow` 的 Micronaut JDBC 数据源。
- Flow 的所有 `org.x9.jooq.JOOQ` 事务和查询入口只绑定该具名数据源，不回退到
  `default` 或宿主的其他数据源。
- Flow 在具名 DataSource 创建时同步运行自身迁移；迁移失败会阻止应用启动。
- 迁移只扫描 `classpath:db/migration/flow`，使用独立的 Flow Schema History，
  不扫描或执行宿主项目的迁移目录。
- JOOQ 生成代码继续以独立 Flow 数据库的 `public` schema 为固定契约。当前不支持
  在运行时把同一制品任意映射到其他 schema；需要共享物理数据库时必须先增加明确
  的 schema 映射决策。

### 外部接口

CSES 只声明一个依赖并继续通过 `FlowService`、`ExecutionService` 等公开 Service
使用 Flow。Controller、Handler、Repository、JOOQ 生成类型和迁移实现不是外部
调用接口。

## 理由

- 调用方只学习一个依赖坐标、一份 `datasources.flow` 配置和少量公开 Service，
  形成深模块接口。
- Flow 的事务、JOOQ、数据库迁移和生成代码保持在同一个发布版本中。
- Server 与 CSES 成为 Core 的两个平级消费者，避免启动和 HTTP 依赖反向进入核心。
- 只合并 Gen 的运行产物，既得到单一 Flow JAR，又避免 Fat JAR 的重复类和宿主依
  赖冲突。

## 后果

- 现有非 HTTP 生产代码和对应测试需要从 `server` 移入 `core`。
- 共享给 Core 测试和 Server HTTP 测试的内存 Adapter 使用 Gradle Test Fixtures，
  不进入生产 JAR。
- `gen` 的生产 SQL 需要在 Core 资源处理阶段转换为稳定的 Flyway 版本文件名；原始
  生产脚本仍是唯一编辑来源。
- 每次发布必须验证 Flow JAR 包含生成类和迁移资源、排除 Generator 与 Server 类，
  并在空 PostgreSQL 数据库上完成迁移和 Core 集成验证。
