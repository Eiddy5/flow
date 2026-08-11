# Default Dispatch Queue 负载测试手册

## 目的与边界

`DefaultDispatchQueue` 的并发正确性和基础性能通过独立 Gradle 任务运行：

```bash
./gradlew :core:dispatchQueueLoadTest
```

该任务不属于普通 `test`、`check` 或 `build` 链路。它连接真实 PostgreSQL，并通过
Micronaut 装配的具名 `flow` Hikari DataSource、JOOQ Configuration 和
`org.x9.jooq.JOOQ` 驱动 Queue；不得使用每次事务直接创建 JDBC Connection 的测试
Adapter 代替生产连接池。测试从 Context 取得具名 `flow` Hikari DataSource 与 JOOQ
Configuration，再构造生产使用的 `org.x9.jooq.JOOQ` Facade。测试 Context 会关闭与
Queue 无关的 Micronaut health monitor，避免它在长测期间装配外部 Redisson/Discovery
依赖并污染性能日志。每条测试连接还使用 shutdown timeout 作为 PostgreSQL
`statement_timeout` 和 JDBC `socketTimeout`，使失败场景中的阻塞 JDBC 调用最终可被
回收；正常场景不得触发该上限。这样测得的连接竞争、事务行为和延迟才与实际装配一致。
场景 Runner 只通过公开的 `DispatchQueue`、
`QueueSubscription` 和 `CompletionStage` 契约工作；具体 `DefaultDispatchQueue` 的
创建、测试 Event `Class<T>` 绑定与数据库残留检查收敛在测试环境 Fixture 内。生产
Queue 直接使用项目 `JsonFactory` 构造和恢复 Event，不装配测试专用编解码 Interface。

任务默认使用小规模 `smoke` profile。默认断言只覆盖消息完整性、Queue 隔离和用于
发现卡死的宽松超时；TPS 和延迟只作为本次运行的观测结果输出，不设置依赖 CPU、磁盘、
网络或数据库规格的固定性能门槛。

## 数据库准备

负载测试会创建并删除带唯一运行标识的消息。必须使用独立测试数据库，不得指向生产库
或共享开发库。运行前先在空数据库执行当前基线：

```bash
psql <测试数据库连接串> \
  -f gen/sql/flow/001_create_flow_tables.sql
```

当前基线要求统一 `flow_queues.payload` 为 `jsonb`，且顶层必须是 JSON object；
`queue_type + queue_name` 是 Queue 隔离边界。当前负载场景只生产和消费
`queue_type = 'DISPATCH'` 的行，不验证 Broadcast Interface、消费游标或保留清理。
负载测试不兼容仍按传输类别拆分载荷表或使用 `bytea` payload 的旧测试数据库，应先按
开发基线规则重建。

通过以下环境变量提供连接信息：

| 环境变量 | 必填 | 说明 |
| --- | --- | --- |
| `FLOW_POSTGRES_TEST_URL` | 是 | PostgreSQL JDBC URL，例如 `jdbc:postgresql://127.0.0.1:5432/flow_queue_load` |
| `FLOW_POSTGRES_TEST_USER` | 否 | 数据库用户，默认 `flow` |
| `FLOW_POSTGRES_TEST_PASSWORD` | 否 | 数据库密码，默认 `flow` |

测试启动的 Micronaut Context 将这些变量映射到 `datasources.flow`，并从容器中取得
`@Named("flow")` 的 Hikari DataSource 和 JOOQ Configuration。缺少 URL、Schema 未准备完成，
或实际取得的不是具名 `flow` 数据源时，任务应直接失败，不能回退到 `default` 数据源。

