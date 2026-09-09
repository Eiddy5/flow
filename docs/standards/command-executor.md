# CommandExecutor 开发规范

## 适用范围

本规范适用于 `core` 模块中 `org.cses.flow.core` 下的写操作。Controller 和其他
模块内调用方都通过同一份 Core Service/Command 链路使用这些规则。

## 固定调用链

```text
Controller（Core 外）
  -> Service
  -> CommandExecutor(Session, Command)
  -> CommandHandlerRegistry
  -> 具体 CommandHandler
  -> Repository 加载领域
  -> Domain 执行业务行为
  -> Repository 保存完整领域
```

Execution 的启动、外部 Resume 和 Cancel 是已确认的异步 Executor 链路：
`ExecutionService` 通过统一的 `create(session, key, Optional<version>, inputs)` 入口
选择最新或精确 Flow，并直接构造 Executor Module 拥有的 `Create`、`Resume` 或 `Cancel`
Command，并投递到持久化
`ExecutionCommand` Queue；`DefaultExecutor` 只把 Queue 消息路由给
`ExecutionCommandEventHandler`，不进入 Core `CommandExecutor`。该 Handler 负责恢复
外部命令上下文、校验并物化或更新 Execution，保存成功后投递只携带生命周期
身份的内部 `ExecutorEvent`；它不创建 `ExecutorContext` 或调用 `ExecutorService`。
Resume Command 只携带
`company`、`actorId`、`executionId`、`taskRunId` 和规范化 outputs；Cancel Command
只携带 `company`、`actorId` 和 `executionId`；消费者从
Execution 反查精确 Flow Reversion。可信调用方继续既有 `CREATED` Execution 时，Service
使用 `CommandExecutor.execute(..., completion)` 在领域保存后完成 Create Queue
投递；Handler 仍不得嵌套调用 CommandExecutor。

复杂读取不进入 CommandExecutor，固定由 Service 调用 QueryHandler。

## 泛型契约

- `Command<R>` 使用 `R` 声明命令返回类型。
- `CommandHandler<S extends Session<U>, U extends User, R, C extends Command<R>>`
  只处理一个具体 Command，并同时保留具体 Session 和 User 类型。
- `CommandExecutor.execute(S, C)` 返回与 `Command<R>` 一致的 `R`。
- `CommandExecutor` 的 JOOQ 和 Registry 由 Micronaut 构造器注入。
- 泛型擦除造成的未检查转换只能存在于 `CommandHandlerRegistry` 内部。
- Handler 必须通过 `commandType()` 显式声明精确 Command 类型，不使用反射解析泛型。

## Session 规则

- 用户上下文统一使用 `S extends Session<U>`，其中 `S` 是具体 Session
  子类型，`U` 是 Session 内部的具体 User 类型。
- 例如 `CsesSession extends Session<CsesUser>`，对应
  `S = CsesSession`、`U = CsesUser`。
- Controller 从 PAAS Session 参数绑定能力获取 Session，Service 原样传给 CommandExecutor。
- Session 不属于前端可控的 Command 参数，不得把 `userId` 从请求体复制进 Command 代替 Session。
- CommandExecutor 将 Session 和普通 DSL 一起放入 `CommandContext`。
- Handler 通过 `CommandContext.getSession()` 获取原始具体 Session，
  因而可以直接使用 `CsesSession` 或 `XpaSession` 的扩展能力。
- 系统任务统一传入 PAAS 提供的 `Session.Robot`，不得传入 `null`。

## 持久化边界

- CommandExecutor 使用具名 `@Named("flow")` JOOQ 创建普通 DSLContext，不开启业务事务。
- Handler、Domain、Repository 不打开跨调用事务；按加载、领域行为、完整保存的顺序处理。
- Repository 使用传入的 DSLContext，不能回退到宿主数据源，不能用 SQL 业务条件替代领域行为。
- 完整聚合的父子保存由仓储以单个 SQL 保证；并发旧快照冲突必须显式反馈，不能静默覆盖。
- `execute(..., completion)` 在 Handler 返回后调用传输动作，不再承诺与领域写入原子提交。
  回调不能嵌套命令或再次修改领域。
- 异常继续传播；已完成的其他保存或外部调用不会因为异常自动回滚。
- Execution 快照、Worker 回调与 Queue 传输边界见
  [ADR 0084](../decisions/0084-save-domain-snapshots-without-business-transactions.md)。
  数据库执行入口内部限定加载元数据生命周期，业务使用普通 `find/save`，不启动
  数据库事务；具体协议见 [ADR 0091](../decisions/0091-hide-repository-cas-behind-save.md)。

## Command 规则

- Command 只保存调用方能够决定的参数。
- Command 必须不可变。简单数据协议可以使用 `record`；需要隐藏构造、复杂校验、
  继承或框架代理时使用 `final class`。具体选择规则见
  [`development.md`](development.md)。
- `validate()` 只校验 Command 自身字段，不访问 Repository。
- Command 不注入 Service、Repository、Handler 或具体 Task 扩展。

## Handler 规则

- 具体 Handler 统一放在
  `org.cses.flow.core.handlers.<业务模块>`，例如 Flow Handler 放在
  `org.cses.flow.core.handlers.flows`。业务模块分包规则见
  [`../project-structure.md`](../project-structure.md)。
- Handler 名称必须包含动作和领域，例如 `PublishFlowHandler`。
- 一个 Command 必须且只能注册一个 Handler。
- Handler 按“加载聚合、调用领域行为、保存聚合、返回结果”的顺序编排。
- Handler 不保存请求级可变状态，也不直接发布 Core Queue Event；Execution 启动由
  `ExecutionService` 投递 Executor Command。Executor Module 的
  `ExecutionCommandEventHandler` 是外部命令进入 Executor 的唯一入口，并只向内部
  `ExecutorEvent` Queue 投递状态推进事件。
- 重复 Handler 注册和缺失 Handler 都必须立即失败。

## Query 规则

- QueryHandler 只处理复杂查询、树形组装、统计和查询后二次处理。
- QueryHandler 可以使用 Repository 或直接使用 JOOQ。
- QueryHandler 不修改领域状态，不调用 CommandExecutor。
- 当前 QueryHandler 直接返回 Repository 提供的领域对象隔离副本，不建立与
  领域对象字段重复的 Snapshot/View。未来只有查询结构与领域结构实质不同时，
  才能在 Core 外或查询适配层引入专用 Projection。
