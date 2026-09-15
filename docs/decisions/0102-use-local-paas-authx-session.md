# ADR 0102：本地 PAAS 联合构建与 AuthX 会话接入

## 状态

Accepted。

## 背景

Flow 需要与本地 `paas`、`paas-service`、`pass-auth` 同步联调。
AuthX 提供 `AuthxSession extends Session<AuthxUser>`，其中 `AuthxUser` 继承
PAAS 的 `SessionUser`，不继承旧 `User`。AuthX 的公司上下文属于 Session；
用户绑定后会清空旧用户公司字段。因此旧 `Session<User>` 上界、用户强转和
`session.user.companyId` 相等校验不能用于新认证。

## 选项

1. 每次发布三个仓库后再更新依赖：联调反馈慢，容易解析到旧包。
2. 本地联合构建并保留旧 User 转换：丢失 AuthX 类型与身份语义。
3. 本地联合构建并迁移到 SessionUser 基础契约：采用。

## 决策与理由

- Flow 使用与 CSES 一致的三个本地开关和可覆盖路径。启用时将路径规范化，
  向嵌套构建传递选择；目录不存在即失败。关闭时使用 Maven 坐标。
- Server 引入 `org.pass:pass-authx`，Controller 使用 `@UserSession AuthxSession`，
  租户管理入口要求已选择公司。Core 不依赖 AuthX，用户上界统一为 `SessionUser`；
  不需要保留具体用户类型的领域入口接收 `Session<? extends SessionUser>`。
- SessionUtil 从可信 Session 读取公司，从 SessionUser 读取全局用户身份。
  不再把用户上的旧公司字段作为租户成员证明；资源查询和修改继续校验租户。
- 两个 Executor 消费者复用 SessionUtil.restore，通过当前 SessionFactory 恢复
  一致的会话和用户类型，不使用原始类型或旧 User 强转。恢复的是队列消息需要的
  最小上下文，不是登录凭证、公司成员关系或权限授权结果。
- Server 保留 AuthX 所需的 PAAS Temporal、响应包装及其诊断依赖。Flow Controller 使用
  `@SkipResponseWrap` 保持既有 HTTP 响应结构。

## 后果与边界

- 构建使用 JDK 25；本地联调命令和发布依赖检查见
  [联调手册](../harness/local-paas-authx.md)。
- DataPilot 已发布的 MicronautDataSourceEngine 仍将用户上界限定为旧 User。
  FlowDataSourceEngine 当前仅注册数据源与配置，没有传入 AuthxSession 的业务调用点，
  因此保留该上游要求的类型声明。没有通过 raw type 或强转宣称它支持 AuthX 用户。
  若新增使用 AuthxSession 的 DataPilot 用户操作，必须先升级上游泛型契约。
- 本次不部署认证基础设施或创建账号。真实登录需要宿主已有 AuthX 数据表、
  Redis、DataPilot 数据源、必要的授权服务及配置。技术测试与真实登录验收分别报告。
