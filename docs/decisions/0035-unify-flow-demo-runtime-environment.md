# ADR 0035：统一 Flow Demo 运行环境

## 状态

Superseded by [`ADR 0049`](0049-remove-demo-and-memory-runtime-modes.md)

## 背景

Flow 页面原先同时提供 `studio` 和 `demo` 两个 Micronaut 环境。两者都会注册同一份
静态页面、Demo Controller 和固定本地 Session Binder，但数据库装配不同：
`studio` 依赖平台提供具名 `flow` 数据源，`demo` 使用独立 PostgreSQL Adapter。

两个环境暴露相同页面，却要求不同配置键和启动命令。开发者容易只启用 `studio`
而缺少具名数据源，或清理数据库后误把装配错误判断为页面错误。页面能力与数据库
来源需要两个不同概念，但不需要两个页面环境。

## 备选方案

### 方案一：继续保留 `studio` 与 `demo`

改动最少，但重复页面和 Session 配置，调用方仍需理解两个名字的隐含差异。

### 方案二：`demo` 永远使用独立 PostgreSQL Adapter

启动接口最简单，但项目服务已经拥有平台具名数据源时无法复用现有事务和连接池，
也会让 Demo Adapter 覆盖正式装配。

### 方案三：统一 `demo` 环境并显式选择平台托管模式

页面只有一个环境；数据库仍在现有具名 `JOOQ` seam 上保留平台与独立两个真实
Adapter，并通过一个配置开关保证二者互斥。

## 决策

采用方案三。

- `/demo/**` 静态资源、`/api/demo` Controller 和固定本地 Session Binder 只由
  `demo` 环境启用。
- 删除 `application-studio.yml` 和 `flow.studio.*` 配置，统一使用
  `flow.demo.*` 与 `FLOW_DEMO_*`。
- `flow.demo.platform-managed` 默认为 `false`。该模式关闭 Consul 配置读取、服务
  注册和 Watch，注册 `DemoPostgresJooqAdapter`，并读取
  `flow.demo.postgres.*`。
- `flow.demo.platform-managed=true` 时启用平台配置，不注册 Demo PostgreSQL
  Adapter；调用方按 ADR 0040 提供 `datasources.flow.*`，框架自动完成
  `@Named("flow")` DataSource 与 JOOQ 装配。
- 两种模式都不允许 Flow Repository 或事务回退到 `default` 等宿主数据源。
- 两种模式都会替换正式 Session Binder，只允许在本地或受控开发环境启用。
- 本决策不改变数据库基线策略。独立模式连接空数据库前仍需执行
  `gen/sql/flow/001_create_flow_tables.sql`。

## 理由

`demo` 成为页面模块唯一的小接口，调用方只需学习环境名和一个模式开关。平台与
独立 PostgreSQL 是具名 `flow` JOOQ seam 上的两个真实 Adapter，保留差异是必要
的；通过条件装配保证同一进程只选择其中一个，可以避免 Bean 覆盖和隐式回退。

## 后果

- 原 `MICRONAUT_ENVIRONMENTS=studio` 启动方式失效，必须迁移为 `demo`。
- 原 `FLOW_STUDIO_*` 身份变量失效，统一迁移为 `FLOW_DEMO_*`。
- 平台托管方式需要额外设置 `FLOW_DEMO_PLATFORM_MANAGED=true`。
- 独立模式继续使用短连接 PostgreSQL Adapter，不自动创建或升级 Schema。
- `ErrorResponse` 的 Micronaut Serde 元数据问题属于 HTTP 异常协议，需单独修复，
  不由本运行环境迁移处理。
