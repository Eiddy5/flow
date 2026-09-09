# 注解式 Pulsar 队列与 Executor 切换

当前两条 Executor 队列已使用 PAAS Pulsar，不需要 Flow 队列 YAML；本次没有通知接入。
封装边界见 [ADR 0092](../decisions/0092-add-annotation-driven-paas-pulsar-queues.md)，
当前装配见 [ADR 0094](../decisions/0094-use-pulsar-for-executor-queues.md)。

## 当前运行链路

| 消息类型 | Topic / subscription | 发布入口 | 消费入口 |
| --- | --- | --- | --- |
| ExecutionCommand | flow-executor-command | ExecutionService 的 create/resume/cancel/rewind | DefaultExecutor.onCommand → ExecutionCommandEventHandler |
| ExecutorEvent | flow-executor-event | 外部 Command Handler / 内部 Event Handler | DefaultExecutor.onEvent → ExecutorEventMessageHandler |

两个消息类型均用 `@FlowQueue(name = QUEUE_NAME, topic = QUEUE_NAME)` 声明。
发布端通过构造器注入 `Queue<ExecutionCommand>` 或 `Queue<ExecutorEvent>`；由统一
Factory 按泛型解析声明，无需 `@Named` 或逐队列 Factory。

消费端使用 `@FlowQueueListener(subscription = QUEUE_NAME)`，每个 Topic 每实例一个
Shared Consumer。两个方法均为 singleton 上的 public void 方法，调用对应 Handler。
Handler 正常返回后 PAAS 才 ACK；任何处理、保存或后续发布失败都会进入 NACK 重投。
它们不创建独立 QueueSubscription，也不自行关闭共享 PAAS 资源。

业务调用方继续通过 ExecutionService 使用流程能力，不直接调用内部消费 Handler。
如需声明另一条队列，在消息类型标注 FlowQueue，并在 singleton 的同步 void 方法
标注 FlowQueueListener；不支持 raw 泛型、未声明类型或参数化消息根类型。

- 公共 Queue<T> 提供 queueName、emit、emitAsync。需要带 key 或定时发布时可注入
  PulsarQueue<T>；两种注入共享同一个发布实例。
- 同一 Topic 只能对应一种消息契约，逻辑名或 Topic 冲突在声明时拒绝。
- 多实例同 Topic/subscription 竞争消费；不同 subscription 分别收到消息。
- 回调必须在返回前完成业务操作，不得同时使用异步执行注解或吞掉异常。
- Shared 不承诺同 key 顺序；领域并发仍由 Repository CAS 和既有重投处理保护。
- 宿主必须启用覆盖自身 Java 包的 Micronaut 注解处理。只放置源码而没有生成可执行
  Bean 元数据不会发现消费者。条件化声明沿用 Bean 的 @Requires。

## 从旧版本切换

1. 使用宿主既有 PAAS 方式提供 Pulsar 连接，启用 consumer。确认生产命名环境下两个
   Topic/持久订阅存在并可用；发布前建立订阅或消息保留策略，避免首次订阅从末尾开始。
2. 停止旧版本的新写请求，等待已有 Execution Command / Event 消息处理完成。
   暂停中的 Flow 可以继续保存在 PostgreSQL，不要求所有业务流程先结束。
3. 核对旧队列表两条链路无积压后停止全部旧进程，再启动新版；不要混跑两版。
4. 用公开 Service 完成一条启动、暂停、恢复到终态的验证，并观察 Broker 订阅积压。
   新消息应只进入 Pulsar，不应再增加旧表对应消息。

旧积压检查（部署侧对所选 Flow 数据库只读执行）：

```sql
SELECT queue_name, count(*)
FROM queues
WHERE queue_type = 'DISPATCH'
  AND queue_name IN ('flow-executor-command', 'flow-executor-event')
GROUP BY queue_name;
```

有积压时继续由旧版本消费，不能直接删除消息后切换。本次代码不自动迁移积压、删除
旧表或变更 Broker 策略；旧适配器保留但不装配，也没有 Pulsar 失败回退路径。

## 发布语义

| 方法 | 行为 |
| --- | --- |
| `emit(event)` | 同步等待 Broker 受理 |
| `emitWithKey(event, key)` | 携带非空路由 key；key 不等于业务去重 |
| `emitAsync(event)` | 返回受理 stage；包括即时初始化异常在内的失败均通过 stage 返回 |
| `emitAt(event, timestampMillis)` | 非负 UTC Unix 毫秒时间点，已过去的时间立即具备投递资格 |

同步失败为 `QueueException`；异步失败的 stage 保留 `QueueException`。受理不表示业务
已经完成，异步调用方必须观察失败。默认 Shared 不保证顺序；此封装尚未暴露 Key_Shared
或“异步且带 key”的组合，不能由调用方据此假设相同 key 串行。

## PAAS 配置与生命周期

