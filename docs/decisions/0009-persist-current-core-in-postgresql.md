# ADR 0009：为当前 Core 提供 PostgreSQL 聚合持久化

## 状态

Accepted（ExternalTask 持久化条款由 ADR 0016 取代，并已于 2026-08-12 删除；
其余聚合持久化条款继续有效）

## 背景

Flow Core 已定义 `FlowRepository`、`ExecutionRepository` 和
`ExternalTaskRepository`，但生产代码只有测试专用内存实现。当前 PostgreSQL
脚本也无法无损重建现有领域对象：

- `executions` 缺少聚合的 `lockVersion`。
- `flows` 缺少 Flow 描述。
- `flow_tasks` 缺少 inputs、outputs 和类型扩展 properties。
- `flow_tasks` 的旧主键不允许相同 Task id 跨 Flow version 复用。

ADR 0008 已确定 Flow 定义域后续迁移到 `FlowDraft + Flow Reversion`。
本决策不替代该目标模型，只解决迁移完成前当前 Core 无生产 Repository 的问题。

## 决策

- 在 `infrastructure/repositories/<业务模块>/postgres` 实现三个现有 Repository
  端口，全部复用调用链传入的 `DSLContext`。
- 每张业务表通过对应 `XxxEntry` 集中完成领域对象双向转换，并使用生成对象的
  `buildInsertMap()`、`buildUpdateMap()`。
- `flows` 增加 `description`；`flow_tasks` 增加 JSONB 的 inputs、outputs 和
  properties，并把主键调整为 `company_id + flow_id + flow_version + id`。
- `flow_drafts.content` 暂存当前 Draft 的结构化 JSON 快照。当前 Service 在进入
  Repository 前已经丢失原始 YAML，因此不能伪造原始来源；完成 ADR 0008 迁移
  时由 `FlowDraft.raw` 替换该过渡格式。
- `executions` 增加 `lock_version`。首次保存为 0，修改已有聚合时必须恰好加一。
  同一命令事务内允许同一版本多次 flush，以支持 Worker 外键记录的中间保存。
- 本决策实施时，`external_tasks.lock_version` 使用严格 compare-and-set；该过渡
  Repository 与表已按 ADR 0016 删除，不再属于当前持久化边界。
- 写操作从当前 `DSLContext` 的事务数据读取 Session，生成 creator/updater 审计；
  `CommandExecutor` 负责在命令事务期间绑定并在结束后恢复 Session。
- 审计检索列使用 varchar，与 PAAS Session 的 String 用户 id 契约一致，不在
  PostgreSQL 生成列中强制转换为 bigint。
- 所有查询、更新和删除均包含 companyId 或所属聚合身份条件。

## 理由

该方案能在不让 Core 依赖 JOOQ 类型的前提下无损恢复当前聚合，并保留命令事务、
租户隔离、Task 顺序和乐观锁语义。显式记录过渡格式可以避免把当前适配误认为
ADR 0008 的最终存储模型。

## 后果

- 数据库脚本和 JOOQ 生成代码必须同步更新。
- PostgreSQL Repository 的集成测试需要真实 PostgreSQL，以验证 JSONB、复合键、
  部分唯一索引和 CAS。
- 完成 ADR 0008 的领域迁移时，需要同时替换 Flow Repository 契约、Draft 内容
  格式和 `version` 术语。
- ExternalTask Repository、Entry、JOOQ 类型与表已经删除。PAUSE 等待只由
  ExecutionRepository 持久化的 Pause TaskRun 表达，Execution Resume 不依赖额外
  等待聚合。
