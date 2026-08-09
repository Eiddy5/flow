# ADR 0042：浅拆分 Core 与 HTTP Server

## 状态

Accepted

本决策修订 [`ADR 0032`](0032-restore-single-server-runtime-module.md) 的单一
Server Gradle 模块条款，以及 [`ADR 0039`](0039-embed-complete-flow-server-in-cses.md)
中由同一个 Server 模块直接保存全部 Flow 实现的条款。人工数据库基线、CSES
同进程嵌入、编译期插件和 `datasources.flow` 配置契约保持不变。

## 背景

Flow 的 `server` 同时保存 HTTP 入站、独立启动组合根、领域用例、Executor、
Worker、插件扩展和 PostgreSQL 实现。CSES 需要继续通过一个完整 Flow 依赖获得
HTTP 路由和可直接注入的公开 Service，但 `server` 的源码职责应只表达 HTTP 服务。

当前非 HTTP 代码之间已经形成完整协作关系。继续拆分 Domain、Executor、Worker、
Extension 或 PostgreSQL 会引入新的制品接口和循环依赖调整，不属于本次浅拆分。

## 备选方案

### 方案一：继续保留单一 Server 模块

不需要移动源码，但 `server` 名称继续同时表达 HTTP Adapter 和完整 Flow 实现，
无法从 Gradle 模块上识别 HTTP seam。

### 方案二：新增 Runtime 并继续细分运行模块

可以建立更多技术制品，但会改变当前包协作、发布关系和测试装配，超出只拆 HTTP
Server 的目标。

### 方案三：恢复 Core，加一个薄 Server

把全部非 HTTP 生产能力原样迁入 `core`，`server` 只保存 HTTP、启动和资源，并
通过一个项目依赖重新组合完整 Flow。

## 决策

采用方案三。

### Gradle 模块

- `gen`：继续保存 PostgreSQL 完整基线、JOOQ Generator 和生成代码。
- `core`：保存现有 `org.cses.flow.core`、`executor`、`worker`、`extensions`，以及
  除 HTTP Session Binder 外的 `infrastructure`。Java 包名和包职责保持不变。
- `server`：只保存 `Application`、`controller`、HTTP DTO、Session Binder、静态
  资源和独立启动配置。
- 不新增 `runtime`、`api`、`postgres`、`starter` 或其他 Gradle 模块。

Gradle `core` 是完整非 HTTP Flow 能力的制品容器，不表示其中的 Executor、Worker、
Extension 或 PostgreSQL 实现变成 `org.cses.flow.core` Java 包的内部类型。

### 依赖和嵌入

- `core` 继续使用 `gen` 中的 JOOQ 生成类型。
- `server` 使用 `api(project(':core'))`，让消费 Server 的 CSES 同时获得公开 Flow
  Service、Domain 和 Plugin 类型的编译可见性。
- CSES 继续消费 `org.cses.flow:flow:<version>`，本地 Composite Build 继续把该
  坐标替换为 `:server`；CSES 不需要分别装配 Core 与 Server。
- CSES 与独立 Flow 启动仍各自只有一个 Micronaut ApplicationContext 和一个 HTTP
  Server。Core 的 Micronaut BeanDefinition 通过普通项目依赖进入该 Context。

### 配置和数据库

- 具名 DataSource/JOOQ 的消费边界、Repository 和 DataPilot 接线归 `core`；
  DataSource/JOOQ 的创建和生命周期由已有 Micronaut/PAAS Factory 管理。
- 宿主仍只配置 `datasources.flow.*`；没有新的 Java Configuration。
- `gen/sql/flow/001_create_flow_tables.sql` 仍由开发或部署人员手工执行。
- Core 和 Server 都不依赖 Flyway，也不在启动时修改 Schema。

### 测试

- Core、Executor、Worker、Extension、Repository 和数据库装配测试迁入
  `core/src/test/java`。
- HTTP Controller、Session Binder 和启动装配测试留在 `server/src/test/java`。
- 两个模块共享的内存 Repository、无连接 JOOQ 和测试 Plugin 放入 Core Test
  Fixtures，不进入生产制品。
- 架构测试阻止 HTTP 源码进入 Core，也阻止非 HTTP 生产源码回到 Server。

## 理由

- 只建立一个与 HTTP seam 对应的浅拆分，不改动已经稳定的业务实现和 Java 包关系。
- Server 作为 HTTP Adapter 可以保持很薄；完整 Flow 行为仍集中在一个 Core
  implementation 中。
- `api(project(':core'))` 保持 CSES 只学习一个依赖坐标，同时保留直接调用公开
  Service 和贡献编译期 Plugin 的能力。
- 数据源、事务、Repository 和执行引擎一起迁移，避免跨模块事务装配和反向依赖。

## 后果

- 构建入口变为 `gen + core + server`，Core 与 Server 分别拥有自己的测试任务。
- Server 普通 JAR 不再直接包含 Core class；发布 Server 时必须同时发布并声明
  Core 的传递依赖。
- CSES 的 Flow 依赖、YAML 和数据库准备方式不变。
- 后续只有出现真实的独立部署或替换需求时，才重新评估更细的 Gradle 模块。
