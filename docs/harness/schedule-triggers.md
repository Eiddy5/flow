# 定时触发运行与验证

## 接线

服务模块经 `scheduler` 传递依赖 Core；只消费 Core 的宿主需要主动依赖 scheduler
才能启用定时触发。沿用 `datasources.flow` 与 PAAS Pulsar 配置。

```yaml
flow:
  scheduler:
    enabled: true
    poll-interval: 1s
```

应用正常启动后扫描；`enabled: false` 关闭本机定时扫描和配置订阅；Core 的命中投递、流程终态触发和 Webhook 仍运行。此开关不是全局暂停，多实例中其他启用节点仍可工作。

## 数据库

全新开发数据库只应用 `gen/sql/flow/001_create_flow_tables.sql`，其中已包含
`trigger_schedules`。沿用 `docs/harness/postgresql-repositories.md` 的数据库连接与
生成步骤；调度表使用生成的 JOOQ 类型。旧 `scheduled_tasks` 表及独立 SQL 入口已移除，
新实现不读取旧表；按开发期约定重建隔离数据库，不将旧表当作兼容数据源。

遵循开发期基线约定：在隔离开发数据库重建并验证，不由应用自动升级 Schema。
`CREATE TABLE IF NOT EXISTS` 不会为既有 flows 表补列，不能把重复执行当作迁移。
既有业务数据库的数据保留升级需要单独安排；本次没有执行业务库迁移。

## Flow 示例

通过既有 Flow 发布入口提交以下 YAML，每天上海时间 09:00、18:00 各独立启动一次：

```yaml
key: scheduled-example
triggers:
  - key: morning
    type: org.cses.flow.extensions.triggers.ScheduleTrigger
    cron: "0 0 9 * * *"
    timezone: Asia/Shanghai
  - key: evening
    type: org.cses.flow.extensions.triggers.ScheduleTrigger
    cron: "0 0 18 * * *"
    timezone: Asia/Shanghai
    disabled: false
tasks:
  - key: hello
    type: org.cses.flow.extensions.log.Log
    message: scheduled execution
```

六段顺序是秒、分、时、日、月、周；不接受五段 UNIX 或第七段年份。
`inputs` 是每个 Trigger 自身的启动输入映射，必须匹配 Flow 的 Input 声明。
配置随事件异步生效，首次计划按配置处理时刻计算；请为首次到期留出投递处理时间。
未配置 triggers 的 Flow 保留手动启动行为。两个 trigger 设为同一时间会产生两个
不同编号的 Execution。以公开 Execution 查询确认运行成功，outbox.published_at
只证明事件投递，trigger_runs.accepted_at 只证明启动命令投递。

其他触发器配置和 HTTP 接入见 [触发器类型手册](trigger-types.md)。

## 验证

JDK 使用当前构建要求的 25，通过 Gradle Wrapper：

```bash
./gradlew :scheduler:test --no-parallel
./gradlew test --no-parallel
```

测试必须明确设置 `FLOW_POSTGRES_TEST_URL`、`FLOW_POSTGRES_TEST_USER`、
`FLOW_POSTGRES_TEST_PASSWORD`、`FLOW_PULSAR_TEST_URL` 和 `FLOW_PULSAR_TEST_ADMIN_URL`，
指向隔离 PostgreSQL/Pulsar，不能指向业务数据。
另外设置 `FLOW_POSTGRES_DEFAULT_TEST_URL`，指向同一测试账号可访问的独立空宿主库。
Flow 库应已执行上述完整建表入口；宿主库不创建 Flow 表。Scheduler 测试夹具显式启用
`default` 和 `flow` 两个真实数据源，覆盖宿主与 Flow 数据源并存的装配。
2026-09-20 对旧调度器的复验中，沿用通用夹具的 `datasources.default.enabled=false` 时，即使显式设置
`datasources.flow.enabled=true`，仍无法注入具名 `flow` DataSource；补全双数据源
配置后定时场景通过。当前 FlowScheduler 已只依赖具名 JOOQ，测试保留双数据源装配覆盖；
这不代表生产必须依赖默认数据源。
跨模块测试必须串行，避免共享 Pulsar 订阅互相消费消息造成误判。
测试结束关闭上下文后，夹具同时清理 Executor、延迟和调度事件订阅；主题仍有消费者时
跳过清理，避免影响多上下文测试。只删除测试租户数据库记录会留下无法解析的旧调度
消息，导致后续测试等待全局 ACK 清零超时。重启验证期间不清理订阅。
UC-14 两场景检查公开保存、查询、实际到期和完整运行；技术测试另查定义校验、
持久化身份、应用重启及 Broker 重复消息。实际结果查看 docs/test-reports 的最新报告。

待投递事件可按 `published_at IS NULL`、`retry_at`、`attempts`、`last_error` 排查；
当前计划查看 `trigger_schedules.available_at`、`attempts`、`last_failure_at`、`last_error`。
领取行锁、命中/outbox 和下一次计划在同一数据库事务提交；连接退出会回滚并释放锁，
没有持久化 picked 或心跳租约。具体边界见 [ADR 0107](../decisions/0107-integrate-postgresql-trigger-scheduling.md)。不要手工修改
事件 ID、Execution ID 或 scheduledAt 后重投；这会破坏一次命中的身份契约。
