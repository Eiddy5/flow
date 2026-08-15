# ADR 0061：使用 Record 表达不可变数据协议

## 状态

Accepted

## 背景

Flow 中有一组对象只负责承载一次调用、队列消息或 Worker 运行结果所需的
数据，不拥有聚合身份、生命周期或外部资源。原先这些对象使用普通 class，
需要重复维护字段、构造器、访问器和相等性语义，也容易把运行时事务状态混入
需要持久化的 payload。

## 决策

- Core 的简单写命令使用 Java `record`：
  `CreateExecutionCommand`、`ContinueExecutionCommand`、
  `SaveFlowDraftCommand`、`DeployFlowCommand`、`DeleteFlowDraftCommand` 和
  `DeleteFlowCommand`。
- Executor 的 `Create`、`Resume`、`Cancel` 使用 Java `record` 表达队列中的
  Command payload；它们不继承 `SerializableObject`，也不持有 `DSLContext`。
- Worker 侧的 `WorkerTask`、`WorkerTaskResult` 以及任务执行结果 `RunResult`
  使用 Java `record`。
- Record 的紧凑构造器继续承担原有的非空、规范化和集合复制校验；Map、List 等
  可变容器必须在边界复制为不可变快照。
- 对外已有代码依赖的 JavaBean 风格 `getXxx()` 方法可以保留为兼容适配器，
  但不再通过 setter 提供可变状态。
- `Event`/`ExecutorEvent`、`CommandContext`、`RunContext`、聚合根、实体、
  领域值对象、JOOQ Entry 和带框架生命周期或可变解析状态的对象不因字段少而
  强行迁移。尤其 `Event` 的 `DSLContext` 事务兼容能力按 ADR 0060 保留。

## 理由

Record 能直接表达这些对象的不可变数据协议，并自动提供值语义、结构化访问器
和清晰的构造边界。事务 DSL、Session、领域行为和解析状态则属于运行时资源或
领域不变量，不是可持久化的数据事实；将它们继续放在普通 class 中可以避免把
资源生命周期放进 payload 的 equals、hashCode 或 JSON 结构。

## 后果

- 新增纯数据传输对象时，优先评估是否应使用 `record`，而不是继续增加字段和
  setter 样板代码。
- Record 的 canonical constructor 成为公开构造边界，调用方需要遵守其不变量。
- Record 本身不能替代领域对象；需要身份、生命周期、行为、框架反序列化状态或
  事务资源的类型继续使用普通 class。
- Core 架构测试对 `RunResult` 保留一个明确的任务执行结果协议例外，并要求
  Worker 传输类型保持 Record 形态。
