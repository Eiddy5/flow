# PostgreSQL Repository 验证

## 目的

验证 Flow、Execution、TaskRun 和 ExternalTask 的真实 PostgreSQL 往返、JSONB
转换、跨版本 Task id 复用，以及乐观锁冲突。

## 准备数据库

在空的 PostgreSQL 数据库执行：

```bash
psql "$FLOW_POSTGRES_TEST_URL" \
  -f gen/sql/production-release/flow/2026-07-28/001_create_flow_tables.sql
psql "$FLOW_POSTGRES_TEST_URL" \
  -f gen/sql/production-release/flow/2026-07-28/003_align_temporal_columns.sql
```

已有 001 表结构的数据库还需按顺序执行：

```bash
psql "$FLOW_POSTGRES_TEST_URL" \
  -f gen/sql/production-release/flow/2026-07-28/002_align_repository_persistence.sql
psql "$FLOW_POSTGRES_TEST_URL" \
  -f gen/sql/production-release/flow/2026-07-28/003_align_temporal_columns.sql
```

`003_align_temporal_columns.sql` 将旧脚本中的毫秒时间戳转换为
`timestamptz`，使数据库 Schema 与 JOOQ 生成模型使用的 `OffsetDateTime`
保持一致。

测试只使用随机 companyId，不会清空或删除数据库中的其他租户数据。
UC 测试默认保留本场景创建的 Flow、Execution、TaskRun 和 ExternalTask 数据，
便于在本地 PostgreSQL 中观察真实入库结果。场景中出现 PAUSE 时，夹具先关闭
启动 server 的 ApplicationContext，再由独立 External Trigger
ApplicationContext 从 PostgreSQL 恢复并推进，因此保留数据中不应存在 RUNNING
Execution 或 WAITING ExternalTask。如需在 CI 或一次性验证后清理，
显式设置 `FLOW_POSTGRES_TEST_CLEANUP=true`；清理范围只限本次夹具创建的随机
companyId。

## 运行 Repository 集成测试

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
FLOW_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/flow \
FLOW_POSTGRES_TEST_USER=flow \
FLOW_POSTGRES_TEST_PASSWORD=flow \
./gradlew :server:test \
  --tests '*PostgresRepositoryIntegrationTest'
```

未设置 `FLOW_POSTGRES_TEST_URL` 时，Repository 集成测试会跳过。

## 运行 UC 测试

UC-01～UC-07 通过 Micronaut server 的公开 Service/Command 链路装配生产
PostgreSQL Repository，不再使用 Flow 内存 Repository。运行 UC、server 模块或
项目完整测试前必须设置相同的三个 PostgreSQL 环境变量：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
FLOW_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/flow \
FLOW_POSTGRES_TEST_USER=flow \
FLOW_POSTGRES_TEST_PASSWORD=flow \
./gradlew :server:test --tests '*Uc*Test'
```

上述命令默认保留 UC 数据。需要自动清理时追加：

```bash
FLOW_POSTGRES_TEST_CLEANUP=true
```

未设置 `FLOW_POSTGRES_TEST_URL` 时，UC 夹具会立即给出明确错误，不能把缺少真实
数据库的执行误判为 UC 通过。普通领域单元测试仍可独立运行。

## 重新生成 JOOQ

数据库结构变化后，可使用本地默认连接生成：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
./gradlew :gen:generateJooq --rerun-tasks
```

也可通过 `FLOW_JOOQ_JDBC_URL`、`FLOW_JOOQ_JDBC_USER` 和
`FLOW_JOOQ_JDBC_PASSWORD` 指向一次性代码生成数据库。
