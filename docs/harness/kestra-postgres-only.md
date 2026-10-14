# PostgreSQL 唯一后端清理与验证

依据：[ADR 0119](../decisions/0119-use-postgresql-only.md)。分支：`codex/kestra-core-migration`。
本轮清理已落入源码，PG 收敛的技术复验通过。根全量回归曾因测试辅助类性能问题
中断，剩余套件已完整补跑；core 仍有 5 个既有失败，因此不宣称全量回归全绿。

## 保留的 JDBC 分层

| 模块 | 当前职责 |
| --- | --- |
| `jdbc` | Repository/状态存储的公共实现、事务与重试、迁移历史、JOOQ 配置；只装配 PG |
| `jdbc-postgres` | PostgreSQL Repository、JSONB/时间/全文查询、advisory lock、迁移 SQL、队列客户端 |
| `queue-jdbc` | 原生 Dispatch/Broadcast、发布/领取/ACK/重试、清理与队列工厂 |
| `embedded` | 将原生组件绑定到宿主已有 `datasources.flow`；共池、无 default 回退、无隐式 DDL |

不因只有一种数据库就合并所有模块。队列协议、数据库操作与宿主资源所有权仍是
不同职责；不新增 Route 或业务代码。

## 删除与迁移

原路径移除 118 个文件，其中 113 个退出、5 个测试文件迁到 PG；注册模块从 22 降为 19。

- `jdbc-h2`：104 个文件，包含实现、H2 SQL、构建和测试。共同 Repository/Runner/队列
  测试已有 PG 子类，保留 PG 执行入口。H2 专用缓存、函数和迁移场景随实现退出。
- `repository-memory`：7 个文件；它是 H2 数据源创建与临时数据库重写。
- `runner-memory`：5 个文件；它仅传递 memory 后端并验证 memory 到 H2 的回退。
- `jdbc/EphemeralDatabase`：1 个文件；唯一调用方属于已移除的临时 H2 模式。
- 1 份传入 MySQL `batchSize` 的日志清理 YAML：删除重复样例，原五条日志清理测试
  复用已有正常 YAML，保留数量和成功断言，并按唯一 execution ID 确认日志已清空。
- MySQL/H2 方言装配、MySQL 死锁错误码、队列批量删除和时间绑定分支已删除。
  锁竞争使用 PG 冲突忽略；真实 SQL 异常不再被 H2 的宽泛异常捕获吞掉。
- `PurgeLogs.batchSize` 和对应 Service/Store 参数是 MySQL 专用，整个调用链已删除。
  `PurgeExecutions.batchSize` 是有效的执行清理分页参数，继续保留。

迁移到 `jdbc-postgres/src/test/java` 的 5 个文件：

| 文件 | 保留的验证 |
| --- | --- |
| `LogDataStoreInterfaceFactoryTest` | 插件发现、类型列表、未知插件错误 |
| `PostgresConcurrencyLimitStateStoreTest` | 并发限制状态存储契约 |
| `PostgresLogDataStoreDedicatedTest` | 独立 PG 日志库与主库隔离；只用于原生扩展验证，embedded 仍禁止独立日志 URL |
| `ReducedLogDataStore`、`ReducedLogDataStoreTest` | 日志扩展的游标分页、不支持清理时的能力降级 |

锁契约已由现有 `PostgresRepositoryTest` 执行，因此直接复用，未增加重复入口。

原 `JdbcMigrationHistoryStoreTest` 的历史、校验和、修复校验和与空库检测用例改为真实
PG，每个测试创建并清理自己的临时 schema。其它模块通过随机 schema 隔离 Context。
移除 MySQL 专用测试，增加非 PG 方言/日志 URL 拒绝测试；不关闭任何保留能力的测试。
上游原有 `PostgresFlowRepositoryTest.invalidFlow` 空覆盖本轮未更改，不计为新增覆盖。

## 回归发现的排序元数据问题

