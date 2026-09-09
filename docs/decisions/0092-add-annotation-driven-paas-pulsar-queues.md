# ADR 0092：新增注解驱动的 PAAS Pulsar 队列

## 状态

Accepted（2026-09-09）。用户确认复用 PAAS Pulsar，在 Flow 提供注解配置的二次封装，
支持不同业务队列及基础消费回调和 ACK 测试；该阶段不替换已有 PostgreSQL 队列。
后续用户已要求替换，当前运行装配与公共 Queue 契约以 [ADR 0094](0094-use-pulsar-for-executor-queues.md) 为准。

## 背景

PAAS 已提供 PulsarFactory、Pulsar、JacksonSchema、PulsarConsumerRegistration 与
PulsarConsumerRunner。重新实现连接、消息编码、接收循环和 ACK/NACK 会重复已有能力。
Flow 需要按消息类型注入队列、用方法注解声明业务消费者，并在启动前拒绝重复和非法声明。

现有 DispatchQueue 还承诺 DSLContext 事务发布、原子批量和独立订阅暂停/恢复/关闭。
PAAS 的公开接口无法完整履行这些语义，因此不能把新封装直接注册为旧接口实现。

## 选项

1. 直接使用原生 Pulsar Client：重复 PAAS 的连接、编码和消费生命周期，不采用。
2. 让新封装实现原 DispatchQueue：无法兑现数据库事务和订阅生命周期，不采用。
3. 保留旧实现，新增注解驱动的 PAAS 薄适配：采用。

## 决策

- `queues/annotations/FlowQueue` 标注消息类型，以必填 `name + topic` 表达逻辑队列与
  PAAS Topic。`FlowQueueListener` 标注 singleton Bean 的公开实例方法；方法接收一个
  带 FlowQueue 声明的消息参数，返回 void。注解包含必填 subscription 与默认 1 的
  正整数 concurrency。第一版固定 Shared 消费，不引入 Queue YAML 配置。
- `FlowQueueListener` 唯一允许依赖 Micronaut 的 `Executable` 元注解，启用编译期方法
  发现；其他 Queue 契约仍不依赖 Micronaut，消费者装配及执行留在 infrastructure。
- `infrastructure/queues/pulsar/PulsarQueueFactory` 用 PAAS `InjectUtil` 提取字段或
  构造注入的泛型类型，复用 PAAS Factory，按消息类型缓存薄发布实例。在 Flow 自身
  登记范围内拒绝逻辑名/Topic 被不同类型占用；PAAS 自身仍只按 Topic 缓存，宿主应
  为 Flow 使用独立 Topic，不能与非 Flow 发布器混用不同类型。
- `PulsarQueue` 只提供单条同步、带 key 同步、异步、定时发送。失败统一为
  QueueException，异步接口的即时异常也表现为失败 stage。成功只表示 Broker 受理。
  不支持事务发布、原子批量、独立 close，也不实现原有 Queue/DispatchQueue。
- `PulsarQueueListeners` 使用 Micronaut ExecutableMethodProcessor 处理编译期生成的
  方法元数据。先校验全部声明，再解析回调 Bean，最后在 StartupEvent 中一次性委托
  PAAS 注册。按 `(topic, subscription)` 拒绝本进程内重复注册；多实例可以使用相同
  订阅。同一 Topic 的不同订阅形成独立消费组，不同 Topic 可复用同一订阅名。
- Flow 创建的 PAAS 注册对象不成为 Bean，避免 PAAS LifecycleManager 再次扫描启动。
  PAAS 的 Factory 仍拥有 Producer、Consumer、线程及应用退出清理；Flow 不创建第二套
  Client 或接收循环，不关闭共享 PAAS 实例。
- 回调正常结束返回 true，PAAS 发起异步 ACK；解码、空载荷或业务异常由 PAAS 执行
  NACK。回调必须同步完成工作，不能自行启动异步工作后立即返回。并发大于 1 时同一个
  singleton 可同时接收不同消息，业务必须具备相应线程安全和幂等性。
- 连接、认证、TLS、环境前缀和全局 `pulsar.consumer.enabled` 沿用 PAAS。该全局开关
  为 false 时 Flow 方法消费者也不启动。Flow 不新增队列配置文件或全局开关，也不改变
  宿主已有 PAAS 配置。无 Flow 消费声明时不为 Flow 启动 Producer 或 Consumer。
- 两个现有 Executor Queue Factory、DefaultExecutor、Handler、数据库队列实现、表与
  SQL 基线均保持不变；只在用户为新消息/消费者显式加注解时启用新能力。

## 理由与后果

配置接近业务声明，发送端与消费端共用消息类型上的 Topic，传输实现集中在 PAAS。
业务回调不接触 MessageId、Message、ACK/NACK 或序列化器。代价是第一版不与旧 Queue
Interface 互换，也不拥有动态暂停/恢复和独立关闭能力。

基于当前平台 BOM 的 paas-pulsar 2.5.0.25 源码，仍存在 PAAS 层限制：创建 Consumer
失败可能只记录异常并返回 null；异步 ACK 失败未被主接收循环等待或主动转为 NACK；
close 不明确等待正在执行的业务回调。这些行为不能由 Flow 薄封装宣传为已解决，若需要
增强应优先修正 PAAS。本次测试明确记录异步 ACK 失败边界，不承诺 exactly-once。

## 验证与接入

`PulsarQueueTest` 使用真实 Micronaut 元数据、注入、PAAS Factory 和 ConsumerRunner，
在网络 Client 边界注入可控消息与 ACK 故障，覆盖两队列/消费组隔离、并发注册数量、
回调前不 ACK、成功 ACK、异常 NACK、重投、消息类型冲突和发布失败。它不是 Broker
持久化、网络断线或故障转移测试。接入示例与复验命令见
[`pulsar-queues.md`](../harness/pulsar-queues.md)。