复用 PAAS `PulsarClientConfiguration`、PulsarFactory、JacksonSchema 和生命周期管理。
连接信息仍由宿主已有方式提供，Flow 不创建自己的 Client。PAAS development 模式会
给 Topic 和订阅名添加机器名前缀；避免对同一队列再添加另一套前缀，完整 Topic URI 与
PAAS development 前缀组合也必须由宿主核对。

全局 `pulsar.consumer.enabled=false` 会禁用 PAAS 生命周期消费者及 Flow 方法消费者，
不要为控制单个 Flow 队列而改动宿主全局开关。PAAS 当前把整体关闭放在消费者生命周期
管理器中；仅生产者且关闭该全局管理器的宿主须沿用自己的 PAAS 资源清理安排。

本封装不提供单队列 close、动态暂停/恢复、数据库事务发布或原子批量。异步 ACK 失败
目前不会由 PAAS 接收循环立即补发 NACK；Consumer 建立失败和在途回调关闭也有 PAAS
层限制，见 ADR。消息保留不等于副作用 exactly-once，业务应按稳定身份保证幂等。

## 基础消费与 ACK 验证

```bash
./gradlew :core:test --tests '*PulsarQueueTest'
```

当前已解析 PAAS/Micronaut 制品要求 JDK 25；与已有 PostgreSQL harness 相同，在 macOS
上可仅为验证命令选择已安装 JDK，不改变仓库要求或依赖版本：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :core:test --tests '*PulsarQueueTest'
```

测试覆盖：

1. 构造/字段泛型注入、不同类型队列、PAAS JacksonSchema 往返、同步/带 key/异步/定时
   委托，以及即时和异步发布失败。
2. 两个 Topic、多消费组、同名跨 Topic 订阅、并发消费者数量和启动事件幂等。
3. 阻塞业务回调期间不 ACK；回调完成后 ACK 原消息。
4. 业务异常和解码异常触发 NACK；同一消息重投后成功触发 ACK。
5. 注入异步 ACK 失败，记录当前 PAAS 不自动 NACK、仍继续处理下一条消息的边界。
6. 重复订阅、非法返回类型/并发数、未声明消息类型、Topic/逻辑名冲突和 raw 注入拒绝。
7. 无 Flow 消费声明时不创建 Producer 或 Consumer。

使用真实 Micronaut ApplicationContext、编译期元数据、PAAS Factory/Pulsar/Runner；
只在网络 Client 边界使用受控替身，并隔离与本测试无关的宿主 WebSocket/Redis 注册。
这 7 项替身测试本身不启动真实 Pulsar Broker，因此不能证明服务端持久化、重启恢复、断线重投
或集群故障转移。这组技术测试不计入 UC 业务验收；当前切换的 UC 必须使用真实
Pulsar Broker 与 PostgreSQL，运行环境和结果见本次测试报告。

## 真实 Broker 与运行链路回归

`ExecutorPulsarIntegrationTest` 使用真实 Broker、PAAS Client/Factory/Schema/Runner：
验证四种生产命令和内部事件的 JSON 往返、公共 Queue 与 PulsarQueue 共用发布实例，
以及阻塞回调未 ACK、跨队列发布、异常 NACK 后由 Broker 重投、成功后订阅积压清零。
业务 UC 通过 `WorkflowUcFixture` 使用同一真实 Pulsar 装配及真实 PostgreSQL。

本机已验证的隔离 Broker 启动方式（Pulsar 4.0.7）：

```bash
docker run -d --name flow-pulsar-test \
  -p 127.0.0.1:56650:56650 -p 127.0.0.1:58081:8080 \
  -e PULSAR_PREFIX_brokerServicePort=56650 \
  -e PULSAR_MEM='-Xms512m -Xmx1g -XX:MaxDirectMemorySize=512m' \
  apachepulsar/pulsar:4.0.7 bash -c \
  'bin/apply-config-from-env.py conf/standalone.conf && exec bin/pulsar standalone --advertised-address 127.0.0.1 --no-functions-worker --no-stream-storage'
```

容器内 Broker 端口也设为 56650，使 lookup 返回的地址与本机映射一致。仅用于
本机测试，不作为集群部署配置。完成验证后可用 `docker rm -fv flow-pulsar-test`
删除该专属容器及匿名卷。

以下命令只指向专属测试环境。测试结束会清理该 Broker 上两条 Executor 订阅积压，
因此禁止填写业务 Broker 地址。PG 环境与基线见
[PostgreSQL harness](postgresql-repositories.md)。

```bash
export FLOW_PULSAR_TEST_URL=pulsar://127.0.0.1:56650
export FLOW_PULSAR_TEST_ADMIN_URL=http://127.0.0.1:58081
# 按 PostgreSQL harness 提供 FLOW_POSTGRES_TEST_*，仅使用隔离测试库
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :core:test \
  --tests '*PulsarQueueTest' --tests '*ExecutorPulsarIntegrationTest'
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :core:test --tests '*Uc02ExecutionLifecycleTest'
```

测试生命周期仅隔离无关宿主 WebSocket 注册，不替换 Pulsar 传输。上下文重启保留
持久订阅；最终 fixture 关闭后才清理测试积压。清理不作为业务完成的证据。
