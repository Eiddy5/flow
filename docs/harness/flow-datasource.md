# Flow 数据源接入与资源生命周期

本手册对应 [ADR 0111](../decisions/0111-unify-flow-datasource-and-compose-repository-enhancers.md)。
独立 Server 与 Micronaut 宿主使用相同的 `datasources.flow` 配置契约，数据库仍为
PostgreSQL 的 `flow`，Schema 为 `public`。运行应用管理连接池生命周期。

## 运行应用配置

在实际启动应用的有效配置中提供：

```yaml
datasources:
  flow:
    url: ${FLOW_DATASOURCE_URL:`jdbc:postgresql://127.0.0.1:5432/flow`}
    username: ${FLOW_DATASOURCE_USERNAME}
    password: ${FLOW_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver
    maximum-pool-size: ${FLOW_DATASOURCE_MAXIMUM_POOL_SIZE:10}
    minimum-idle: ${FLOW_DATASOURCE_MINIMUM_IDLE:2}
```

账号、密码由环境变量或其他有效运行配置提供。SSL、连接超时等属性使用 Micronaut
Hikari 的标准配置；修改连接配置后重启运行应用。Server 已包含此模板，宿主将相同
节点放在自己的配置中，无需复制 Factory 或编写另一个 Repository。

宿主可以同时保留 `datasources.default`。Flow 注入 `@Named("flow")` 的 JOOQ，
`NamedJooqFactory` 选择同名 Configuration，DataPilot 使用 `postgresql.flow`。
宿主自己的默认库和额外无限定名 Configuration 不参与 Flow 的数据库选择。
通常无需额外配置 `jooq.datasources.flow`。

该节点只定义 Flow 的数据库连接。正式 `MicronautDataSourceEngine` 仍使用 DataPilot
既有的外部组件装配与生命周期，运行应用需按其原有约定提供相应配置；本轮不裁剪
这些组件。真实 PG 测试注册轻量 Engine 装配，保留实际数据库 Builder / Handler，
省略 ES、Redis 和索引消费者的启动，不能作为完整生产 Engine 启动验收。

在启动前使用 `psql` 执行 `gen/sql/flow/001_create_flow_tables.sql`；
准备方式见 [PostgreSQL Repository 验证](postgresql-repositories.md)。应用不自动建库、
建表或迁移。未声明 Flow 数据源的 Core 容器不装配依赖该数据源的运行时；声明后
URL 必须非空，配置或连接错误不会回退到宿主默认库。

## Repository 的使用边界

Service、Executor、Scheduler 和触发运行时从具名 Flow JOOQ 获取 DSLContext，
通过 Repository 接口完成数据库操作。具体 `XxxRepositoryImpl` 继承
`BaseRepository`，Entry 负责转换，业务实现负责租户与身份条件，基类调用注入的 `FlowDataSourceEngine` 的原生
`buildQuery/buildUpdater/buildDeleter` 完成普通 CRUD。Engine 注册生成元数据的表、
列和复合主键，DbDataSourceOption.name 与 dbName 均为 `flow`。
基类只接收 Engine 与 Table，不持有或校验业务字段。当前 DataPilot 的复合主键
兼容处理采用原生单项 batchCreate、完整 Model 的原生 Handler 更新，并关闭 Flow 集合的缓存
代理；复合主键计数通过原生 Handler 的 COUNT 聚合完成，避免第一列去重造成少计。
实际数据库 Handler 保持原生实现。细节见 ADR 0111。

CAS、完整聚合 CTE、单快照 multiset 和调度行锁使用 Engine 原生
`useExecutor(...).dsl(...).executeResult(...)` 扩展。在 EngineContext 中绑定调用方
提供的同一连接，外部会话不提交、回滚或关闭调用方连接。单行查询使用原生列表
查询并检查结果数量，最新版本显式限制一行；不走当前会丢失运行时条件的 single 路径。

ExecutionRepositoryImpl 构造器组合注入无状态 `CasEnhancer`。根 CAS 与 TaskRun、
retry/terminal outbox 在同一条 SQL 中执行，成功后才回填 lock。Flow 保持追加式
版本保存。调度计划保持调用方短事务和 `FOR UPDATE SKIP LOCKED`；触发运行时负责
事务与投递编排，命中、事件标记和来源查询由 TriggerRepositoryImpl 保存。
Webhook 幂等锁与调度配置同步锁同样通过 Repository 操作，锁随调用方短事务释放。
Repository 不自行创建池、切换数据源或关闭调用方连接。

## JOOQ 生成器

Generator 使用单独的环境配置：

| 环境变量 | 用途 |
| --- | --- |
| `FLOW_JOOQ_JDBC_URL` | PostgreSQL JDBC URL，指向 flow |
| `FLOW_JOOQ_JDBC_USER` | 生成器读取 Schema 的账号 |
| `FLOW_JOOQ_JDBC_PASSWORD` | 账号密码 |

配置环境变量后执行：

```bash
./gradlew :gen:generateJooq
```

生成进程创建自己拥有的 HikariDataSource，传入 `GenerationTool.setDataSource(...)`，
在生成结束或失败后关闭池。原有本地开发默认值仍保留；共享环境应显式配置连接值。
输出位于 `gen/src/main/java/org/flow/gen/flow/`，禁止手工编辑生成代码。

## 验证环境

技术测试使用 `FLOW_POSTGRES_TEST_URL`、`FLOW_POSTGRES_TEST_USER`、
`FLOW_POSTGRES_TEST_PASSWORD`；真实宿主隔离还提供 `FLOW_POSTGRES_DEFAULT_TEST_URL`，
指向没有 Flow 表的独立宿主数据库。测试自行管理 DataSource，UC 夹具关闭时同时
释放容器和测试适配器。未提供环境变量而跳过的集成测试不算真实数据库验收。

项目的源代码约定为 Java 21；当前解析的部分 PAAS/JOOQ 依赖要求更高 JVM。
本轮生产编译使用命令作用域的 JDK 25，不改变系统默认 JDK，也不能据此声称 Java 21
兼容性已通过。完整执行证据与 UC 边界记录在本轮测试报告中。
