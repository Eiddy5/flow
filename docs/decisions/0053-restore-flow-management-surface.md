# ADR 0053：恢复正式 Flow 管理页面与 HTTP Controller

## 状态

Accepted（修订 ADR 0049 中关于删除 Flow 管理页面和 HTTP 入口的条款；
PostgreSQL-only 与移除 Memory 运行模式的条款继续有效）

## 背景

Flow 原有的页面不是演示数据或前端模拟器，而是用于真实 FlowDraft、Flow Reversion、
Execution 和 PAUSE Resume 操作的管理界面。此前重构时，页面、Controller 和固定
Session Binder 一并删除，导致当前仓库只剩 Core Service 和插件查询接口，真实用户无法
通过 Flow 自身管理流程。

## 备选方案

### 方案一：继续只提供 Core Service

可以保持 Server 很薄，但每个宿主都必须重复实现 Flow 管理页面和 HTTP 协议，无法满足
Flow 作为可直接使用的工作流产品入口的需求。

### 方案二：恢复 Demo 环境和固定本地 Session

可以快速复原旧页面，但会重新引入 Demo 开关、伪造租户身份和第二套运行配置，违背
PostgreSQL-only 以及宿主认证边界。

### 方案三：恢复正式管理页面和真实 HTTP Controller

页面作为 Server 静态资源发布，Controller 直接调用公开 FlowService、ExecutionService
和 YamlParser；每个请求通过宿主提供的 `@UserSession` 进入真实租户和审计上下文。

## 决策

采用方案三。

- 管理页面发布在 `/flow/index.html`，资源位于 `server/src/main/resources/flow/`。
- 真实 HTTP API 由 `org.cses.flow.controller.flow.FlowController` 提供，使用 `/api`
  路径下的 FlowDraft、部署、Execution、取消和 Resume 路由。
- Controller 不使用 `flow.demo.*` 或 Demo 开关；身份仍由
  `@UserSession Session<User>` 绑定，并由 Core Service 校验租户归属和审计信息。当前
  独立 Flow Server 的临时默认 `admin` 身份由 ADR 0054 定义，可配置关闭。
- 页面启动和运行操作继续调用当前 PostgreSQL-backed Core Service。普通 Execution 启动
  遵循 Dispatch Queue 的受理语义，不在 Controller 内直接推进状态。
- 插件目录继续复用 `/api/plugins`，页面的 Task 编辑器从真实插件注册表和 Schema
  读取可用 Task 定义。

## 理由

该页面承载的是 Flow 的真实用户操作，不应以 Demo 命名或绕过生产认证。把页面恢复在
Server，同时保留 PostgreSQL-only 和宿主 Session 边界，可以让独立 Flow Server 与嵌入
CSES 的运行方式共享同一套 HTTP 契约，而不会复活 Memory Repository；开发期固定身份的
临时边界和关闭要求由 ADR 0054 单独记录。

## 后果

- 独立运行时默认由 ADR 0054 提供 `admin` Session Binder，同时仍需要
  `datasources.flow.*`；接入真实认证前，该 binder 只能作为开发期临时身份。
- 部署页面的宿主必须允许 `/flow/**` 静态资源，并为 `/api/**` 提供正常认证会话。
- CSES 可以直接复用该 Controller 和页面，也可以只注入公开 Flow Service 实现自己的
  UI；Flow Core 不依赖页面代码。
- ADR 0049 中关于 Demo/Memory 运行模式、独立 Adapter 和 `flow.memory.enabled` 的
  删除决定不变。
