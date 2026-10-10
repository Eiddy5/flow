# Flow 运行高可用的外部协议边界

核对日期：2026-09-22。范围：同地域跨可用区自动恢复；Pulsar 4.0.x 官方文档、
PostgreSQL 17 官方文档，以及用于说明执行边界的 Temporal 官方文档。
本文是设计前的资料核对与验收准备，不是已接受 ADR，不证明现有集群配置或运行效果。

范围补充：用户随后澄清当前关注运行逻辑全面闭环与稳定性。本文保留为技术参考，
跨 AZ 建议不作为当前实施任务；当前方案见 [ADR 0108](../decisions/0108-flow-runtime-high-availability.md)。

## 1. 消息顺序与确认

**官方事实。** Pulsar Shared 不保证顺序，断连消费者的未确认消息会重新分配。
Key_Shared 按 key 分配消费者，生产端必须禁用普通批处理或采用按 key 批处理；
nack 仍可能造成乱序。两者使用逐条 ACK，不支持累计 ACK。
见 [订阅与确认](https://pulsar.apache.org/docs/4.0.x/concepts-messaging/#subscription-types)。
Key_Shared 的 `allowOutOfOrderDelivery` 还可以显式放宽故障时顺序要求。
见 [KeySharedPolicy API](https://pulsar.apache.org/api/client/4.0.x/org/apache/pulsar/client/api/KeySharedPolicy.html)。

**对 Flow 的建议。** 以 `executionId` 路由能减少同实例竞争，但不能把消息顺序
当作状态正确性的唯一条件。数据库必须检查预期状态、版本与当前 attempt；重复消息
应能读到此前结果，迟到消息应能被拒绝。ACK 的前置条件是已经持久化本次处理结果，
或已经持久化可恢复的后续工作，不能仅表示调用了处理函数。

## 2. 消息持久化、生产去重与数据库事务

**官方事实。** Pulsar 的消息与订阅游标存储于 BookKeeper，Broker、BookKeeper、
元数据服务分别承担不同职责。见
[架构](https://pulsar.apache.org/docs/4.0.x/concepts-architecture-overview/)。
`sendAsync()` 返回的 Future 用于观察发送完成，方法返回本身不表示安全持久化。
见 [发送 API](https://pulsar.apache.org/api/client/4.0.x/org/apache/pulsar/client/api/TypedMessageBuilder.html#sendAsync())。
Broker 去重默认关闭，依赖生产者身份；断连生产者的去重信息还受保留时长影响。
见 [生产去重](https://pulsar.apache.org/docs/4.0.x/cookbooks-deduplication/)。
Pulsar 事务原子关联消息生产和消息确认。见
[Pulsar 事务边界](https://pulsar.apache.org/docs/4.0.x/txn-why/)。

**对 Flow 的推论与建议。** Pulsar 事务不能直接让 PostgreSQL 状态更新也进入同一
事务，生产去重也不能去除消费者已经执行的外部副作用。状态迁移与下一步推进意图应
在同一个 PostgreSQL 事务提交；独立投递器将持久化意图发送到队列。发送成功但更新
投递标记前崩溃允许重复发送，消费端依靠稳定事件 ID 和状态条件保证只采纳一次。
这里的 outbox 是建议，不能据此推断当前所有运行链路已经实现。

## 3. 延迟投递、传输重投与业务重试

**官方事实。** `deliverAt`/`deliverAfter` 仅在 Shared、Key_Shared 下延迟投递。
见 [延迟投递 API](https://pulsar.apache.org/api/client/4.0.x/org/apache/pulsar/client/api/TypedMessageBuilder.html#deliverAt(long))。
默认延迟跟踪配置可能略微提前投递；TTL 可以让未到期消息过期。
nack 重投计数不持久化；官方将可靠重试计数指向 `enableRetry(true)` 与
`reconsumeLater`。Retry topic 支持 Shared，DLQ 支持 Shared/Key_Shared；DLQ 需要
有效订阅或相应保留策略。见
[延迟与重试限制](https://pulsar.apache.org/docs/4.0.x/concepts-messaging/)。

**对 Flow 的建议。** 业务尝试次数、下次执行时间和时间预算以持久化运行事实为准；
传输重投不应直接递增业务尝试次数。收到唤醒后重新校验 `dueAt`、状态和 attempt，
并以有界的数据库故障对账兜底；正常计时继续使用现有 Pulsar 延迟投递，不恢复
普通 UPDATED 扫描计时。不要仅靠 Broker nack 计数实现业务重试上限；也不要假定
Key_Shared 可以直接使用全部 Shared retry-topic 行为。毒消息隔离必须有可查询原因、
告警和受控重新投递入口，不能把进入 DLQ 等同于流程已失败或已完成。

## 4. PostgreSQL 领取、锁与长时间任务

**官方事实。** `FOR UPDATE SKIP LOCKED` 跳过当前无法取得行锁的记录，适合多消费者
竞争队列表，但会得到不完整视图；表级锁仍可能等待。见
[SELECT 锁定条款](https://www.postgresql.org/docs/17/sql-select.html#SQL-FOR-UPDATE-SHARE)。
行锁在事务结束时释放；死锁可能导致某个事务被中止。见
[显式锁](https://www.postgresql.org/docs/17/explicit-locking.html)。

**对 Flow 的建议。** 在短事务中领取并写入 `owner + leaseExpiresAt + claimToken`，
提交后再调用 Task；不要在外部调用期间长期持有数据库事务。心跳续租、超时回收、
结果提交都校验同一个 token。回收通过条件更新使旧 token 失效，旧 Worker 即使恢复
也不能推进流程。死锁、CAS 失败和序列化失败需要有界重试，重试前重新读取事实；
不能复用旧内存对象直接覆盖新状态。

## 5. 数据库 RPO 与自动故障切换

**官方事实。** PostgreSQL 流复制默认异步，切换时可能丢失已提交但未复制的事务。
同步复制可让成功提交等待指定副本持久化；`remote_write` 不等同于副本落盘，
`remote_apply` 则额外等待回放。同步副本不足时提交可能等待。见
[复制与高可用规划](https://www.postgresql.org/docs/17/warm-standby.html#SYNCHRONOUS-REPLICATION)。
PostgreSQL 本身不提供完整故障检测与自动提升控制；提升后必须隔离旧主，避免双主。
见 [故障切换](https://www.postgresql.org/docs/17/warm-standby-failover.html)。

**对 Flow 的建议。** 若目标是单可用区故障时已确认状态 RPO=0，关键运行事务需要
跨 AZ 同步持久化，并只提升具备已确认 WAL 的候选副本。必须一起设计写入口切换、
旧主隔离、连接池重连和提交结果不确定时的幂等查询。同步副本不足时，应明确选择
暂停写入还是降低持久性；不能一面自动降为异步，一面继续宣称 RPO=0。该目标只覆盖
约定故障域，不等于任何数量的副本同时损毁都不丢数据。

## 6. Pulsar 跨可用区的具体要求

**官方事实。** BookKeeper 支持按 rack/region/zone 放置副本；rack 可以代表可用区。
副本不足时的行为取决于强制最少故障域配置；严格要求无法满足会阻止新 ledger 创建
或恢复，非严格策略可能放宽分布。Broker 及自动恢复侧都需要采用对应策略。见
[Bookie 放置与隔离](https://pulsar.apache.org/docs/4.0.x/administration-isolation-bookie/)。

**对 Flow 的建议。** 跨 AZ 部署应验证 Broker、Bookie、元数据 quorum、访问入口均可
承受目标 AZ 故障。除了副本总数，还要核对写 quorum、ACK quorum 的故障域分布，以及
失去一个 AZ 后的剩余容量和新 ledger 创建条件。不能仅根据“3 个 Broker”推导消息
高可用，也不能脱离实际拓扑直接给出一组固定 quorum 数字。

## 7. Worker 超时与外部副作用

**官方事实。** Temporal 使用执行超时识别没有返回的任务，并按策略重试；取消通过
协作机制传递，运行代码可能忽略取消。见
[执行生命周期](https://docs.temporal.io/activity-execution)。
外部操作成功后 Worker 在报告前崩溃，仍会触发重试，因此业务操作需要幂等键，且应由
被调用服务执行去重。见 [Activity 幂等](https://docs.temporal.io/activity-definition#idempotency)。

**对 Flow 的建议。** Worker lease 过期只是撤销框架接纳其结果的资格，不能证明旧
进程或外部操作已经停止。框架生成并传递稳定业务操作键，自动重试期间保持不变；
claim token 另行递增，用于隔离旧 Worker，不直接等同业务 Attempt 次数。接收端支持 fencing 时可拒绝旧 token，
否则仍需幂等接口或结果查询。对于既不幂等也无法查询结果的操作，失败窗口应进入
可见的待确认状态，不能盲目承诺自动重试且绝不重复。

## 8. 建议的故障验收准备

以下是技术验证建议，不作为已确认 UC 或已经通过的测试报告：

- 在状态事务提交后、投递前退出进程：后续推进由持久化意图恢复。
- 在 Broker 接受消息后、投递标记落库前退出：重复投递只产生一次有效状态迁移。
- Worker 已调用外部服务但未报告时退出：业务幂等键避免重复副作用。
- Worker 长时间暂停并被接管后恢复：旧 token 的心跳与结果均被拒绝。
- 在 Pause/重试到期附近重启 Broker 与 Scheduler：允许唤醒延迟，不提前采纳、不丢失。
- 同一事件重复、乱序、迟到以及 DLQ 重新投递：实例仍能收敛到合法状态。
- 下线一个 AZ：实际记录数据库与消息服务 RPO、恢复时间、积压清空时间及重复副作用数。
- 数据库切换期间制造提交结果不确定：调用方能依稳定请求 ID 查询确认，不创建重复运行。

本次仅阅读官方资料并整理建议，未探测生产环境、修改配置或运行上述验证。
