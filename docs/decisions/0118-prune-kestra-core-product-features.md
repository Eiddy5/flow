# ADR 0118：第二轮裁剪核心中的产品功能和 Docker 实现

## 状态

Accepted（2026-10-13）。用户批准第二轮清理，并明确无需保留兼容接口。
局部修订 ADR 0115/0116/0117 的功能范围；执行、JDBC 队列、调度、通用扩展和
Task 导出仍为必保能力。不加入 Route 或业务逻辑。

当前核心表结构和最新退役能力见 [ADR 0136](0136-retain-triggers-in-the-core-schema.md)。

## 背景

第一轮已移除独立 CLI、Web 和 MySQL 模块，core/script 仍包含 AI 助手、MCP、
匿名使用上报、Dashboard、官方插件目录与自动安装、Docker 容器执行等产品能力。
它们与注册、导出、JDBC 和 Worker 装配有交叉引用，必须按功能链路裁剪。

## 备选方案

1. 仅通过配置关闭：源代码和依赖仍保留，不满足减法目标。
2. 保留所有旧接口并返回空值：增加无用途的兼容层，用户明确不要求。
3. 删除已退出功能及其专用接口，保留通用扩展协议并做技术验收：采用。

## 决策

1. 删除 AI 会话、MCP 服务及专用 Trigger/广播入口/Repository、匿名上报及专用
   gRPC 服务、独立管理端点 Basic Auth 和产品升级提示。保留加密配置下发、Worker
   连接、心跳、任务交付、日志、指标和恢复机制。
2. 删除官方插件目录和自动安装链路。保留本地插件目录扫描、独立 ClassLoader、
   编译期注册、版本识别、手动 Maven/JAR 安装和离线 Schema bundle。
3. 删除 Dashboard 模型、图表/DataFilter SPI、内置图表、专用服务、校验与 JDBC
   查询方法。保留执行/日志/指标/触发器的通用查询和持久化。聚合 Schema 根收敛为
   Flow、Task、Trigger；被删除类型不再出现在注册、文档、图标和 Schema 中。
4. 删除 Docker 执行器、docker-java 依赖和旧 RunnerType/DockerOptions 兼容字段。
   保留 AbstractExecScript、CommandsWrapper、ScriptOutput、Kotlp、Process 及
   通用 TaskRunner SPI。脚本显式指定 taskRunner；不把原来的 Docker 请求静默
   转为宿主进程执行。保留的 TaskRunner 扩展仍可提供独立实现。
5. 保留原生条件、分支、循环、Subflow、暂停/恢复、重试、超时、KV、Namespace
   文件、Secret、Assets、App、FileRenderer 和日志扩展。此轮不裁剪其它内置 Task。
6. 原生 SQL 迁移文件保持内容和 checksum，不清理现有业务数据库。退出功能的
   Repository/Bean 和纯自动安装 Java 迁移退出运行装配；历史表暂留，后续另行制定
   数据库结构迁移。datasources.flow 及宿主连接池、Context 所有权保持不变。
7. 按文件更新来源变更账本，保留原始上游清单。同步清理退役功能的测试、资源和
   生成物；保留能力的失败测试不得通过删除、跳过或放宽断言隐藏。
8. 用户追加要求清除代码残留后，继续删除 CLI/Web 专用 DTO、表达式编辑/AI 上下文、
   DOT 导出与 Flow 自动加载接口、插件 UI manifest 扫描/模型及无消费方的旧类型。
   通用插件注册、任务/触发器/TaskRunner、Storage、Secret、日志、Asset、App、Rule、
   AdditionalPlugin、FileRenderer 等扩展协议与 Task Markdown/Schema/SVG 导出保留。
9. Web 日志 SSE 的消费者、Matcher、FollowLogEvent 及 JDBC 广播工厂一并退出。
   普通 LogEntry 仍由 Dispatch 队列进入 Indexer/日志存储；单条/批量写入的完成结果
   直接返回日志队列确认，不能忽略该队列的失败。ExecutionStreamingService 仍被
   WebhookService 使用，保留执行事件流、异步操作事件及相关队列。
10. 删除仅供 Web 登录使用的 BasicAuth Java 密码迁移、密码散列工具、匿名实例 UUID
    和专用测试。gRPC BasicAuth 的常量时间凭据比较保留，SQL 迁移原文及既有 settings
    行不改动；不会再迁移已退出 Web 登录的数据。
11. 删除无效果的脚本 warningOnStdErr 字段和已被 CommandsWrapper 取代的旧方法。
    移除 main 中的 HTTP Server Netty、Web 密码哈希依赖及退出功能的 AI/MCP 平台声明。
    HTTP 客户端、测试用 HTTP Server、实际使用的脚本能力和配置校验保留；不按“没有
    静态调用”删除反射加载、注解注册或供外部插件使用的通用扩展接口。
12. 从 PostgreSQL 新库脚本移除 `dashboards`、`mcp`、`mcp_session` 的建表、索引和触发器定义。
    已有库通过 `2.1.03-remove-retired-product-tables` 删除当前 Flow schema 中这三张表及其数据。
    两个修改过的脚本仅接受本次明确列出的旧、新 SHA-256 对；已有迁移记录保持原值。
    这局部修订 ADR 0130 对两个 SQL 文件内容的约束。存在外部依赖时，PostgreSQL 以
    `RESTRICT` 拒绝删除并使本次迁移失败。

## 理由

使源码和依赖聚焦嵌入执行引擎，并保持真实插件扩展和 Task 导出闭环。以明确功能
退出处理兼容性，避免保留空实现造成调用成功但功能缺失。

## 后果和验收

不再支持 AI 会话、MCP Tool Trigger、Dashboard、官方插件自动获取和内置 Docker。
使用这些类型的定义应在解析/校验阶段被拒绝。外部插件需要面向保留的协议编译，
不承诺被删除类型的二进制兼容。

验收包括全模块编译及回归、生产依赖隔离、真实 PostgreSQL 执行/JDBC 队列/调度/
持久化/宿主生命周期、独立插件 JAR 加载执行、通用脚本 Process 执行、Task 文档/
Schema/图标导出和 bundle 重载。保留类型的导出差异必须逐项解释。
技术清理验收不替代全部核心迁移台账或真实 CSES 接入验收。

执行证据维护在 [第二轮记录](../harness/kestra-prune-round2.md) 和
[源码残留清理记录](../harness/kestra-prune-unused.md)。
