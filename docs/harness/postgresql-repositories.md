# PostgreSQL Repository 验证

## 目的

验证 Flow、Execution 和 TaskRun 的真实 PostgreSQL 往返、JSONB 转换、跨版本
Task id 复用，以及业务唯一键冲突。

当前完整关系模型：

```mermaid
erDiagram
    FLOWS ||--o{ FLOW_TASKS : "仅正式版本：company_id + key + version"
    FLOWS ||--o{ EXECUTIONS : "company_id + flow_key + flow_version"
    FLOW_TASKS o|--o{ FLOW_TASKS : "parent_id 形成任务树"
    EXECUTIONS ||--o{ TASK_RUNS : "execution_id 形成运行历史"
    FLOW_TASKS ||--o{ TASK_RUNS : "Execution 的版本范围内由 task_id 解析"
    TASK_RUNS o|--o{ TASK_RUNS : "parent_id 形成运行树"

    FLOWS {
        varchar company_id PK
        varchar id PK "领域 Entity ID"
        varchar key UK "稳定 Flow key"
        bigint version "草稿为空"
        boolean draft "默认 true"
        text source "原始 YAML"
        varchar status
        jsonb creator
        jsonb updater
        jsonb deleter
        bigint created_at
        bigint updated_at
        bigint deleted_at
        jsonb inputs
        jsonb outputs
    }

    FLOW_TASKS {
        varchar company_id PK
        varchar flow_key PK
        bigint flow_version PK
        varchar id PK
        varchar parent_id
        text type
        jsonb inputs
        jsonb outputs
        jsonb properties
    }

    EXECUTIONS {
        varchar company_id PK
        varchar id PK
        varchar flow_key
        bigint flow_version
        jsonb state
        jsonb generation
        bigint lock_version
        jsonb creator
        bigint created_at
    }

    TASK_RUNS {
        varchar id PK
        varchar execution_id
        varchar task_id
        varchar parent_id
        integer iteration
        integer execution_generation_version
        jsonb generation
        jsonb state
        integer order
    }

```

图中的关系都是逻辑关系。数据库不创建外键，由复合身份、唯一约束、应用校验和
同事务写入保证。`flows` 同时保存草稿与正式版本：草稿满足
`draft = true AND version IS NULL`，正式版本满足
`draft = false AND version > 0`；两者都保存原始 YAML `source`。`flows.id` 是 Flow
Domain 的稳定 Entity ID；Flow、Task 快照和 Execution 的版本绑定统一使用
`(company_id, flow_key, flow_version)`。Flow/Task 的
Input、Output 和 Plugin properties 没有独立身份或
生命周期，作为不可变定义快照保存在 JSONB 中；Execution 与 TaskRun 的 inputs、
outputs 保存运行事实，完整 State 以
`{"current":"...","history":[...]}` 作为单一 JSONB 值对象持久化。Execution
与循环 TaskRun 的片段迭代事实分别以 `generation` JSONB 保存 Current/History；
TaskRun 的 `execution_generation_version` 标识它属于哪个 Execution 退回片段。
Execution
和 TaskRun 按当前运行状态过滤时使用 `state ->> 'current'` 表达式索引，不存在独立
运行 `status` 或 `state_history` 字段；Flow 的 Audit Status 则以独立文本
`status` 持久化。

## 准备数据库

开发期只维护一个完整建表基线入口；入口会在同一事务中包含全部表级脚本。在空的
PostgreSQL 数据库执行：

```bash
FLOW_POSTGRES_PSQL_URL=postgresql://flow:flow@localhost:5432/flow \
psql "$FLOW_POSTGRES_PSQL_URL" \
  -f gen/sql/flow/001_create_flow_tables.sql
```

`psql` 使用 libpq 连接 URI；不要把下方 Java 测试使用的 `jdbc:postgresql:` URL
直接传给它。

基线使用 `IF NOT EXISTS`，因此相同内容可以重复执行；它不会修正已经存在但定义
不同的对象。基线发生变化后必须显式重建开发数据库，不执行 ALTER、回填或旧数据
转换。应用不携带 Flyway，也不会在启动时创建、升级或清理 Schema。

领域时间在 Java 中使用 Epoch 毫秒 `long/Long`，当前 Flow 基线中的领域时间列使用
`bigint`，由 Entry 直接映射；Queue 的 `created_at` 是数据库消息顺序字段，可由
Queue Adapter 使用数据库默认值生成。领域审计列不由数据库默认值或生成列补齐。

Flow 的 creator/updater/deleter、状态和时间由 Flow 领域产生并由 `FlowEntry` 写入；
Execution 只保留 `BaseDomain` 的 creator/created_at；TaskRun 不保存独立审计或
start/end 时间列，状态历史完整保存在 `state` JSONB 中。

测试只使用随机 companyId，不会清空或删除数据库中的其他租户数据。
UC 测试默认保留本场景创建的 Flow、Execution 和 TaskRun 数据，便于在本地
PostgreSQL 中观察真实入库结果。跨 Server 场景会显式关闭启动 Execution 的
ApplicationContext，再由新的 ApplicationContext 使用精确的
`executionId + taskRunId` 从 PostgreSQL 恢复并推进。完成型场景结束后不应存在
PAUSED TaskRun；PAUSE 不再创建独立等待表记录。
如需在 CI 或一次性验证后清理，
显式设置 `FLOW_POSTGRES_TEST_CLEANUP=true`；清理范围只限本次夹具创建的随机
companyId。

## 运行 Repository 集成测试

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
FLOW_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/flow \
FLOW_POSTGRES_TEST_USER=flow \
FLOW_POSTGRES_TEST_PASSWORD=flow \
./gradlew :core:test \
  --tests '*PostgresRepositoryIntegrationTest'
```

未设置 `FLOW_POSTGRES_TEST_URL` 时，Repository 集成测试会跳过。

## 运行 UC 测试

UC-01～UC-08 通过 Micronaut Core 的公开 Service/Command 链路装配生产
PostgreSQL Repository，不再使用 Flow 内存 Repository。运行 UC、Core 模块或
项目完整测试前必须设置相同的三个 PostgreSQL 环境变量：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
FLOW_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/flow \
FLOW_POSTGRES_TEST_USER=flow \
FLOW_POSTGRES_TEST_PASSWORD=flow \
./gradlew :core:test --tests '*Uc*Test'
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
