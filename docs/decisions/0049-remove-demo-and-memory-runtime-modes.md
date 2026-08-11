# ADR 0049：移除 Demo 与 Memory 运行时模式

## 状态

Accepted（supersedes [`ADR 0035`](0035-unify-flow-demo-runtime-environment.md)；
标准具名数据源契约仍由 [`ADR 0040`](0040-bind-flow-datasource-from-host-yaml.md)
定义）

## 背景

Flow 曾同时包含面向页面演示的 `demo` 环境和无数据库的 `memory` 运行模式。两者
通过条件 Bean、独立配置前缀和测试夹具切换 Repository 与 JOOQ 边界，导致同一套
Core 用例存在多套持久化语义，也使独立启动方式与正式嵌入方式的配置不一致。

当前 Flow 的正式运行边界已经确定为标准具名 `flow` DataSource、JOOQ 和
PostgreSQL Repository。Demo 页面、固定 Session、直连 PostgreSQL Adapter 以及
Memory Repository 不再承担正式产品职责。

## 备选方案

### 方案一：继续保留 Demo 与 Memory 开关

可以继续支持无数据库开发和内置页面，但需要维护条件装配、两套事务语义以及额外的
环境变量，正式路径的配置和验证仍然容易被旁路。

### 方案二：只隐藏页面，保留 Demo/Memory 基础设施

表面上减少 HTTP 暴露，但无用的 Adapter、配置键和 Bean 仍会进入类路径，后续仍可能
被错误启用或覆盖正式 `flow` JOOQ。

### 方案三：生产 PostgreSQL-only

删除 Demo HTTP/静态资源和独立 Adapter，移除 `flow.memory.enabled` 条件装配；所有
生产 Repository 始终由标准 `@Named("flow")` JOOQ 驱动。需要快速单元测试的替身只
存在于测试类内部，不作为 Micronaut 运行时模式。

## 决策

采用方案三。

- 删除 `flow.demo.*`、`FLOW_DEMO_*`、`flow.memory.enabled` 以及对应的
  `application-demo.yml`、`bootstrap-demo.yml` 配置。
- 删除 Demo Controller、固定 Session Binder、页面静态资源、Demo 直连 PostgreSQL
  JOOQ Adapter 及其专属测试和运行手册。
- 四个正式 PostgreSQL Repository 不再使用 `@Requires` 读取 Memory 开关；它们在
  Core 装配中始终实现对应 Repository 端口。
- 独立和嵌入运行都使用 `datasources.flow.*`。JOOQ 继续由 Micronaut/PAAS 根据
  标准具名 DataSource 自动注册，Flow 不创建第二套 Factory。
- 需要数据库语义的集成测试统一通过 PostgreSQL 测试适配器启动；少量纯 Executor
  单元测试使用测试类内部的最小替身。

## 理由

生产 PostgreSQL-only 使 Core 的事务、锁、版本和 JSONB 持久化语义只有一个来源，
并把数据库配置收敛到 ADR 0040 的稳定边界。删除 Demo 和 Memory 的条件 Bean 后，
无效配置不会再改变 Repository/JOOQ 装配，独立运行和 CSES 嵌入运行也共享同一套
数据库契约。

## 后果

- 原有 `MICRONAUT_ENVIRONMENTS=demo`、`FLOW_DEMO_*` 和 `flow.memory.enabled`
  启动方式失效，调用方必须提供有效的 `datasources.flow.*` 并预先执行 Flow SQL
  基线。
- Flow 不再内置可直接访问的 Demo 页面；需要页面或产品级 API 时由宿主应用提供
  HTTP 入口并注入 Flow Service。
- 没有 PostgreSQL 的测试不能通过启动 Memory ApplicationContext 绕过数据库；应改
  为纯单元测试或 PostgreSQL 集成测试。
- ADR 0035 保留为历史决策记录，但其 Demo 运行环境方案由本 ADR 取代。
