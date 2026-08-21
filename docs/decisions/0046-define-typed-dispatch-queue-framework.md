# ADR 0046：定义类型化 Dispatch Queue 框架

## 状态

Accepted（Event 事务字段与同步发布事务来源条款由 ADR 0066 修订）

## 背景

Flow 后续需要在 Execution、Worker 和其他运行能力之间稳定传递异步消息，同时允许
存储、消费循环和事件载体由不同 Module 演进。当前 `WorkerDispatcher` 仍在同一 JVM
和命令事务中同步调用 RunnableTask；仓库没有可供业务依赖的 Queue Interface，也不应
在尚未选择 Adapter 时把存储、序列化、确认或重试协议写入 Core。同步发布需要加入
现有 JOOQ 业务事务是已确认例外，因此 Event 可以直接提供可空的事务级
`DSLContext`；这不授权 Queue Core 执行 SQL 或定义持久化协议。

Queue 的公开 Seam 需要保持类型安全：业务 Module 应拥有具体 Event 及其内部业务
分类，Queue 只表达传输类别和订阅生命周期。首个已确认场景是多个等价 Consumer 对
同一 Event Queue 进行竞争消费；广播、路由键和虚拟分片尚没有本轮 Interface 需求。
传输类别是 Queue 基础设施的逻辑维度，不要求每种类别各建一张消息载荷表；数据库
Adapter 的统一存储决策由 ADR 0047 规定。

## 备选方案

### 方案一：业务直接依赖数据库 Queue 实现

具体类可以一次提供发布、持久化和消费，但业务调用方会依赖数据库实现，替换存储形态
时必须修改所有调用点，也无法在没有数据库的测试中复用同一 Interface。

### 方案二：使用统一 Message 与中心 messageType 目录

Queue 可以用字符串区分全部消息，但中心目录需要了解每个业务 Module 的消息分类，
相同名称还需要全局协调。业务侧也会失去 Java 泛型和多态提供的类型约束。

### 方案三：使用业务 Event 与类型化 Queue Interface

业务 Module 定义具体 Event；Queue Core 只定义 Event 分类、发布能力和 Consumer
注册生命周期。Adapter 实现相同 Interface，并在组合根为每个 Event 契约提供一个
类型化 Queue。

## 决策

采用方案三。

### Module 与 Interface

- Queue 框架位于 Gradle `core` 模块的顶层 `org.cses.flow.queues` Java Module，与
  `core`、`executor` 和 `worker` 平级；Event 分类位于
  `org.cses.flow.queues.event`。
- `Event` 要求业务提供 `String key()` 和可空 `DSLContext dsl()`。Queue Core 不规定
  key 是否为空、是否唯一或具有什么业务含义；`dsl()` 只提供同步发布可加入的调用方
  事务，返回 `null` 表示没有调用方事务。
- `dsl()` 是一次发布的基础设施上下文，不是 Event 业务事实。它不能进入持久化 payload、
  不能用于区分业务 Event 类型，也不改变业务 Module 对字段和内部 `eventType` 的所有权。
- `DispatchEvent` 是 `Event` 的分类 Interface，表示适用于竞争消费。它与未来可能新增的
  `BroadcastEvent` 等 Queue 分类相互独立，但不是业务 Event 内部 `eventType` 的中心
  目录。
- `Queue<T extends Event>` 只公开实现决定的 `queueName()` 和独立的关闭生命周期。
- `DispatchQueue<T extends DispatchEvent>` 公开单条与批量的同步 `emit`、返回
  `CompletionStage<Void>` 的异步 `emitAsync`，以及接收 Java
  `Consumer<T>` 的 `subscribe`。
- `QueueSubscription` 是一次 Consumer 注册的生命周期句柄，公开
  `pause/isPaused/resume/isActive/close`；Consumer 本身继续使用
  `java.util.function.Consumer`，不新增另一套业务 Consumer Interface。
- `QueueException` 是当前唯一的非受检 Queue 异常。Core 本轮不预定义消息大小、
  序列化或数据库异常。

### Dispatch 契约

- 每个具体 Event 契约对应一个类型化 `DispatchQueue`。同一 Queue 可以创建多个
  Subscription；它们作为语义等价的 Consumer 竞争 Event，不形成广播。
- 单个 Subscription 串行调用自己的 Consumer。普通 Dispatch Queue 不保证全局消费
  顺序；需要路由键、分区或有序消费时新增明确能力，不扩张当前 Interface 的隐含语义。
- Queue 的公开发布、订阅与关闭操作以及 Subscription 生命周期操作允许并发调用，
  具体 Adapter 必须保证这些调用的线程安全，但 Core 不规定锁、线程或数据库实现。