第一次全量回归在 core 阶段主动中断，不能计为完成。线程栈和临时 PG 当前查询表明，
原生 `loadSortableColumns` 使用 `DSL.meta().getTables()` 枚举全库；测试 Context 的
隔离 schema 增多后，它会反复读取所有表的元数据，并把其它 schema 的同名表列
混入当前资源的可排序字段。

改为 PG `to_regclass` 按连接 search_path（或显式 schema）定位实际表，查询该表
`pg_attribute`；继续保留字段过滤与大小写映射。

第二次完整回归进入 PG 套件后，还发现测试辅助类 `JdbcTestUtils` 也通过全库
`meta().getTables()` 后过滤当前 schema，且对每张表重复读取连接的 schema。
先终止 PG 测试 JVM，让 `--continue` 继续其余模块；Worker 的线程栈随后也确认
命中同一旧辅助类，于是停止该次 Gradle 客户端，保留已完成模块的原始结果。
辅助类改为在 SQL 中限定 `current_schema()`，完整重跑 PG、JDBC、Worker 和
Worker Controller 套件。中断部分不计为通过，也不复用上一次单例测试 XML。

排序修复前反例已在真实 PG 上失败（旧实现没有拒绝外部同名表字段），证据在
`build/validation/postgres-only/metadata-red/`。新增跨 schema 反例验证外部同名表
独有列被拒绝、当前表大小写列可排序、显式限定 schema 的表可排序。

## 数据源和来源边界

宿主仍配置 `datasources.flow` 的 URL、凭据、schema 和池容量，Repository、日志、
队列、显式迁移历史及锁共用此池。事务包装器只接受 PG；日志数据源只识别 PG。
原生日志 SPI 仍保留，宿主适配继续拒绝独立日志 URL，不取得宿主池关闭所有权。

PG 的 10 个 SQL 文件与固定上游 SHA-256 全部一致。H2 SQL 已删除；未对任何业务库
执行迁移，也没有重写原生表/JSONB/时间协议。原始来源清单不变，退出、测试迁移及
修改后的源码逐文件登记在 `upstream-changes.json`。

## 可复用验证

```bash
./gradlew testClasses
bash docs/harness/verify-kestra-embedded.sh --full-regression
python3 verify-upstream.py
```

本轮中断套件和日志清理用例的复验命令：

```bash
bash docs/harness/verify-kestra-embedded.sh \
  :core:test --tests 'io.kestra.plugin.core.log.PurgeLogsTest' \
  :jdbc:test :jdbc-postgres:test :worker:test :worker-controller:test --continue
```

脚本默认也运行 embedded 与 tooling 测试、依赖隔离检查和 Schema 导出。

脚本仅创建自身持有的 PostgreSQL 17 容器，开放随机本地端口，创建 flow、control、
kestra_unit、kestra_logs 四个隔离数据库。最后一个仅供独立日志库扩展测试。
`FLOW_KESTRA_UPSTREAM_TEST_*` 提供原生测试连接，`FLOW_KESTRA_LOGS_TEST_URL` 提供
日志测试库；各 Context 的 `datasources.postgres.schema` 使用随机名称。脚本退出时
删除自己创建的容器及卷，不读取宿主业务数据库配置。

`httpcore5-h2` 是 Apache HTTP/2 协议库，不是 H2 数据库驱动，继续保留。
来源档案 `upstream-build/`、过去 ADR/验收记录中的 H2 字样是历史依据，不是当前构建。

## 本轮结果

- 全部测试类编译：通过（1 分 1 秒，61 个任务）。
- 跨 schema 修复后的专项：21/21 通过（embedded 9、PG 元数据反例 1、tooling 11；39 秒）。
- 最终复验构建：通过（9 分 34 秒，85 个任务，18 个执行、67 个 up-to-date）。
  源码和测试输入未变的 up-to-date 结果沿用 Gradle 的输入校验，不计为再次执行。
- 42 个退役生产类型及其 Bean 注册均未出现在当前 JAR 中。
- Task 聚合 Schema 仍为 3 个根、123 个定义；与本轮前比较，唯一变化是删除
  `PurgeLogs.properties.batchSize`，没有丢失 Task 或扩展定义。
