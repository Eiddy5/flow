# ADR 0039：将完整 Flow Server 嵌入 CSES

## 状态

Accepted（数据源 YAML 配置接口由
[`ADR 0040`](0040-bind-flow-datasource-from-host-yaml.md) 修订；Core 与 HTTP Server
的 Gradle 模块形态由 [`ADR 0042`](0042-split-core-from-http-server.md) 修订）

本决策修订 ADR 0032 中“只有独立 Flow Server 一种交付形态”和“外部项目不能依赖
Server 制品”的条款，修订 ADR 0033 中由 Flyway 在启动时执行 Flow 建表基线的
条款，并把 ADR 0026 的项目内插件发现范围扩展到同一宿主 ApplicationContext 中
由 Micronaut 编译期发现的插件。其余模块、领域、插件类型和数据库基线决策继续
有效。

## 背景

CSES 已建立 `feat-flow` 分支，需要在同一进程中直接使用 Flow 的 Controller、公开
Service、Executor、Worker、插件扩展和 PostgreSQL 实现，并继续在 CSES 项目中
开发基于 Flow 的业务模块。调用方需要的是完整 Flow Server 能力，而不是只包含
领域接口的纯 Library。

Flow 同时仍需保留独立启动能力。若 CSES 直接消费当前 Server，两个制品中的基础
`application.yml` 和 `bootstrap.yaml` 会竞争应用名、端口与 Consul 配置；数据库
自动迁移也不符合由部署人员手工执行 SQL 的目标契约。

## 备选方案

### 方案一：CSES 通过 HTTP 调用独立 Flow 进程

部署隔离清晰，但 CSES 无法在同一 ApplicationContext 中直接调用 Flow Service 或
贡献编译期插件，不符合当前嵌入目标。

### 方案二：拆出纯 Flow Runtime Library

可以隔离启动配置，但会把当前完整 Server 拆成新的 Gradle 制品，并迫使 CSES 在
多个制品间重新装配 Flow 已经拥有的业务能力。

### 方案三：同一个完整 Server 制品支持独立与嵌入两种启动方式

`server` 继续保存全部 Flow 生产能力。独立运行由 Flow `Application` 启动；嵌入
运行由 CSES `Application` 启动同一个 Micronaut ApplicationContext。两种方式只在
组合根和全局配置所有权上不同。

## 决策

采用方案三。

### 完整 Server 制品

- `server` 仍是 Flow 唯一 Gradle 运行模块，不拆分 Core、Web 或 Runtime 制品。
- CSES 通过模块坐标 `org.cses.flow:flow:<version>` 消费 Server 的普通 Java JAR；
  本地源码联调使用 Gradle Composite Build，把该坐标替换为 Flow 的 `:server`。
- 依赖的是普通 JAR 及其 Gradle/Maven 依赖元数据，不使用 `shadowJar` 作为库依赖。
- Server 中的 Controller、Core Service、Executor、Worker、内置插件、Repository、
  静态资源和 Micronaut Bean 元数据全部保留。

### 两种启动方式

- 独立运行：Flow `Application` 是组合根，并默认激活 `flow-standalone` 环境。
- 嵌入运行：CSES `Application` 是唯一组合根；Flow `Application` 只作为普通类存在，
  不会启动第二个 ApplicationContext 或第二个 Netty 端口。
- 嵌入时 Flow Controller 与 CSES Controller 共享 CSES 的 HTTP Server、应用身份、
  认证会话和平台配置。
- Flow 的应用名、端口、Consul 和独立运行开关只放在
  `application-flow-standalone.yml` 与 `bootstrap-flow-standalone.yaml`；基础
  `application.yml` 只保存不会改变宿主全局行为的 `flow.*` 配置。

### 数据库与手工建表

- Flow Repository 和事务继续只使用 `@Named("flow")` 的 DataSource、JOOQ 和
  PostgreSQL；不得回退到 CSES 的 `default` 数据源。
- CSES 只提供标准 `datasources.flow.*` 连接属性；Micronaut 和 PAAS 按 ADR 0040
  自动创建具名 DataSource、JOOQ Configuration 和 `org.x9.jooq.JOOQ`。
- `gen/sql/flow/001_create_flow_tables.sql` 是唯一完整 Schema 入口，由开发或部署
  人员在启动应用前手工执行；入口与表级脚本布局由 ADR 0048 定义。
- Flow Server 不依赖 Flyway，不复制 `db/migration/flow` 资源，不创建 Schema
  History 表，也不在启动时创建、修改或删除数据库对象。
- 提供了 Flow 数据源但未执行当前基线时，数据库装配或首次业务访问明确失败；应用
  不尝试自动修复旧 Schema。

### CSES 扩展

- CSES 可以直接注入 Flow 的 `FlowService`、`ExecutionService`、`PluginService`
  等公开入口，不越过这些入口自行组合 Flow Handler、Repository 或 Executor。
- CSES 源码中直接标注 `@Plugin`、并由宿主 Micronaut 编译期处理产生 BeanDefinition
  的 Task，可以进入同一个 `DefaultPluginRegistry`，不需要额外注册来源。
- `DefaultPluginRegistry` 直接使用具体插件类的 `Class#getPackageName()` 构造目录；
  `GET /api/plugins` 的分组和每个 Task 元信息都返回真实 `packageName`，Flow Studio
  同样显示该路径，以区分 Flow 自带和 CSES 扩展。
- 该扩展只支持构建时已在宿主 classpath 中的插件，不恢复 ServiceLoader、插件目录、
  动态安装、独立 ClassLoader 或热卸载。

## 理由

- CSES 学习一个依赖坐标和一个具名数据库契约，就能获得完整 Flow 行为。
- Flow 独立与嵌入运行复用同一份实现，修复和业务演进保持局部性。
- 只隔离组合根配置，不把完整 Server 拆成多个浅模块。
- Schema 变更保持显式、可审查，不让宿主应用启动产生隐式数据库写操作。

## 后果

- 嵌入 CSES 后，Flow HTTP 接口监听 CSES 端口；需要独立端口时必须独立部署 Flow。
- CSES 必须在启动前提供具名 `flow` 数据源并执行当前 SQL 基线。
- CSES 与 Flow 必须对齐 Java、Micronaut、JOOQ、DataPilot 等共享依赖版本，并通过
  组合应用启动测试验证 Bean、路由和配置没有冲突。
- 插件类的 canonical class name 仍是定义和持久化类型；CSES 插件换包或改名属于
  显式兼容性变更。
