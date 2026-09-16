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

应用正常启动后扫描；`enabled: false` 关闭本机调度和触发事件消费者，已保存的
outbox 仍保留。此开关不是全局暂停，多实例中其他启用节点仍可工作。

## 数据库

全新开发数据库先应用 `gen/sql/flow/001_create_flow_tables.sql`，再应用
`scheduler/sql/scheduled_tasks.sql`。沿用 `docs/harness/postgresql-repositories.md`
中的数据库连接与生成步骤；上游 scheduled_tasks 被 JOOQ 生成器排除。

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

## 验证

JDK 使用当前构建要求的 25，通过 Gradle Wrapper：

```bash
./gradlew :scheduler:test
./gradlew test
```

测试必须明确设置 `FLOW_POSTGRES_TEST_URL`、`FLOW_POSTGRES_TEST_USER`、
`FLOW_POSTGRES_TEST_PASSWORD`、`FLOW_PULSAR_TEST_URL` 和 `FLOW_PULSAR_TEST_ADMIN_URL`，
指向隔离 PostgreSQL/Pulsar，不能指向业务数据。
UC-14 两场景检查公开保存、查询、实际到期和完整运行；技术测试另查定义校验、
持久化身份、应用重启及 Broker 重复消息。实际结果查看 docs/test-reports 的最新报告。

待投递事件可按 `published_at IS NULL`、`retry_at`、`attempts`、`last_error` 排查；
上游计划查看 picked、last_heartbeat、last_failure、consecutive_failures。不要手工修改
事件 ID、Execution ID 或 scheduledAt 后重投；这会破坏一次命中的身份契约。
