# ADR 0032：恢复单一 Server 运行模块

## 状态

Superseded by
[`ADR 0042`](0042-split-core-from-http-server.md)（数据库基线策略由 ADR 0033
修订；完整 Server 嵌入 CSES、独立运行配置和人工建表契约由
[`ADR 0039`](0039-embed-complete-flow-server-in-cses.md) 修订）

## 背景

ADR 0030 曾把可嵌入 Flow Library 与可启动 Server 拆为两个 Gradle 模块。后续
Task 插件重构已经确认了具体 Task 即 Plugin、`OrchestrationTask`、编排扩展分包
以及 Executor/Worker 的 Java 包边界；这些边界并不要求建立独立的 `core` Gradle
模块。

当前项目仍以独立 Flow Server 为交付形态。继续维护 `core -> server` 的制品边界
会额外引入发布配置、Test Fixtures、跨模块依赖和两套构建验证，却没有当前调用方
要求独立 Flow Library。因此需要恢复原有的单一运行模块，同时保留已经完成的插件
与运行时重构。

## 备选方案

### 方案一：保留 Core 与薄 Server

继续执行 ADR 0030，保留可嵌入制品和独立发布验证。它适用于已经存在稳定外部
Library 消费方的情况，但不符合当前项目交付边界。

### 方案二：继续拆分更多运行模块

把 Domain、Executor、Worker、PostgreSQL Adapter 和 Server 分别建成 Gradle 模块。
这种方式会进一步扩大构建和发布复杂度，当前没有对应的独立部署或替换需求。

### 方案三：恢复 Gen 与 Server 两个 Gradle 模块

`gen` 继续维护数据库脚本和 JOOQ 生成代码；`server` 承载全部 Flow 生产代码、
测试、资源和可启动应用。Java 包仍用于表达 Core、Executor、Worker、Extension 与
Infrastructure 的职责边界。

## 决策

采用方案三。

### Gradle 模块

- `gen`：保存 PostgreSQL 迁移脚本、JOOQ Generator 和生成代码。
- `server`：唯一运行模块，包含 `Application`、Controller、Flow Core、Executor、
  Worker、内置扩展、PostgreSQL Repository、DataPilot/Session Adapter 和资源。
- 不再注册或发布独立的 `core` Gradle 模块。

`org.cses.flow.core` 继续作为 Java 领域与用例包存在；它不是 Gradle 模块。ADR 0012
确认的 `org.cses.flow.core`、`org.cses.flow.executor` 与
`org.cses.flow.worker` 平级包关系保持不变。

### 构建与资源

- `server` 直接依赖 `gen`，编译时使用 `org.flow.gen.flow` 生成类型。
- `gen/sql/flow/001_create_flow_tables.sql` 作为人工执行的完整数据库基线；Server
  不再把它复制为运行资源，也不在启动时迁移数据库，具体以 ADR 0039 为准。
- 不再生成或验证 `org.cses.flow:flow` Library、独立 Core POM 或 Core Sources JAR。
- 所有测试统一位于 `server/src/test/java`；测试 Adapter 不进入生产源码。

### 插件与编排结构

模块合并不回退 Task 插件重构：

- 具体 Task 仍直接作为 `@Plugin` Bean，以 canonical class name 注册。
- `Pause`、`Parallel` 和后续 Flow 编排 Task 仍位于 `extensions/flow`。
- `Log` 仍位于 `extensions/log`，`AutomaticTask` 暂时位于 `extensions/tasks`。
- 不恢复 `TaskExtension`、`TaskTypeDispatcher`、伴生 `*TaskPlugin` 或 `BranchTask`。

## 理由

- 当前只有一个可启动、可交付的运行形态，单一 `server` 模块与实际边界一致。
- Java 包已经能够保护领域、Executor、Worker 和扩展之间的职责，不需要用额外
  Gradle 制品重复表达。
- 插件重构与 Gradle 模块拆分解耦，恢复模块不会损失已经确认的编排语义。
- 测试与生产代码位于同一模块后，移除 Test Fixtures 和跨模块测试依赖，构建入口
  更直接。

## 后果

- 原 `core/src/main/java`、`core/src/test/java` 和 `core/src/testFixtures/java` 内容
  分别并入 `server/src/main/java` 与 `server/src/test/java`。
- CSES 作为已确认调用方，可以按 ADR 0039 依赖完整 Server 普通 JAR；仍不发布
  独立 Core Library。
- `server` 的依赖集合包含 Flow Core、插件 Schema、HTTP 和数据库访问所需的完整
  依赖，但不包含 Flow Flyway 迁移。
- 架构测试继续保护 Java 包边界以及旧插件机制不得恢复。
