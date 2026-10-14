# JDBC Worker 通信迁移验收

本轮按 [ADR 0120](../decisions/0120-replace-worker-grpc-with-jdbc-queues.md) 实施，
仅使用 PostgreSQL/JDBC，Pulsar 留待后续。未加入 Route 或业务逻辑。

## 运行入口

```bash
bash docs/harness/verify-kestra-embedded.sh --full-regression --console=plain
```

脚本仅创建一次性 PostgreSQL 容器，分别隔离 flow、control、上游测试及日志库；
结束后清理容器。所有命令使用 Gradle Wrapper。不要对业务库运行这些测试。

## 验收项

| 能力 | 验证入口 | 检查内容 |
| --- | --- | --- |
| 原子领取 | `WorkerJdbcProtocolTest` | PG 消费失败回滚消息与归属，重复消息不能覆盖归属，竞争者 SKIP LOCKED，遵守领取上限；新一代 Trigger 等待旧归属释放，旧 epoch 结果拒绝 |
| 原子结果 | `WorkerJdbcProtocolTest` | 写结果后抛异常必须整笔回滚；成功后保留完成标记；拒绝旧 Worker、重复发布及过期 ACK；同一 Worker 重试时拒绝旧 delivery；纳秒时间经微秒 JSON 往返后能确认释放 |
| 发送可靠性 | `QueueWorkerIOSenderTest` | 数据库短暂故障保留当前结果，恢复后按顺序发送；关闭失败不报告正常交付 |
| 容量 | `WorkerJobFetcherTest` | 无缓冲时不超线程数领取，无关完成通知不能释放容量，同一 TaskRun 的短暂重试重叠分别计数 |
| 停止订阅 | `WorkerControlSubscriptionTest` | 普通和 SystemWorker 共用一条 Kill 订阅，最后一个 Worker 停止后才关闭 |
| 强杀恢复 | `WorkerCrashRecoveryTest` | 独立 JVM 执行 Sleep 任务并持久化 RUNNING 后强杀，新引擎在同库恢复到 SUCCESS，保留 RESUBMITTED 尝试，清理归属 |
| 未提交事务恢复 | `FlowJdbcTest` | 强杀持有消费事务的独立 JVM 后，PG 回滚且消息可再次消费 |
| 执行和调度 | `EmbeddedRuntimeTest`、Worker 和 PG 套件 | 外部 Task、脚本、If、Cron、日志、输出、WorkingDirectory、取消、Trigger 领取/Worker 丢失通知 |
| 插件及导出 | `PluginExportTest`、tooling | 独立插件 JAR 加载、TaskRunner 扩展、Task 文档及 Flow/Task/Trigger Schema |
| 宿主边界 | embedded | 同 Context，flow 具名池，default 不回退，显式迁移，停止不关闭宿主数据源 |
| 依赖及来源 | `verifyRuntimeIsolation`、`verify-upstream.py` | 不含 gRPC/Pulsar 和退出后端；上游变更逐文件登记 |

## 运行记录

2026-10-13 第一轮：embedded 12/12、Worker 65/65 通过；PG 完整套件
983 项中 5 项失败，发现反序列化失败反馈、TriggerReceived 与超大结果错误日志三处
行为遗漏。已补入新 Worker 链路，保留原始失败证据，不将失败报告改写为通过。

完整回归已执行 `test --continue`，以下为该轮结果（收尾细化前，跳过项单列）：

| 模块 | 总数 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| core | 3501 | 5 | 7 |
| embedded | 13 | 1 | 0 |
| executor | 199 | 0 | 8 |
| indexer | 3 | 0 | 0 |
| jdbc | 90 | 0 | 11 |
| jdbc-postgres | 983 | 0 | 0 |
| processor | 7 | 0 | 0 |
| queue | 43 | 0 | 0 |
| scheduler | 140 | 0 | 0 |
| script | 18 | 0 | 0 |
| storage-local | 68 | 0 | 0 |
| tests | 6 | 0 | 0 |
| tooling | 11 | 0 | 0 |
| worker | 65 | 0 | 0 |

其中 core 的 5 项失败与此前 [PG 收敛验收](kestra-postgres-only.md) 的基线一致：
`PebbleDocBasicMdDriftTest` 3 项依赖已删除的 UI 文档，`RequestRunnerTest` 2 项请求
正文包含非法 Unicode 字符。未将这些失败归为本轮通过，也未为了迁移修改无关测试。
embedded 的 1 项失败来自新强杀探针缺少 `kestra.server.service.purge.fixed-delay` 配置，
已补齐后复验。

受影响模块全套复验使用：

```bash
bash docs/harness/verify-kestra-embedded.sh :worker:test :jdbc-postgres:test --continue --console=plain
```

受影响模块全套复验通过：embedded **14/14**、Worker **67/67**、PG **983/983**。
包含真实 RUNNING 状态下的独立 JVM 强杀恢复、Trigger 代次隔离、容量及共享订阅关闭。

收尾补充完成标记的微秒精度和 Task delivery ID：前者保证队列时间序列化后仍能释放
完成归属，后者隔离同一 Worker 重试时上一轮迟到的结果。最终针对执行与恢复链路
进行下列复验，2026-10-13 17:00 完成，构建成功：

| 最终验证范围 | 结果 |
| --- | --- |
| embedded（含 5 项 PG 协议验证、RUNNING 强杀恢复） | 15/15 通过 |
| Worker 全套 | 67/67 通过 |
| PG Runner、失联恢复、运行归属 StateStore | 120/120 通过 |
| core 配置校验及日期序列化 | 15/15 通过 |
| tooling 导出验证 | 11/11 通过 |
| JAR、运行时依赖隔离、Task/Configuration Schema 导出 | 通过 |
| 上游源码来源、差异空白、Harness 语法检查 | 通过 |

最终源码上没有再重复全量 983 项 PG 套件或全部 core 测试；最后两项可靠性细化
通过上述受影响链路复验。全量 core 的 5 项既有失败仍未修复。

```bash
bash docs/harness/verify-kestra-embedded.sh \
  :core:test --tests io.kestra.core.validations.ServerCommandValidatorTest \
    --tests io.kestra.core.serializers.KestraDateTimeModuleTest \
  :worker:test \
  :jdbc-postgres:test --tests io.kestra.runner.postgres.PostgresRunnerTest \
    --tests io.kestra.runner.postgres.PostgresServiceLivenessCoordinatorTest \
    --tests io.kestra.runner.postgres.PostgresWorkerJobRunningStateStoreTest \
  --continue --console=plain
```

本轮证据保存在 `build/validation/jdbc-worker/`。`first-suites/` 保留首轮 XML，
`first-acceptance.log` 保存原始日志；`full-suites/` 和 `full-regression.log`
保留完整回归结果；`final-suites/` 和 `final-acceptance.log` 保留受影响模块
全套复验结果；`precision-suites/` 记录精度修正复验，`delivery-suites/`、
`delivery-acceptance.log` 及 `delivery-summary.json` 记录最终源码验收结果。

## 范围边界

本轮验证的是源码迁移后的技术链路，未切换真实 CSES 或业务数据。生产升级需要先
停止旧 gRPC 服务；本轮不提供新旧协议混跑。Task 的外部副作用仍按至少一次交付
设计，不把结果确认机制当作跨外部系统的恰好一次事务。

源码修改前快照：`/tmp/flow-before-jdbc-worker.tar.gz`。此前已存在的源码迁移和
清理工作未重置，当前工作仍在 `codex/kestra-core-migration` 分支，未提交或推送。
