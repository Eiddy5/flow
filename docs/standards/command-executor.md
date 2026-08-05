# CommandExecutor 开发规范

## 适用范围

本规范适用于 `server` 模块中 `org.cses.flow.core` 下的写操作。Controller 和其他
模块内调用方都通过同一份 Core Service/Command 链路使用这些规则。

## 固定调用链

```text
Controller（Core 外）
  -> Service
  -> CommandExecutor(Session, Command)
  -> CommandHandlerRegistry
  -> 具体 CommandHandler
  -> Repository
  -> Domain
```

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
- CommandExecutor 将 Session 和事务 DSL 一起放入 `CommandContext`。
- Handler 通过 `CommandContext.getSession()` 获取原始具体 Session，
  因而可以直接使用 `CsesSession` 或 `XpaSession` 的扩展能力。
- 系统任务统一传入 PAAS 提供的 `Session.Robot`，不得传入 `null`。

## 事务规则

- 事务只由 `CommandExecutor` 使用具名 `@Named("flow")` 的
  `org.x9.jooq.JOOQ.runReturn` 开启。
- Flow 事务只允许使用 `datasources.flow`；不得注入或回退到宿主的 `default`、
  `mattermost` 等 JOOQ Bean。
- `CommandContext` 保存当前事务派生出的 `DSLContext`。
- Handler 和 Repository 必须使用 `CommandContext.getDsl()`。
- Handler、Domain 和 Repository 不得自行开启新事务。
- Handler 内不得再次调用 `CommandExecutor`。
- Command 校验、Handler 执行或 Repository 操作抛出异常时，异常继续传播并回滚事务。

## Command 规则

- Command 只保存调用方能够决定的参数。
- Command 必须不可变。简单数据协议可以使用 `record`；需要隐藏构造、复杂校验、
  继承或框架代理时使用 `final class`。具体选择规则见
  [`project-development.md`](project-development.md)。
- `validate()` 只校验 Command 自身字段，不访问 Repository。
- Command 不注入 Service、Repository、Handler 或具体 Task 扩展。

## Handler 规则

- 具体 Handler 统一放在
  `org.cses.flow.core.handlers.<业务模块>`，例如 Flow Handler 放在
  `org.cses.flow.core.handlers.flows`。业务模块分包规则见
  [`../project-structure.md`](../project-structure.md)。
- Handler 名称必须包含动作和领域，例如 `DeployFlowHandler`。
- 一个 Command 必须且只能注册一个 Handler。
- Handler 按“加载聚合、调用领域行为、保存聚合、返回结果”的顺序编排。
- Handler 不保存请求级可变状态，不直接发布事件。
- 重复 Handler 注册和缺失 Handler 都必须立即失败。

## Query 规则

- QueryHandler 只处理复杂查询、树形组装、统计和查询后二次处理。
- QueryHandler 可以使用 Repository 或直接使用 JOOQ。
- QueryHandler 不修改领域状态，不调用 CommandExecutor。
- 当前 QueryHandler 直接返回 Repository 提供的领域对象隔离副本，不建立与
  领域对象字段重复的 Snapshot/View。未来只有查询结构与领域结构实质不同时，
  才能在 Core 外或查询适配层引入专用 Projection。
