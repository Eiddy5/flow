# ADR 0008：分离 FlowDraft 与已部署 Flow

## 状态

Accepted（部署后保留来源、完整存储迁移和旧契约删除由 ADR 0014 补充；
`Flow.close` 与定义状态枚举已由 ADR 0018 取代，来源聚合命名和固定 `draft`
字段又由 ADR 0022 收敛为 FlowDraft 与 `deleted`）

## 背景

原有模型使用同一个 `FlowDefinition` 同时表达未发布草稿、已部署版本和关闭
版本。该模型要求未解析 YAML 在进入领域前先转换为 Task 定义，并依赖
`status + nullable version` 区分不同有效性条件。

最新领域定义要求允许草稿只保存尚未解析的原始 YAML，同时保证任何 `Flow`
对象都已经完成解析和校验。草稿没有正式业务版本，只有成功部署才生成
`reversion`；关闭表示停止使用，不产生新业务版本。

## 备选方案

### 方案一：继续使用单一 FlowDefinition

使用 `DRAFT/DEPLOYED/CLOSE` 和可空版本区分生命周期。类型较少，但一个对象
需要同时容纳原始 YAML 和解析后的定义，并允许不同状态拥有完全不同的不变量。

### 方案二：FlowDraft 继承 Flow

可以复用身份和审计字段，但未解析、可能暂时无效的来源对象无法满足 Flow
必须完整且可执行的不变量，不符合可替换关系。

### 方案三：使用独立来源聚合并在部署时映射

`FlowDraft` 保存唯一可编辑草稿，`Flow` 只保存成功部署后的完整定义。
部署边界负责解析、校验、版本计算和映射。

## 决策

采用方案三：

- `FlowDraft` 是独立的来源聚合，只保存原始 YAML 业务内容以及必要的
  身份和审计信息。
- 同一逻辑 Flow 最多存在一个 `FlowDraft`，它没有正式 `reversion`。
- `Flow` 只表示已经完成解析、校验并成功部署的完整定义。
- `FlowDraft` 与 `Flow` 是映射关系，不是继承关系。
- `FlowDraft.create()` 是草稿的普通创建入口。
- `deploy` 是产生 `Flow` 的唯一入口，同时完成解析、校验和版本计算。
- 首次成功部署生成 `reversion = 1`；后续成功部署使用当前最大值加一。
- 解析、校验或保存失败时，不产生 Flow，也不占用 `reversion`。
- `Flow.close()` 将已部署 Flow 变为 `CLOSED`，保留已有 `reversion`，
  不生成新版本。
- `FlowDefinitionStatus` 不包含 `DRAFT`；Draft 是 `FlowDraft` 的生命周期
  角色。
- Execution 使用 `flowId + flowReversion` 绑定确定的已部署 Flow。

本 ADR 替代 ADR 0001、ADR 0004 和 ADR 0005 中以下旧结论：

- 使用一个 `FlowDefinition` 同时表达 DRAFT、DEPLOYED 和 CLOSE。
- 使用 `id + null version + DRAFT` 查询草稿。
- 发布前必须单独创建 Upgrade Draft。
- 发布新版本时通过关闭旧版本表达“当前版本”切换。

其他关于业务分包、精确版本绑定、聚合隔离和领域对象复用的决策继续有效。

## 理由

- Flow 可以保持“构造完成即有效”，无需根据状态解释大量可空字段。
- 草稿可以安全保存尚未解析或暂时无效的 YAML，部署失败后仍可继续修改。
- 业务版本只对应成功部署的完整事实，版本号不会被编辑、失败或关闭动作消耗。
- 类型名称直接表达有效性边界，避免调用方把草稿当成可执行 Flow。
- 映射关系比继承关系更准确。具体 YAML Parser 保持在领域对象之外；按
  ADR 0013 解析后的通用只读映射由 Flow 在部署中直接消费。

## 后果

- 现有 `Flow`、`FlowDefinition`、Flow Command、Repository、数据库映射和
  UC-01 仍按旧模型实现，需要作为后续迁移链路统一调整。
- `CreateUpgradeDraft` 不再是独立业务能力；升级规则进入 `deploy`。
- 草稿查询与已部署 Flow 查询需要使用各自明确的 Repository 契约。
- Flow 来源、Reversion、生命周期和 FlowDraft 聚合的后续选择由 ADR 0013、
  ADR 0014、ADR 0018 和 ADR 0022 共同约束。
- 随 Flow 部署产生的完整 Task 由 ADR 0013、ADR 0021、ADR 0023 和 ADR 0024
  继续约束；Flow 从通用映射一次性构建具备完整身份和父子关系的 Task，不建立或
  持久化中间 Task 类型。当前决策链见 [`README.md`](README.md)。
- 若需要永久保留每次部署使用的原始 YAML，应另行确认版本化来源快照，
  不能把已部署 Flow 重新改为未解析来源对象。
