# Flow DataPilot 持久化边界参考

## 范围与结论

本文基于 Flow 当前代码、ADR 与本机 `cloud-datapilot:2.0.48` source JAR 做静态审计。
结论是采用**语义混合（semantic hybrid）**，而不是按技术栈一次性替换全部 Repository。
DataPilot 只承接后续新增、语义简单的单表 CRUD 与只读投影。
现有工作流核心持久化继续使用 JOOQ，不迁移到 DataPilot。

## 持久化边界

继续保留 JOOQ 的链路包括：

- `Flow` 草稿、正式版本及其 `flow_tasks` 定义树；
- `Execution` 与 `task_runs` 的原子保存、恢复和乐观锁；
- `queues` 的投递、竞争认领、事务内删除和崩溃恢复。

这些链路包含聚合一致性、多表写入、显式租户条件、版本冲突或队列行锁语义。
它们的现有入口分别见 [FlowRepositoryImpl.java](../../core/src/main/java/org/cses/flow/infrastructure/repositories/flows/FlowRepositoryImpl.java)、[ExecutionRepositoryImpl.java](../../core/src/main/java/org/cses/flow/infrastructure/repositories/executions/ExecutionRepositoryImpl.java) 与 [PostgresQueueStore.java](../../core/src/main/java/org/cses/flow/infrastructure/queues/PostgresQueueStore.java)。
对应基线表见 [flows.sql](../../gen/sql/flow/tables/flows.sql)、[flow_tasks.sql](../../gen/sql/flow/tables/flow_tasks.sql)、[executions.sql](../../gen/sql/flow/tables/executions.sql)、[task_runs.sql](../../gen/sql/flow/tables/task_runs.sql) 与 [queues.sql](../../gen/sql/flow/tables/queues.sql)。

DataPilot 的候选场景必须同时满足：新能力、单表、简单 CRUD 或读投影、无队列认领、无复杂聚合保存。
任何需要 `FOR UPDATE`、`SKIP LOCKED`、复合主键批量保存或强租户兜底的场景继续使用 JOOQ。
读投影可以由 DataPilot 返回专用 View/DTO，但不得让 DataPilot `Model` 穿透 Core 领域边界。

## 2.0.48 源码审计

审计基线是 [cloud-datapilot-2.0.48-sources.jar](/Users/cses-7/.gradle/caches/modules-2/files-2.1/org.x9.cloud/cloud-datapilot/2.0.48/9d745d9e9d2901adffc573733c7127d19dec3d74/cloud-datapilot-2.0.48-sources.jar)。
版本来源也记录在 [libs.versions.toml](../../gradle/libs.versions.toml)。

- `TransactionManager` 的外部 DSL 接入以真实 DataSource 的具名数据源名作为 `sessionMap` key。
- 因此 DataPilot 声明的 `option.name` 必须与传入 DSL 的物理数据源名完全一致。
- `JooqDatabaseHandler.update` 虽构造了“主键 + version”的 `conditions`，实际执行却仍是 `where(model.buildPkCondition())`。
- 这意味着 2.0.48 的 update 乐观锁条件未真正进入 SQL，不能用于 Execution 等强并发写链路。
- `VersionField` 的数据类型固定为 `INTEGER`，update/delete 中也把原版本强转为 `Integer`。
- 这与 Flow 核心现有 `long`/数据库宽版本语义不应无验证混用。
- `CompanyIdField` 只注册 `BeforeCreate`，负责创建时回填 company ID。
- 查询、更新、删除没有自动追加 company ID 条件，租户隔离仍必须由调用方显式表达并测试。
- 2.0.48 的数据库查询路径没有提供 `FOR UPDATE` 或 `SKIP LOCKED` 能力。
- 因此 DataPilot 不适合当前队列认领与其他悲观行锁场景。
- `DbRepository.batchSave` 留有待优化标记，并明确暂只支持非联合主键。
- 因此复合主键、批量 upsert 或需精确冲突报告的链路不能迁移。

上述判断来自 JAR 内的 `TransactionManager.java`、`JooqDbSessionImpl.java`、`JooqDatabaseHandler.java`、`VersionField.java`、`CompanyIdField.java` 与 `DbRepository.java`。

## Flow 接线约束

[FlowDataSourceEngine.java](../../core/src/main/java/org/cses/flow/infrastructure/datapilot/FlowDataSourceEngine.java) 当前把 `option.name` 写成 `default`；必须改为 `flow`。
原因是外部 DSL session 按数据源名登记，而 Flow 的唯一物理具名数据源就是 `flow`。
同一文件当前使用 `new SchemaImpl("public")`；必须改为生成 Schema 常量 `org.flow.gen.flow.Public.PUBLIC`。
这样 DataPilot collection 与现有 JOOQ 生成表共享同一个明确的 Flow `public` Schema。