- embedded 与 tooling 的运行依赖隔离、Schema 导出、来源清单核对通过。
- 测试容器及测试 JVM 已退出；未访问业务数据库，未新增跳过规则或扩大 flaky 排除范围。

| 套件 | 测试数 | 通过 | 失败 | 跳过 | 证据 |
| --- | ---: | ---: | ---: | ---: | --- |
| PostgreSQL 完整套件 | 983 | 983 | 0 | 0 | `final-suites/jdbc-postgres` |
| JDBC 完整套件 | 90 | 79 | 0 | 11 | `final-suites/jdbc` |
| Worker 完整套件 | 128 | 128 | 0 | 0 | `final-suites/worker` |
| Worker Controller 完整套件 | 194 | 194 | 0 | 0 | `final-suites/worker-controller` |
| embedded | 9 | 9 | 0 | 0 | `final-suites/embedded` |
| tooling | 11 | 11 | 0 | 0 | `final-suites/tooling` |
| PurgeLogs 复验 | 10 | 10 | 0 | 0 | `final-suites/core` |
| core 首次完成的全套结果 | 3519 | 3506 | 6 | 7 | `full-attempt/core` |
| 其余模块 | 484 | 476 | 0 | 8 | `full-attempt` |

其余模块为 executor、indexer、queue、scheduler、script、storage-local、tests、
processor。其中调度 140/140、队列 43/43 均通过。表中证据路径相对
`build/validation/postgres-only/`；PurgeLogs 与 core 全套存在重叠，不能直接相加。

core 全套的 6 个失败中，`PurgeLogsTest.run_with_batch_size` 依赖已退出的 MySQL
专用样例，本轮已改为通用五行清理场景，并将该类全部 10 个测试重新执行通过。
该修复只改测试及样例，未再次变更生产代码；原始全套 XML 保留原始失败，不覆盖
或改写为绿色。剩余 5 个失败与清理前的基线一致：

- `PebbleDocBasicMdDriftTest` 的 3 个测试：缺少已退出 UI 中的
  `ui/src/assets/docs/basic.md`，导致类初始化失败。
- `RequestRunnerTest.request`、`request_no_options`：请求体非法 Unicode 码点
  导致流程只有一个 TaskRun，仍不满足断言；本轮未改动该 HTTP 行为。

因此本轮 JDBC/PG 相关复验没有新增未解决失败，但项目全量回归仍不是全部通过。

## 恢复点与证据

恢复点：`/tmp/flow-kestra-postgres-only-20261013-130629.tar.gz`。
SHA-256：`547bb4cfbc6814f60ad4549e89a3da55fd69f96ba418597d9ca261c4baa54047`，共 2975 个源文件，
排除 `.git`、Gradle 缓存、build 和 Agent 私有目录。它是本轮源码检查点；旧 Flow
的完整备份与恢复仍见 [原始备份记录](kestra-source-backup.md)。

本轮机器证据位于 `build/validation/postgres-only/`；编译与回归完整日志分别为
`/tmp/flow-postgres-only-compile.log`、`/tmp/flow-postgres-only-regression.log`。
被中断的第一次回归留在 `/tmp/flow-postgres-only-interrupted-regression.log`；
反例红/绿记录在 `/tmp/flow-postgres-only-metadata-{red,green}.log`，最终复验日志在
`/tmp/flow-postgres-only-final-suites.log`。`full-attempt/summary.json` 标识了中断与
未启动的套件，`final-suites/summary.json` 记录完整补跑结果；`tested-source-hashes.json`
保存最终受测源文件指纹，`source-delta.json` 记录本轮 118 个原路径删除、68 个修改、
9 个新路径（其中 5 个为迁移的测试）。

阶段一仍有真实 CSES 装配、运行中 Worker 强杀恢复、调度停机补偿和多引擎验收缺口，
本轮 PG 收敛不替代这些验收。尚未提交、推送或合并。
