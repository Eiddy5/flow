# ADR 0003：使用泛型 CommandExecutor 统一写操作

## 状态

Accepted

## 背景

Flow Core 需要统一创建、修改、发布、启动、完成和取消等写操作的事务入口，同时避免引入完整 CQRS、Command Bus 或事件溯源机制。

项目还要求 Controller 位于 Core 外部，Service 作为 Core 的公开入口，复杂查询由独立 QueryHandler 处理。具体 Task Type 是 Core 外部扩展，但 Command 是 Core 自己维护的封闭写操作集合。

## 备选方案

### 方案一：Service 直接操作 Repository

实现简单，但事务、校验和 Handler 路由会分散到多个 Service。

### 方案二：单个 CommandExecutor 包含所有业务方法

能够统一事务，但 CommandExecutor 会持续增长并持有全部领域依赖。

### 方案三：泛型 CommandExecutor 加精确类型 Handler

Command 使用泛型声明返回值。CommandExecutor 统一校验、JOOQ 事务和 Handler 路由，每个 Handler 负责编排一个具体写用例。

## 决策

采用方案三。

- 写调用链为 `Service -> CommandExecutor -> CommandHandler -> Repository/Domain`。
- `Command<R>` 的泛型 `R` 表达命令返回类型。
- `CommandHandler<S extends Session<U>, U extends User, R, C extends Command<R>>`
  处理一个精确 Command 类型，并保留具体 Session 与 User 类型的关系。
- 具体 Handler 放在 Core 的 `handlers/<业务模块>` 中；通用注册机制放在
  `commands/shared`，不得重新平铺业务 Handler。
- Handler 通过 `commandType()` 显式声明类型，由 Registry 在启动时检查重复注册。
- 用户上下文统一使用 PAAS Session 的具体子类型 `S`，由 Service
  显式传给 CommandExecutor；例如 `CsesSession` 或 `XpaSession`。
- CommandExecutor 使用 `org.x9.jooq.JOOQ.runReturn` 开启事务，并将 Session 与事务内 DSL 放入 CommandContext。
- Handler 和 Repository 复用当前 DSL，不自行开启事务。
- QueryHandler 不进入 CommandExecutor，也不要求独立读库。
- 不引入完整 CQRS、Command Bus、Query Bus、事件溯源或 Repository Service Locator。

本决策取代 ADR 0002 中关于 `WorkflowUnitOfWork` 和 `WorkflowTransaction` 的建议；ADR 0002 的领域聚合与运行推进规则继续有效。

## 理由

泛型把 Command、Handler 和返回值在编译期关联起来。精确类型 Registry 把唯一的泛型擦除转换封装在内部。JOOQ DSL 直接形成事务 Seam，避免增加没有第二个 Adapter 的事务抽象。

## 后果

- 新增写操作必须同时增加一个 Command 和一个精确 Handler。
- CommandExecutor 保持稳定，不包含具体业务规则。
- 重复或缺失 Handler 会在注册或执行时失败。
- Handler 不能嵌套执行其他 Command，需要复用的规则必须进入领域对象或领域服务。
- 将来若 Command 需要跨进程分发，再单独评估 Command Bus 和序列化协议。