物理连接配置继续只使用 `datasources.flow.*`，不新增第二套 DataPilot URL、用户或连接池配置。
该约束由 [ADR 0040](../decisions/0040-bind-flow-datasource-from-host-yaml.md) 与 [JOOQ 规范](../standards/jooq.md) 定义。
若增加 Flow DataPilot 配置类，该类只承载 feature flags，不承载物理数据库属性。

[FlowDataSourceEngineService.java](../../core/src/main/java/org/cses/flow/infrastructure/datapilot/FlowDataSourceEngineService.java) 是具名 `DataSourceService`。
DataPilot 通用 `DataSourceController` 会把所有此类 Bean 注入 `/dataPilot` 的 `serviceMap`，空 `serviceKey` 时还会选择其中一个服务。
所以 Flow 内部 Repository 可以直接注入 `FlowDataSourceEngine`，但
`FlowDataSourceEngineService` 必须单独默认关闭，且默认不得出现在通用 controller 的
`serviceMap`。只有显式启用通用 HTTP 管理能力时才创建该 Service Bean，并且普通业务
读写不依赖 `/dataPilot` CRUD 路由。

每一次 DataPilot 操作都必须传入真实调用方 `Session`，禁止构造空 session、伪用户或系统租户替代品。
每一次操作还必须通过 `.dsl(flowDsl)` 复用调用链当前的同一个 Flow `DSLContext`。
这里的 `flowDsl` 必须来自具名 `flow` JOOQ Bean或当前 Flow 事务，不能回退到 `default`。
这样 DataPilot 与同链路 JOOQ 写入共享真实连接、事务、提交和回滚边界。

## 迁移顺序

1. 先修正 `FlowDataSourceEngine` 的 `option.name` 与生成 Schema 常量，并同步单元测试。
2. 增加只含 feature flags 的配置，分别控制内部 engine 与通用 HTTP service；试点前 engine 默认关闭，HTTP service 始终默认关闭。
3. 阻止 Flow service 默认进入通用 `/dataPilot` `serviceMap`，验证空 `serviceKey` 不会选中 Flow。
4. 为适配入口统一要求真实 session 与同一 `flowDsl`，禁止隐式新建独立事务。
5. 只选择一个新增简单单表作为试点，建立 DataPilot collection、Repository adapter 与 Core DTO 映射。
6. 保持 Flow、Execution、TaskRun 与 Queue 的 JOOQ 实现和表结构不变。
7. 试点验证通过后，再逐项评估其他新单表 CRUD/读投影，不做批量机械迁移。

## 必测清单

- 引擎测试断言 `option.name == "flow"` 且 `option.jooqSchema == Public.PUBLIC`。
- 装配测试断言未启用 flag 时没有 Flow `DataSourceService`，通用 `serviceMap` 也没有 `flow`。
- 启用测试断言 DataPilot 使用 `datasources.flow` 创建的真实 DataSource/JOOQ，而非 `default`。
- 事务测试在同一 `flowDsl` 内混合 JOOQ 与 DataPilot 写入，成功时共同提交，异常时共同回滚。
- 会话测试覆盖缺失 session、缺失 company ID 与跨 company 查询、更新、删除的拒绝路径。
- SQL/集成测试证明每个 DataPilot 查询、更新和删除都显式包含 company ID 条件。
- 并发测试不得把 DataPilot 2.0.48 update 当作可靠乐观锁；核心 Execution 继续验证 JOOQ lock version。
- 队列回归继续覆盖 `FOR UPDATE SKIP LOCKED`、事务内删除、崩溃恢复和多消费者竞争。
- 批量测试不得假定 DataPilot `batchSave` 支持联合主键或完整 upsert 语义。
- 回归运行 [FlowDataSourceEngineTest.java](../../core/src/test/java/org/cses/flow/infrastructure/datapilot/FlowDataSourceEngineTest.java)、[FlowNamedDataSourceTest.java](../../core/src/test/java/org/cses/flow/infrastructure/jooq/FlowNamedDataSourceTest.java) 与 [FlowDatabaseIntegrationTest.java](../../core/src/test/java/org/cses/flow/infrastructure/jooq/FlowDatabaseIntegrationTest.java)。

## 决策门槛

DataPilot 版本升级后，只有重新审计并实测乐观锁、租户过滤、行锁和复合主键批量能力，才可扩大边界。
在此之前，“新简单单表走 DataPilot、既有工作流核心走 JOOQ”是默认且可验证的持久化策略。
