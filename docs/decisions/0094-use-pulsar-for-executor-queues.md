# ADR 0094：Executor 两条队列切换到 Pulsar

## 状态

Accepted（2026-09-09）。用户要求先替换现有队列，暂不接入通知。本决策修订
ADR 0092 的“不替换”阶段范围和 ADR 0051 的传输装配，不改动流程业务 UC。

## 背景

运行链路只有 ExecutionCommand 与 ExecutorEvent 两条队列。ADR 0084 已将领域保存
与消息发布分开，生产调用方均使用单条 emit，没有使用 DispatchQueue 的事务发布、
批量、暂停或恢复方法。PAAS 薄封装已提供类型注入、方法监听和 ACK/NACK。

## 选项

1. 让 Pulsar 实现旧 DispatchQueue 全部方法：会虚构数据库原子提交和资源关闭语义，拒绝。
2. Service 直接依赖 infrastructure/PulsarQueue：使 Core 依赖具体基础设施类型，拒绝。
3. 收窄现有公共 Queue 发布契约，以注解装配两条 Pulsar 队列：采用。

## 决策

- Queue<T> 是 transport-neutral 的 queueName、emit、emitAsync 发布接口，无 Event
  泛型上界、独立 close 或订阅所有权。PulsarQueue 实现该接口；一个 Factory 方法
  同时支持 Queue<T> 与 PulsarQueue<T> 的类型注入，并按消息声明缓存同一实例。
- ExecutionCommand 与 ExecutorEvent 标注 FlowQueue，逻辑名和 Topic 分别保持
  flow-executor-command、flow-executor-event。命令继续使用既有 JSON 多态协议。
- ExecutionService 以及两个 Executor Handler 注入 Queue<T>，不再通过 Named
  选择 PostgreSQL 实例。保存与发布的调用顺序保持不变。
- DefaultExecutor 只保存两个 Handler，并通过两个 void FlowQueueListener 方法路由。
  订阅名分别与对应 QUEUE_NAME 相同，每实例各一个 Shared Consumer。处理器完成
  保存、Worker 调用及后续发布后才返回；异常交由 PAAS NACK。原始 Handler 返回值
  仍可供同步技术调用使用，不作为 ACK 条件。
- 删除两个旧 Executor Queue Factory。旧 DefaultDispatchQueue 及其显式
  DispatchQueue/QueueSubscription 契约保留为未自动装配的独立适配器；close 所有权
  只属于 DispatchQueue，不扩散回公共 Queue。它们不再参与任何默认运行链路，
  也不作为 Pulsar 故障时的回退。
- 不自动删除 SQL queues 表或已有消息。部署切换须先停止新请求并等待旧版本消费
  完已接受消息，再停止旧进程和启动新版本；不允许两版混跑。不在本次自动迁移运行中
  的积压消息，不修改业务数据库。详细切换检查见 harness/pulsar-queues.md。
- PAAS 统一拥有 Client/Producer/Consumer/接收线程和关闭。运行环境必须能访问
  Pulsar，并启用 PAAS consumer；不新增 Flow YAML，不加入通知消费者。

## 理由

复用已有发布接口与 PAAS 消费实现，替换位置限定在两条运行消息的装配。领域状态机、
仓储 CAS、Session 恢复和幂等处理保持原有职责；无需实现第二套事务或消息接收循环。

## 后果与验证

Pulsar 成为运行必需的外部服务。Shared 不提供跨实例顺序或 exactly-once；已有 CAS
冲突与失败重投仍是执行保障的一部分。业务保存与消息发布不原子，沿用 ADR 0084 的
已保存命令重投后补发推进事件协议。PAAS 异步 ACK 失败、消费初始化失败和在途关闭的
现有限制仍见 ADR 0092；切换不能被解释为这些 PAAS 限制已修复。

验证应覆盖真实 PAAS Schema 的四类命令和内部事件往返、泛型发布注入、真实 Broker
上的双 Topic 消费与 ACK/NACK，以及通过公开 Service 的运行、暂停恢复、取消、重启。
UC 测试使用实际 Pulsar 与 PostgreSQL；网络替身技术测试不能替代这次切换的 UC 验收。
