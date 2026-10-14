# ADR 0121：直接通过项目原生模块使用流程引擎

## 状态

Accepted（2026-10-13）。用户确认本项目自身就是可用的流程引擎，移除 `embedded`，
继续在同一应用中通过 Java 调用。修订 ADR 0115/0116 中的宿主适配模块定位。

## 背景

Kestra 源码已经组成根项目，但数据源、迁移、插件和生命周期仍由 `embedded` 中的
替换工厂提供，使本项目继续表现为包装另一个引擎。用户要求直接在现有模块上开发和使用。

## 备选方案

1. 保留或重命名 `embedded`：仍有额外适配层，不采用。
2. 将职责归回原生模块，根工程聚合可直接使用的 Java 依赖：采用。
3. 恢复独立服务器、CLI 或 HTTP 接口：用户确认使用同一应用内 Java 调用，本轮不引入。

## 决策

- 删除 `embedded` 的构建注册、适配工厂和生产目录。根 `flow` 作为 Java Library
  聚合已有运行模块，根工程自身不承载另一套引擎实现。跨模块技术验证归根 `src/test`。
- `core` 提供 `FlowEngine` 生命周期入口，直接使用原生 Executor、Worker、Scheduler、
  Indexer 与 SystemWorker 契约；SystemWorker 契约与其它运行角色同置于 core，实现在 worker。
  调用者使用同一 ApplicationContext 注入 FlowEngine、FlowService、队列和 Repository。
- JDBC 事务、日志默认库、迁移历史、迁移锁和迁移脚本直接绑定 `datasources.flow`。
  不使用 `@Replaces` 工厂重新包裹这些实现，也不回退 default。显式配置的连接参数、
  schema 与连接池大小由调用应用管理；池默认值只作用于 flow 数据源。
- 迁移默认显式调用；原生 `kestra.migration.auto: true` 可开启自动迁移，测试环境明确
  开启。引擎 start 不执行 DDL。上游独立日志扩展能力与现有测试不在本次裁剪范围。
- 插件目录配置归 `kestra.plugins.path`，由原生 Registry 工厂在返回注册表前加载。
  Task 目录及 Markdown/Schema/图标导出归 core 的插件文档服务，离线聚合工具保留在 tooling。
- 引擎 start/close 只管理原生运行角色，不创建或关闭调用应用的 Context、连接池。
  `KestraContext.shutdown()` 直接请求本项目 FlowEngine 停止，不再使用宿主替换工厂；
  不使用 FlowEngine 的原生角色 Context 保留原有关闭路径。未捕获异常处理器不再退出
  整个 JVM，进程生命周期由调用应用管理。
- 保留 `io.kestra` 包及模型、插件、队列、SQL 协议。移除 `flow.kestra.*` 嵌入式开关，
  启动参数使用 `kestra.engine.*`。不改造业务 UC、不加入 Route 或 CSES 业务。

## 理由

数据源和插件装配发生在拥有它们的模块中，生命周期入口只协调原生运行角色，核心
模块不反向依赖具体执行模块。根依赖聚合使调用方能够直接使用本项目，同时保留源码
模块的职责与真实插件 JAR 验证。

## 后果与验证

`embedded` 坐标、类及配置退出，调用方转用本项目根依赖和原生 Java 入口。已有数据库
及上游迁移 SQL 不改动。通过真实 PG 验证具名数据源隔离、显式迁移、流程执行、调度、
输出、独立插件、日志、停止所有权与强杀恢复；运行完整回归和来源校验。独立服务器、
新 API、旧业务 UC 兼容和真实 CSES 工程切换不属于本次完成声明。
