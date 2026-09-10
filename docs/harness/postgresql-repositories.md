# PostgreSQL Repository 验证

## 目的

验证 Flow、Execution 和 TaskRun 的真实 PostgreSQL 往返、JSONB 转换、Flow 追加
版本、跨版本 Task id 复用，以及业务唯一键冲突。

当前完整关系模型：

```mermaid
erDiagram
    FLOWS ||--o{ FLOW_TASKS : "仅正式版本：company_id + key + version"
    FLOWS ||--o{ EXECUTIONS : "company_id + flow_key + flow_version"
    EXECUTIONS o|--o{ EXECUTIONS : "parent_id 直接来源"
    EXECUTIONS ||--o{ EXECUTIONS : "origin_id 最初运行"
    FLOW_TASKS o|--o{ FLOW_TASKS : "parent_id 形成任务树"
    EXECUTIONS ||--o{ TASK_RUNS : "execution_id 形成运行历史"
    FLOW_TASKS ||--o{ TASK_RUNS : "Execution 的版本范围内由 task_id 解析"
    TASK_RUNS o|--o| EXECUTIONS : "parent_task_run_id 子调用"
    TASK_RUNS o|--o{ TASK_RUNS : "parent_id 形成运行树"

    FLOWS {
        varchar company_id PK
        varchar id PK "数据库行 ID"
        varchar key UK "稳定 Flow key"
        bigint version UK "Repository 分配，非空"
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
        varchar parent_id
        varchar origin_id
        varchar parent_task_run_id
        jsonb inherited_task_runs
        varchar flow_key
        bigint flow_version
        jsonb state
        jsonb generation
        bigint lock
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
同事务写入保证。`flows` 同时保存草稿与正式版本：两者的 `version` 都非空，并在
同一个 `company_id + key` 序列中由 Repository 按全部历史行分配；删除状态也追加
新版本且参与后续分配。两者都保存原始 YAML `source`。`flows.id` 只标识一次保存的
数据库行；Flow、Task 快照和 Execution 的版本绑定统一使用
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

旧 PostgreSQL 队列表已移除；当前基线仅包含上述四张领域表，关系图保持不变。

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
`bigint`，由 Entry 直接映射。领域审计列不由数据库默认值或生成列补齐。

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

业务、测试和探针均直接使用普通 DSLContext 调用 `find/save`，无需执行作用域。
对象携带加载时的 `lock`，copy 保留版本，可以跨操作传递；保存不能重读最新版本
替换旧凭据。新对象为 null，首次保存为 0，每次成功更新加 1，SQL 失败不回填。
根 CAS 与 TaskRun 仍通过单 SQL 原子提交。显式事务或 savepoint 回滚后必须丢弃
已修改对象并重新加载。完整并发回归还应运行：

```bash
./gradlew :core:test --tests '*CasRepositoryTest' --tests '*ExecutionCasIntegrationTest'
```

此命令使用上文相同的 Java 与 PostgreSQL 环境变量。字段更名后，已有开发库必须
显式重建；基线的 `IF NOT EXISTS` 不会把旧列自动改名。验证优先使用独立临时数据库，
不要为了运行测试清空现有开发数据。

### 2026-09-10 对象版本与 CAS 仓储基类验证

使用独立 PostgreSQL 17 容器及当前完整结构基线，未操作已有开发库。常规测试配置
定向 49/49 通过，0 跳过：CasRepositoryTest 3、ExecutionCasIntegrationTest 12、
ExecutionEntryTest 3、ExecutionTest 14、PostgresRepositoryIntegrationTest 7、
CommandExecutorTest 5、ExecutorEventMessageHandlerTest 5。证据目录为
`core/build/cas-inline-lock-targeted-current/core/test/xml/`。
覆盖另一个根类型继承、重复保存、跨上下文副本、真实双线程竞争、根子和双根原子性、
SQL 失败不回填，以及显式事务/savepoint 回滚后重载。此结果不代替 UC 或全量验收；
使用 JDK 25 执行，不构成 Java 21 兼容性验证。
最终全项目回归为 447 通过、8 失败、1 跳过，其中 CAS 专项仍为 49/49；
正常 SQL 探针已执行，根子保存保持单 SQL，不作为数据库性能证据。场景覆盖、失败
详情、环境复核及临时资源清理见
[本轮批次报告](../test-reports/flow/BATCH-2026-09-10-1311.md)。

### 2026-09-09 通用 CAS 最小重构验证（历史实现）

使用独立 PostgreSQL 17 容器，未操作已有开发库。定向测试 32/32 通过：
`CasSupportTest` 4、`ExecutionCasIntegrationTest` 12、`PostgresRepositoryIntegrationTest`
7、`CommandExecutorTest` 5、`ExecutorEventMessageHandlerTest` 4。
覆盖弱对象身份、跨表与跨操作隔离、友好冲突文案、真实并发与根子原子性，以及
外层事务提交清理、嵌套提交保留和嵌套回滚失效。原有退回派生保存用例一并保留。
`:server:flowQueryProbe` 正常模式执行成功，确认 SQL 探针适配新入口；它使用 Mock JDBC，
不作为真实数据库性能证据。
UC 验收结果另见本轮 `docs/test-reports/flow/` 报告，不能由定向测试替代。

同工作区并行 Gradle 测试可能覆盖默认 XML 和二进制结果；各任务应使用独占的
测试结果目录，不引用被其他任务覆盖的报告。可用临时 Gradle init script 配合
`-I`，按项目及 Test 任务分别设置 `binaryResultsDirectory`、
`reports.junitXml.outputLocation` 和 `reports.html.outputLocation`；不要为一次验证
修改全局构建配置。本轮定向证据位于 `core/build/cas-refactor-targeted/core/test/xml/`。

### 2026-09-08 CAS 改造验证

使用单独创建的 PostgreSQL 17.10 数据库，未修改本机已有开发库。Schema 验证脚本
在另一个临时容器中成功执行完整基线两次；JOOQ 从新基线重新生成。

- `ExecutionCasIntegrationTest`：7/7，通过两线程真实并发写、同事务连续保存与重复
  加载、正常/异常会话清理（含派生 DSL）、脱离会话的快照拒绝、子写失败回滚、
  子记录身份冲突和陈旧快照不能重新插入已删除根。
- `PostgresRepositoryIntegrationTest`：7/7，验证 Flow/Execution 往返、根子失败原子性
  和当前 Schema；依据 ADR 0072 修正了仍要求已删除审计列存在的两项旧断言。
- `ExecutorEventMessageHandlerTest`：4/4，为本地状态机回归，不是数据库或业务验收。
- `:server:flowQueryProbe`：正常模式执行成功，仅验证 SQL 生成探针能复用新会话接口，
  不是数据库性能验收；旧测量报告的 SQL 长度不回填为新实现数据。
- CAS HTML 原型只检查了脚本语法和新字段名，没有把它当作 PostgreSQL 验证证据。

以上 18 项定向测试不替代 UC 逐场景验收；UC、模块和完整构建结果以本次
`docs/test-reports/flow/` 报告为准。

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

2026-09-08 验证环境注意：当前已解析的 Micronaut/PAAS 依赖要求 JVM 25，Java 21
会在依赖解析阶段失败。本次生成和验证仅为命令选用本机 JDK 25，没有改动项目的
Java 21 源码约定或依赖版本；这不构成 Java 21 构建通过的证据。

## Execution 来源与退回验证

当前来源与继承快照契约见 [ADR 0087](../decisions/0087-derive-execution-snapshots-on-replay.md)。
`executions.parent_id` 与 `origin_id` 保存关系，`inherited_task_runs` 保存该次运行沿用的完整快照；
`task_runs` 仍只包含本实例首次产生的运行实体，因此统计当前完整路径应通过公开 Execution 查询。

退回受理返回 `executionId`（新实例）、`sourceExecution`、`affectedTaskRunIds`。调用方重新查询新实例，
其可见后使用新 executionId 恢复等待任务；旧编号不自动重定向。
`ExecutionService.lineage(session, executionId)` 返回同源快照，HTTP 对应
`GET /executions/executions/{executionId}/lineage`（包含现有 Execution Controller 前缀）。
每个 HTTP 快照给出 `origin`、`inheritedTaskRunIds`、`effectiveTaskRunIds`，供区分来源、沿用和当前路径。

Schema 变化需使用空库执行基线并重新生成 JOOQ。不得把新基线的 `IF NOT EXISTS` 当成旧库升级。
本需求使用独立临时 PostgreSQL，不修改本机已有开发库；具体运行证据见当次 UC04、UC10、UC11 及批量测试报告。
