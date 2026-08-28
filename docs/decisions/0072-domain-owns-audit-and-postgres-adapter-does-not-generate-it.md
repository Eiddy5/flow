# ADR 0072：审计事实由领域拥有，PostgreSQL Adapter 不生成审计

## 状态

Accepted

## 背景

PostgreSQL Repository 曾通过 `PostgresAudit` 从 JOOQ 配置上下文读取当前 Session，
并在数据库写入前生成当前时间、操作者 JSON 以及 Execution、TaskRun 的更新审计
字段。这让数据库适配层拥有了领域事实的生成责任，也使表结构出现了领域对象没有的
独立审计字段。

## 决策

- 审计事实由领域对象在创建或状态变化时产生；`XxxEntry.from(...)` 只负责把
  已存在的领域字段映射为数据库字段。
- PostgreSQL Adapter 不读取 `DSLContext` 配置中的 Session，不调用当前时间生成
  审计值，不拼装操作者 JSON，并不提供通用 `PostgresAudit` 类。
- 只有领域对象实际拥有的审计字段才进入表结构：Flow 的完整审计状态继续由
  `FlowEntry` 持久化，Execution 只保存 `BaseDomain` 的 `creator` 与 `createdAt`，
  TaskRun 不保存独立审计字段。
- `task_runs` 中原有的 `start_at`、`end_at` 也不再单独保存；TaskRun 的状态历史
  已是领域运行事实。Queue 的 `created_at` 只用于数据库消息顺序，属于 Queue
  Adapter 的基础设施字段，不是审计字段。
- 数据库不通过默认值、生成列或索引生成、复制或改变领域审计事实。开发期 Schema
  变化时重建基线并重新生成 JOOQ，不为旧基线增加迁移脚本。
- `executions.lock_version` 是并发基础设施字段，不是审计字段，Entry 不将它映射到
  领域对象。

## 理由

领域是业务事实和生命周期的唯一来源，Repository 只承担持久化边界、租户条件、
数据库查询和并发/排序等基础设施协议。这样可以避免 Session、系统时间和业务审计
规则在不同 Adapter 中重复实现，也能让表字段与领域模型保持一致。

## 本次影响

- 删除 `PostgresAudit` 及 Execution Repository 对它的调用。
- `executions` 删除 `updater`、`deleter`、`updated_at`、`deleted_at`，并由 Entry
  直接写入领域的 `creator`、`created_at`。
- `task_runs` 删除 `start_at`、`end_at`、`created_at`、`updated_at`、`deleted_at`。
- `flows` 删除从操作者 JSON 派生的 `creator_id`、`updater_id`、`deleter_id` 及其
  索引；`creator`、`updater`、`deleter`、状态和时间仍由 Flow 领域提供。
- 重新生成 `gen` 下的 JOOQ 类，并通过 Repository 直接 `fetchOneInto`/
  `fetchInto` 映射 Entry。

## 验证

- 架构测试检查 PostgreSQL Repository 不再生成 Session、当前时间或操作者审计值。
- Repository 集成测试检查 Execution、TaskRun 和 Flow 的非领域审计列不存在，并
  验证领域对象往返持久化。
