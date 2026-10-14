# ADR 0120：Worker 直接使用 JDBC 队列

## 状态

Accepted，2026-10-13。修订 ADR 0117/0118 中保留 Worker Controller gRPC 的决定。
用户确认本轮先完成 JDBC，Pulsar 后续接入；不加入 Route 或业务逻辑。

## 背景

当前唯一后端是 PostgreSQL，嵌入宿主通过同一 JVM 调用，持久化使用宿主提供的
可配置 `datasources.flow`。Worker 无须再通过独立 Controller 代理数据库、调度
结果、日志与心跳。仅把 RPC 改成异步消息而继续保留代理，会增加一次队列转发和
新的请求超时协议。

## 选项

1. 保留 gRPC：部署需额外端口、服务发现与鉴权，不能满足本次要求。
2. 保留 Controller，使用 JDBC 请求/回复替换每个 RPC：增加关联、超时及重复回复状态。
3. Worker 直接使用队列和原生 Repository：复用现有 PG 事务和恢复机制，本轮采用。

## 决定

- 删除 `worker-controller` 模块、Protobuf RPC 定义、客户端/服务端、发现、鉴权、
  远程元数据/文件/KV 代理及其专用配置、测试。18 个模块组成根构建。
- 普通 Worker 和 SystemWorker 共用按实例创建的 fetcher/sender，分别消费默认和
  `system` 队列，独立线程池、缓冲及在途任务计数。保留核心 Task、Trigger 和扩展 SPI。
- `KeyedDispatchQueueInterface.claim` 明确批量上限和事务契约：在
  `FOR UPDATE SKIP LOCKED` 事务内登记 `worker_job_running`，随后删除队列行；
  提交成功后才交给本地执行队列。登记冲突只丢弃重复或过期交付；更新一代的
  Trigger 调度或 Task 重试保留队列消息等待旧归属释放，异常回滚整批。
- 结果通过原生 TaskResult/TriggerEvent/ExecutionCommand 队列发布。归属校验、
  发布与归属更新在同一 PG 事务内完成。旧 Worker 或旧 Trigger epoch 的结果被拒绝。
  每次 Task 领取产生独立 delivery ID，即使同一 Worker 领取重试，也拒绝上一轮的迟到结果。
- Task 终态结果提交后保留 `resultPending` 标记，恢复扫描不得重跑已经发布终态结果的
  Task。Executor 应用结果后按终态时间标识释放标记，再推进后续任务或重试；
  标识与原生 JSON 协议统一到微秒精度，避免纳秒时间经队列往返后无法匹配。
- WorkingDirectory 子任务结果携带父 Worker job ID，使用父归属校验，子任务完成不
  提前释放父归属。Realtime Trigger 的普通输出不释放归属，终止或失败才释放。
- Sender 保留发送失败的当前事件并重试，保持本地发送顺序；关闭时仍有未交付事件
  则不得上报正常完成。任务恢复保持至少一次语义，不保证外部副作用恰好一次。
- Kill 使用 JDBC Broadcast；集群控制事件继续使用原生 Broadcast；服务心跳直接写
  PG 服务注册表。元数据、日志、KV 与文件访问使用已有 Repository/Storage。
- `WORKER` 进程必须配置 repository、queue、storage，宿主统一提供加密密钥与存储。
  无 Controller 下发密钥或远程配置。PG 连接池所有权、显式迁移策略保持不变。
- 本轮不新增表或变更历史 SQL；归属完成字段使用原生 JSONB 载荷。

## 理由与后果

去掉中转服务后，没有 gRPC 监听端口或额外通信后端。Worker 需要直接访问宿主
配置的 PG 和 Storage；多进程部署必须保证数据库、加密密钥与存储一致。
现有未完成任务状态不可与旧 gRPC Worker 混跑；升级应先停止旧服务并处理在途任务。

未来 Pulsar 适配必须满足事务领取、归属、确认及结果发布的可靠性契约。PG 与 Pulsar
不能共享本轮 JDBC 事务，届时需要单独设计持久化 inbox/outbox，不能只替换配置值。

## 验收

运行 `bash docs/harness/verify-kestra-embedded.sh --full-regression`。本轮证据与
限制记录在 [JDBC Worker 验收记录](../harness/kestra-jdbc-worker.md)。重点覆盖真实 PG
并发领取、回滚、归属隔离、结果幂等、失败重试、执行/调度、WorkingDirectory、
取消、Worker 恢复，以及外部插件加载与 Task/Schema 导出。
