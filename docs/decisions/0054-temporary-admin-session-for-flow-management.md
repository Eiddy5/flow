# ADR 0054：为 Flow 管理页面提供临时固定 admin 身份

## 状态

Retired（2026-09-14：按用户要求清理默认登录用户配置）

## 废止说明

已删除固定 `admin` 身份 Binder、启用开关及对应测试，同时移除未被现有测试使用的
临时 Session Binder。Controller 继续使用 `@UserSession`，由 PAAS／宿主认证提供
真实会话；独立部署也必须配置有效认证，不再自动生成管理员身份。

以下内容仅保留原决策的历史背景，不再代表当前运行配置。

## 背景

正式 Flow 管理页面已经恢复为真实 HTTP Controller，但当前独立 Flow Server 尚未接入
宿主的登录和 Session Binder。若没有 `@UserSession`，页面无法调用真实的 Flow Core
写接口，也无法展示当前操作身份。现阶段需要一个明确、可测试、可关闭的默认身份来
启动管理页面联调。

## 备选方案

### 方案一：恢复 Demo Controller 或 Memory 运行时

会重新引入已删除的 Demo/Memory 边界，导致页面和真实 PostgreSQL Flow 行为不一致。

### 方案二：让页面绕过 `@UserSession` 直接传 userId

会把认证上下文泄漏到页面协议，并绕过 Controller 与 Core Service 既有的 Session 合同。

### 方案三：在正式 `@UserSession` binder 上提供临时固定身份

保留真实 Controller、Core Service、租户过滤和审计链路，同时把身份替换限制在一个
明确的配置开关内。

## 决策

采用方案三。

- `server` 默认启用 `flow.management.admin-session.enabled=true`。
- `AdminSessionArgumentBinder` 替换 PAAS 默认 `SessionArgumentBinder`，为所有
  `@UserSession Session<User>` 请求创建固定身份：`companyId=admin`、`userId=admin`、
  `userName=admin`，角色为 `OrganizeType.Admin`，设备为 WebBrowser。
- 该 binder 只属于 HTTP 入站适配，不创建 Demo 路由、不引入 Memory Repository，也不
  改变 PostgreSQL Core Service 的租户和审计逻辑。
- 宿主接入真实认证时设置
  `flow.management.admin-session.enabled=false`，由宿主提供的 Session Binder 接管。

## 理由

固定身份通过正式的 `@UserSession` 合同进入 Controller，页面不会分叉出另一套 Demo
协议；配置开关让临时身份的边界可见且可验证。将 company、user 和管理员角色统一为
`admin`，可以在当前没有登录页面的情况下稳定地建立 Flow 管理数据的归属上下文。

## 后果

- 开关开启期间，任何能访问管理 API 的请求都拥有 `admin` 管理员权限，不提供真实的
  登录认证、用户隔离或租户隔离。
- 所有写入的创建者、更新者和租户归属都会记录为 `admin`，因此该配置不适用于生产环境。
- 关闭开关后，独立服务必须提供有效的宿主 `SessionArgumentBinder`；否则带有
  `@UserSession` 的接口将无法绑定会话。
- 该方案只影响 HTTP 身份注入，不改变 Flow Core、PostgreSQL 适配器或 Dispatch Queue
  的运行语义。