- `subscribe` 成功返回时注册已经生效。`pause` 只阻止后续交付，不中断已经开始的
  Consumer 调用；`close` 停止后续交付并等待当前调用结束。Queue 关闭时级联关闭其
  活跃 Subscription，关闭操作保持幂等；Queue 关闭后的新发布和订阅统一失败为
  `QueueException`。Consumer 在自身回调内关闭 Subscription 时，不等待自身，而在
  当前回调返回后完成关闭。
- Queue 处于打开状态但没有活跃 Consumer 时，已接受的 Event 仍保持待交付；这项
  契约不等于跨进程重启或 Queue 关闭后的持久化保证。同步 `emit` 使用非空
  `Event.dsl()` 时，成功返回只表示发布已在调用方事务中暂存，最终接受取决于该事务
  提交；其他成功发布表示 Queue 已接受 Event。两者都不表示接收端业务已经完成。
- 批量 `emit` 和批量 `emitAsync` 对整批 Queue 接受执行全有或全无，不暴露逐项
  结果。同步批量 Event 必须全部不带 DSL，或全部使用同一个 DSL 实例；这可以让发布
  与调用方业务状态同事务，但不表示 Outbox、Consumer 执行或结果应用也在该事务中。
- 同步发布或订阅无法完成时抛出 `QueueException`；异步发布无法完成时，对应
  `CompletionStage` 以 `QueueException` 异常完成。
- Queue Core 不判断业务消费成功，不定义 ACK、NACK、Lease、重试、反序列化错误、
  消费结果信封或业务幂等。发送端与接收端通过各自业务 Event 协议判断业务结果，
  具体 Adapter 决定存储、恢复和 Consumer 异常处理机制。

### 当前范围

- 本轮不定义 Broadcast Queue Interface、广播消费游标和消息保留清理，也不定义 Keyed
  Dispatch、VNode Dispatch、QueueFactory 或同步本地 Listener。未来增加 Broadcast
  能力时复用统一消息载荷表；广播专属游标或投递状态若确有需要，放入独立状态表，不能
  反向拆分 Event payload。
- 本轮不提供抽象实现、测试 Adapter、数据库 Adapter、Micronaut Queue Bean、数据库表
  或后台消费线程。
- 当前 `ExecutorEventHandler -> WorkerDispatcher` 同步调用和 WorkerTask/Result 协议
  保持不变；内部状态交接已经使用 `ExecutorEvent` Queue，消息载体、事务和恢复路径
  由 ADR 0059 确认。
- Queue 不是 Command Bus、事务 Outbox 或事件溯源入口，不改变 ADR 0003 的
  CommandExecutor 写链路和 ADR 0020/0059 的 ExecutorEventHandler 提交边界。
- Subscription 数量只表示 Queue Consumer 并发度，不实现或解释 ADR 0029 的
  `Parallel.concurrent` 作用域配额。

## 理由

- 类型化 Queue 让业务 Module 保有 Event 和内部分类的语义所有权，Queue 无需维护
  中心字符串目录。
- Event 直接提供同步事务，避免为唯一事务来源再建立事务解析 Interface 或发布重载；
  可空 `dsl()` 把有事务与无事务两种调用保持在同一 Queue Interface 上。
- 小型 Interface 隐藏存储、编解码、线程和数据库机制，让后续数据库与测试 Adapter
  可以在同一 Seam 下替换。
- 独立 QueueSubscription 把 Java Consumer 的业务回调与注册生命周期分开，不要求
  Consumer 理解 Queue 状态或确认协议。
- 只定义当前真实需要的 Dispatch，避免在没有调用方和 Adapter 时提前建立广播游标、
  保留清理等完整消息平台能力；这不意味着数据库消息载荷按传输类别拆表。

## 后果

- 后续 Queue Adapter 必须实现已确认的发布、竞争消费、串行 Subscription、批量原子
  接受和关闭契约，但可以自行选择持久化、序列化、轮询和恢复机制。
- 业务 Event 必须实现 `Event` 或 `DispatchEvent`，提供可空 `dsl()`，并自行维护字段、
  key 与内部业务类型；Queue 不解释这些业务内容，也不持久化 DSL。
- 组合根需要为每个 Event 契约提供唯一的类型化 Queue Bean；Core 不提供通用或业务
  专用 QueueFactory。
- 当前运行行为、数据库 Schema 和部署配置均不变化。引入具体 Adapter 或迁移现有
  Executor/Worker 链路时必须补充新的 ADR、验证场景和实现测试；异步接入还必须
  重新确认同事务 Outbox、租户上下文、Execution 锁定、结果回传、幂等和故障恢复。
