# ADR 0034：将 State 作为单一 JSONB 值对象持久化

## 状态

Accepted

## 背景

Execution 与 TaskRun 在领域中持有同一个不可分割的 `State` 值对象。该对象由
`current` 和从创建开始的有序 `history` 共同构成，二者必须始终一起保存和恢复。

原表结构把当前状态保存为 `status varchar`，把历史保存为
`state_history jsonb`。这种拆分让一个领域值对象拥有两个数据库事实来源，需要额外
约束维持末条历史与当前值相等，也让 Repository 在读写时重新拼装对象。

当前仍处于允许丢弃旧数据的开发阶段，因此本决策只定义目标 Schema，不设计旧列
升级、数据回填或兼容读取。

## 备选方案

### 方案一：继续拆分当前状态与历史

当前状态可以直接使用标量索引，但领域值对象被拆成两个持久化字段，写入和恢复必须
长期维护跨列一致性。

### 方案二：使用一个 State JSONB 对象

在一列中保存 `current + history`，Repository 对整个值对象做一次编解码；需要按
当前状态过滤时使用 JSONB 表达式索引。

### 方案三：当前状态保留在主表，历史拆成事件表

能够独立查询每次状态变化，但 History 没有独立身份和生命周期，当前也没有按历史
事件扫描、归档或报表查询的需求。该方案会增加表、事务写入和聚合恢复成本。

## 决策

采用方案二。

`executions` 和 `task_run` 各自只保存一个非空 `state jsonb` 字段，JSON 结构直接
对应领域 `State`：

```json
{
  "current": "RUNNING",
  "history": [
    {"state": "CREATED", "date": 1785916800000},
    {"state": "RUNNING", "date": 1785916801000}
  ]
}
```

- 删除两张表原有的 `status` 和 `state_history` 字段，不保留兼容字段。
- `current` 是当前状态的唯一事实来源，`history` 是同一个 State 内的完整有序轨迹。
- 数据库检查 State 必须是对象，必须包含字符串 `current` 和非空数组 `history`；
  History 首项必须是 `CREATED`、末项必须等于 `current`，每项必须包含合法状态和
  非负数值 `date`。
- Execution 与 TaskRun 各自允许的状态集合仍由所属聚合决定；完整迁移路线继续由
  `State.rehydrate(...)` 及聚合重建规则校验。
- 当前状态查询分别使用
  `(company_id, (state ->> 'current'))` 和
  `(execution_id, (state ->> 'current'))` 表达式索引，不增加冗余生成列。
- Entry 使用一个专用 Codec 在完整 `State` 与 JSONB 之间双向转换，不在
  Repository 中分别读写 current 和 history。
- `external_task.status` 属于 ExternalTask 自身的遗留生命周期，不是统一
  `State`，不受本决策影响。
- 按 ADR 0033 直接修改唯一开发期建表基线；已有开发数据库必须重建。

本决策修订 ADR 0017 的“持久化”字段结构，其 State 值对象、History 语义及领域
状态迁移原则继续有效。

## 理由

- State 没有独立于拥有者的身份，`current` 与 `history` 共同构成一个值，单列存储
  与领域边界一致。
- JSONB 是有依据的第三范式偏离：该值由聚合整体写入和恢复，没有独立编辑需求；
  唯一事实来源就是 `state`，不存在需要同步的冗余列。
- 表达式索引保留常用当前状态过滤能力，无需为了查询重新引入一个重复的 status。
- 数据库负责可静态验证的 JSON 形状和值域，领域层继续负责完整状态迁移不变量。

## 后果

- Repository 往返能够以一个字段无损恢复完整 State。
- SQL 查询当前状态必须使用 `state ->> 'current'`，不能再引用
  `executions.status`、`task_run.status` 或 `state_history`。
- 如果未来出现大量按 History 事件查询、归档或分析的需求，需要重新评估独立事件表
  或专用读模型，不能在主表增加第二个当前状态事实来源。
- 本次不提供旧 Schema 或旧数据迁移；开发环境重建数据库并重新生成 JOOQ。
