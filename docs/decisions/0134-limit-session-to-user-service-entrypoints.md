# ADR 0134：Session 只用于 Flow 用户操作入口

## 状态

Accepted（2026-11-17）。修订 [ADR 0133](0133-session-aware-public-services.md) 中插件查询、后台恢复 Session 和 Flow 关闭宿主 Session 基础设施的条款。

## 背景

CSES 与 Flow 运行在同一个 JVM。CSES 已绑定的 `CsesSession` 符合 Flow 用户操作入口使用的 PaaS Session 契约。
执行命令入队后已拥有租户、目标和操作参数，`SessionCommand` 另保存操作者。消费端原先通过宿主工厂重建 Session，重建后的身份仅用于日志。
同时，Flow 写入应用级 `paas.session.enabled=false` 默认值，会影响依赖 PaaS 默认开关的宿主。
插件查询读取同一份引擎目录，原有 Session 校验不参与目录选择，用户访问目录的授权由宿主负责。

## 选项

1. 每次消费人工操作时重建 Session。只有消费动作确实需要会话或需要再次向宿主授权时，这项依赖才有用途。
2. 在已有 Service 上接收原始 Session，消费端使用已入队的命令和身份事实，Session 配置由宿主掌握。采用。

## 决定

- 保留 `FlowService`、`ExecutionService` 的原有 Session 泛型签名。宿主执行用户认证和业务授权，原样传入会话，Flow 从 Session 读取企业和操作者。
- `PluginService.plugins()`、`plugin(identifier)`、`versions(type)` 直接查询引擎的全局注册表。目录不按租户和用户筛选，宿主负责对外暴露时的权限检查。
- 保留 `SESSION_COMMAND` 的 JSON 格式及子命令、租户、操作者、目标和操作标识。消费者直接使用 `actorId` 记录操作者，并处理原生命令。已有执行仍在锁内核对租户、命名空间和流程，重放同时核对来源。
- 人工恢复由 `ExecutionService.resume` 从当次会话取得恢复人。执行器根据 `Resume` 命令生成 Pause 输出 `resumed.by` 和 `onResume`。
- 移除命令消费使用的 Session 恢复器。宿主管理 `paas.session.enabled`，Flow 不向应用环境写入该属性。

## 理由

用户操作的当前身份由已认证的宿主 Session 确定。入队后的执行受目标与状态约束，命令存储的操作者足以追踪该次操作，并保留 Pause 恢复人的语义。宿主可以独立管理其 Session 基础设施。

## 后果与验证

- 已持久化的 `SESSION_COMMAND` 继续按原格式读取，无需数据迁移。移除为命令消费提供 `SessionFactory` 的接入要求。
- 插件目录的 Java 调用方需要去掉原有 Session 实参。
- Java 25 下执行 `bash docs/harness/verify-flow.sh --full-regression --console=plain --offline`。全局插件查询、宿主配置、隔离 PostgreSQL 的命令持久化与租户验证均通过。执行器闭环测试通过进程内队列验证 `resumed.by` 和 `onResume`。
- 全量任务通过，所有测试模块实际执行，2453 通过、1 项既有跳过、0 失败。运行时依赖隔离与配置 Schema 导出通过。