最小运行命令：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
FLOW_POSTGRES_TEST_URL=jdbc:postgresql://127.0.0.1:5432/flow_queue_load \
FLOW_POSTGRES_TEST_USER=flow \
FLOW_POSTGRES_TEST_PASSWORD=flow \
./gradlew :core:dispatchQueueLoadTest
```

## 负载参数

所有负载参数使用 `FLOW_QUEUE_LOAD_*` 环境变量。未设置时采用下表的 `smoke` 默认值；
显式值覆盖 profile 默认值。正整数参数必须大于零，batch 不得大于事件总数。内置 profile
均位于单个测试方法 12 小时的最外层卡死保护以内；大幅覆盖 repetition 或三个动态超时
时，调用方也必须同步核对总预算。

| 环境变量 | `smoke` 默认值 | 作用 |
| --- | ---: | --- |
| `FLOW_QUEUE_LOAD_PROFILE` | `smoke` | 内置 profile：`smoke`、`baseline` 或 `soak` |
| `FLOW_QUEUE_LOAD_EVENT_COUNT` | `2000` | 每个发布模式或逻辑 Queue 请求发布的事件数 |
| `FLOW_QUEUE_LOAD_PUBLISHER_THREADS` | `4` | 并发生产线程数 |
| `FLOW_QUEUE_LOAD_CONSUMER_SUBSCRIPTIONS` | `4` | 同一逻辑 Queue 的竞争 Subscription 数 |
| `FLOW_QUEUE_LOAD_QUEUE_INSTANCES` | `2` | 同一逻辑 Queue 使用的 `DefaultDispatchQueue` 实例数 |
| `FLOW_QUEUE_LOAD_BATCH_SIZE` | `50` | 同步批量发布每批事件数 |
| `FLOW_QUEUE_LOAD_MAX_ASYNC_IN_FLIGHT` | `64` | 异步发布允许同时未完成的最大 Stage 数 |
| `FLOW_QUEUE_LOAD_PAYLOAD_BYTES` | `128` | 每个测试 Event 的固定 payload 大小 |
| `FLOW_QUEUE_LOAD_POLL_INTERVAL_MILLIS` | `10` | Queue Subscription 的数据库轮询间隔 |
| `FLOW_QUEUE_LOAD_POOL_SIZE` | `16` | 具名 `flow` Hikari 连接池上限 |
| `FLOW_QUEUE_LOAD_REPETITIONS` | `1` | 每个场景的重复次数；每次使用新的 Queue 名 |
| `FLOW_QUEUE_LOAD_PUBLISH_TIMEOUT_SECONDS` | `60` | 等待全部发布完成的宽松超时 |
| `FLOW_QUEUE_LOAD_DRAIN_TIMEOUT_SECONDS` | `60` | 等待全部唯一事件被消费的宽松超时 |
| `FLOW_QUEUE_LOAD_SHUTDOWN_TIMEOUT_SECONDS` | `30` | 等待 Subscription、Queue 和 Executor 关闭的宽松超时 |
| `FLOW_QUEUE_LOAD_RESULTS_FILE` | `core/build/reports/dispatchQueueLoadTest/results.jsonl` | JSONL 结果文件；可为并行保留不同 profile 显式改名 |

内置 profile 只提供一组可重复的初始参数，所有字段仍可被上表中的显式环境变量覆盖：

| Profile | Event/场景 | Producer | Consumer | Queue 实例 | Batch | Payload | 重复 | Pool | Poll |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `smoke` | 2,000 | 4 | 4 | 2 | 50 | 128 B | 1 | 16 | 10 ms |
| `baseline` | 50,000 | 8 | 8 | 4 | 100 | 1 KiB | 3 | 48 | 5 ms |
| `soak` | 1,000,000 | 16 | 32 | 8 | 250 | 1 KiB | 10 | 80 | 10 ms |

`soak` 会产生数千万次 Event 传输，只能在隔离且容量明确的环境显式执行；不能在共享
开发数据库或普通 CI Worker 上运行。

例如，可以显式扩大单次运行，但这仍然只是当前机器上的场景测试：

```bash
FLOW_POSTGRES_TEST_URL=jdbc:postgresql://127.0.0.1:5432/flow_queue_load \
FLOW_POSTGRES_TEST_USER=flow \
FLOW_POSTGRES_TEST_PASSWORD=flow \
FLOW_QUEUE_LOAD_PROFILE=baseline \
FLOW_QUEUE_LOAD_EVENT_COUNT=100000 \
FLOW_QUEUE_LOAD_PUBLISHER_THREADS=16 \
FLOW_QUEUE_LOAD_CONSUMER_SUBSCRIPTIONS=16 \
FLOW_QUEUE_LOAD_QUEUE_INSTANCES=4 \
FLOW_QUEUE_LOAD_POOL_SIZE=48 \
FLOW_QUEUE_LOAD_PAYLOAD_BYTES=1024 \
FLOW_QUEUE_LOAD_REPETITIONS=3 \
FLOW_QUEUE_LOAD_PUBLISH_TIMEOUT_SECONDS=300 \
FLOW_QUEUE_LOAD_DRAIN_TIMEOUT_SECONDS=300 \
./gradlew :core:dispatchQueueLoadTest
```

## 场景与正确性不变量

每个 Event 携带本次运行的 `runId`、单调递增的 `sequence` 和固定大小 JSON payload。
Consumer 按 `runId + sequence` 记录首次交付、重复交付和跨 Queue 交付。回调线程只记录
结果，不直接执行 JUnit 断言；场景结束后由测试线程统一校验。

负载 Event 的 `dsl()` 返回 `null`，因此同步发布由 Queue 开启事务；异步发布按生产
契约同样始终开启独立事务，后台任务只携带排除 DSL 的 JSONB Queue Entry。调用方 DSL
的提交、回滚、同批同一 DSL、混合 DSL 拒绝、持久化 payload 排除 DSL 以及按
`Class<T>` 恢复类型的规则由聚焦集成测试验证，不属于本手册的吞吐场景。

### Backlog 关闭并重开恢复

1. 在没有 Consumer 的情况下发布全部事件，并等待发布操作完成。
2. 关闭全部 Publisher Queue 实例。
3. 使用相同逻辑 Queue 名创建全新的 `DefaultDispatchQueue` 实例并注册 Consumer。
4. 等待 backlog 清空并关闭新实例。

必须满足：

- 成功接受的事件数等于请求发布数。
- 重开后收到的唯一 `sequence` 集合与成功接受集合完全一致。
- `missing=0`、`duplicates=0`、`crossQueue=0`。
- Queue 全部关闭后，该逻辑 Queue 在数据库中的 `pending=0`。
- 不依赖原 Queue Java 对象、原 Subscription 或进程内通知完成恢复。

这里的“重开”只表示关闭并重新创建 Queue 实例，用于验证数据库持久化；它不模拟 JVM
崩溃或操作系统进程重启。

### 同步与异步并发生产、竞争消费

使用多个 Queue 实例和 Subscription 同时消费，并分别运行以下发布模式：

- 并发逐条同步 `emit(T)`。
- 同步批量 `emit(List<T>)`。
- 有界并发批量 `emitAsync(List<T>)`；在途 Stage 不得超过
  `FLOW_QUEUE_LOAD_MAX_ASYNC_IN_FLIGHT`。

多个 Producer 使用确定且互不重叠的 sequence 区间，并通过同一个 start gate 开始。
必须满足：

- 每个成功发布的 `sequence` 恰好出现一次，`missing=0`、`duplicates=0`。
- 所有发布 Stage 都完成；发布失败直接使 JUnit 场景失败，已形成的结构化结果不得被当作
  该 repetition 的成功记录。
- 同一 Subscription 的 Consumer 调用不重叠。
- 最终没有遗留消息：Queue 全部关闭后必须满足 `pending=0`，关闭在宽松 shutdown
  timeout 内完成。

测试不声明 FIFO，也不要求不同 Consumer 获得相同数量的消息。`SKIP LOCKED` 下的消费
顺序和竞争分布不是正确性条件。

### 逻辑 Queue 隔离

同时创建两个不同的逻辑 Queue 名，每个 Queue 使用独立 `runId` 并并发发布、消费。
必须满足：

- 每个 Consumer 只能收到所属 Queue 的 `runId`。
- 每个 Queue 分别满足 `missing=0` 和 `duplicates=0`。
- 一个 Queue 完成或关闭不能让其他 Queue 提前完成、丢失事件或接收错误 Event。

每次 repetition 都使用不可复用的 Queue 名前缀。每个场景先记录 Queue 关闭后的 pending，
再单独清除该 Queue 名并确认清理后 pending 为零；`AfterAll` 的前缀清理只是最终兜底。
无论成功还是失败，清理顺序均为停止 Producer、关闭 Subscription、关闭 Queue，再删除
测试残留；清理异常需要保留为原失败的 suppressed exception，不能覆盖首个正确性失败。

## 结果输出

每个场景、发布模式和 repetition 同时写入控制台、JUnit report entry 和以下 JSONL：

```text
core/build/reports/dispatchQueueLoadTest/results.jsonl
```

JSONL 每行严格对应一个 `(runId, scenario, repetition)`，是多 repetition 和双 Queue
场景的性能结果载体；JUnit XML 只作为测试通过证据，不能作为性能数据的唯一来源。每条
记录至少输出：

- 完整负载配置、连接池大小和唯一 `runId`。
- requested、accepted、callbacks、unique、missing、duplicates、crossQueue、关闭后/清理后
  pending、清理行数、开始 drain 时的剩余数 `drain_start_remaining` 和 failures。
- publish TPS、delivery TPS 和端到端 TPS。
- 从发布开始到首次 Consumer 回调的端到端延迟 `p50`、`p95`、`p99`。
- publish、drain、total 和 shutdown 用时。

计时使用 `System.nanoTime()`。Backlog 的 drain 表示重新创建 Consumer 后清空积压所需
时间；并发发布场景的 drain 表示全部 publish 完成后追平剩余积压所需时间，端到端 TPS
覆盖发布与消费重叠的整个区间。`delivery TPS` 使用 `drain_start_remaining / drain 时间`，
不会把 publish 完成前已经交付的 Event 错算到追平吞吐中；`end-to-end TPS` 才使用全部
唯一 Event 除以从发布开始到消费完成的总时间。异步 Event 的延迟起点在取得 in-flight permit 后、调用
Queue API 前，因此不包含压测工具自身的 semaphore 等待，但包含编码、落库、排队和回调
前的全部 Queue 路径。

TPS 和百分位结果用于比较同一环境、相同参数下的代码变化，不作为默认断言，也不能直接
与另一台开发机或 CI Worker 的结果比较。默认断言仅包括上述完整性不变量以及 publish、
drain、shutdown 是否在宽松 timeout 内结束。

## 不属于生产容量认证

一次 `:core:dispatchQueueLoadTest` 通过，表示单 JVM、当前 PostgreSQL 和当前 Hikari
配置下的公开 Queue API 通过了有限场景的并发完整性检查。它不覆盖，也不能替代以下
验证：

- 多 JVM 或多节点同时竞争同一逻辑 Queue。
- 在锁定、Consumer 回调或提交窗口执行 `kill -9` 的崩溃恢复。
- PostgreSQL 断连、主备切换、事务提交结果不确定或连接池故障。
- 不兼容 `Class<T>`/Event JSON 结构、坏 payload、慢 Consumer 和 Consumer 内嵌套
  数据库事务。
- 六小时以上 soak、千万级统一消息表膨胀、不同 `queue_type` 的负载干扰、autovacuum、
  WAL 和容量趋势。

这些场景必须在隔离环境中单独执行，并结合业务幂等、连接池余量、数据库监控和故障注入
结果完成生产容量认证。不得用 smoke profile 的 TPS 或一次本地大参数结果给出生产容量
承诺。
