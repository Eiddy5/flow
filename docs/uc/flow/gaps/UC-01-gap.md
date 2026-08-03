# UC-01 项目能力缺口

## 对应 UC

- UC：`UC-01 Flow 定义生命周期`
- 文档：[UC-01 Flow 草稿生命周期与多租户管理.md](../UC-01%20Flow%20草稿生命周期与多租户管理.md)
- 涉及范围：目标领域模型迁移，以及旧 S7 并发写协议

## 历史目标模型缺口（已关闭）

目标模型已经改为：

- `FlowDraft` 单独保存未解析原始 YAML，没有 `reversion`。
- `Flow` 只表示解析、校验并部署成功的完整定义。
- `deploy` 同时负责解析、校验和 `reversion` 递增。
- `FlowDraft` 与 `Flow` 由类型表达草稿和正式定义角色，不保存 `draft`；
  两者新建时均为 `deleted=false`。
- `delete` 不产生新版本，且删除最大 Reversion 后不能回退旧 Reversion。
- 定义生命周期不使用 `FlowDefinitionStatus` 或其他状态枚举。

迁移前的生产代码使用一个 `Flow` 聚合持有 Draft 和版本集合，并存在
`FlowDefinition`、`createUpgradeDraft()`、`PublishFlowCommand` 和
`id + version + status` 查询契约。当时 `Uc01FlowLifecycleTest` 也只验证旧
模型，因此历史 PASS 结果不能判定新模型通过。

上述模型、调用链、Schema 和测试现已完成迁移，以下内容作为缺口形成时的历史
证据保留。

## 历史风险（已消除）

- 未解析来源和有效 Flow 仍可能被调用方视为同一种对象。
- 编辑草稿时会提前解析 YAML，无法满足“部署时才解析”的规则。
- 升级仍依赖单独的 Upgrade Draft 动作。
- 发布新版本仍会把旧版本改为 CLOSE，与 close 表达整个 Flow 删除语义冲突。
- 历史测试成功可能掩盖目标领域模型尚未实现。

## 已落地的最小能力

- 独立 `FlowDraft` 聚合及 Repository。
- 只接收完整解析结果的 `Flow.deploy` 创建入口。
- 原子完成解析、校验、`reversion` 计算和 Flow 保存的部署命令。
- 不产生新 `reversion`、只允许 `deleted=false → true` 的逻辑删除行为。
- 按 `id + reversion` 查询 Flow、按 `id` 查询来源草稿的独立契约。
- 重写 UC-01 场景和唯一主测试类，覆盖新模型的正向、反向和变异场景。

## UC 目的

保护 Flow 稳定身份、DRAFT 完整性、版本历史和租户隔离，防止并发编辑或发布时
静默覆盖已经提交的定义。

## 目标业务与安全场景

两个调用方读取同一 DRAFT 后分别保存不同修改时，系统必须检测陈旧写入或使用
明确的版本协议，不能让后提交者在无提示的情况下覆盖先提交者。

## 缺口形成时的项目能力

- `Flow` 聚合负责 DRAFT、发布、升级和关闭状态转换。
- `FlowQueryHandler` 按 companyId、id、version 和 status 精确查询。
- `InMemoryFlowRepository` 以 companyId + flowId 隔离并返回聚合副本。
- `Flow` 和 Task 技术 id 使用 `StringUtil.newId()` 生成。

## 原不满足项与证据（已修复）

- `Flow` 聚合没有可用于陈旧写检测的 lockVersion 或等价修改令牌。
- `FlowRepository.save` 不接收 expected version，也没有冲突结果。
- `InMemoryFlowRepository.save` 对 `flows` 与 `flowIds` 直接执行无条件 `put`；
  同 key 并发创建还可能让 key 索引与实际聚合集合产生不一致。
- `Uc01FlowLifecycleTest` 已覆盖 S1～S6，但尚未覆盖需要并发协议的 S7。

## 风险

- 并发编辑可能丢失已经提交的 DRAFT 内容。
- 并发创建同 key Flow 时，key 查询结果可能只指向其中一个聚合。
- 用户无法区分“保存成功”与“覆盖了他人的更新”。

## 已满足的最小能力

- 为 Flow 修改定义可比较的并发令牌或等价冲突协议。
- Repository 保存时原子校验旧状态，并明确返回或抛出冲突。
- 同一 companyId + key 的唯一性必须与聚合保存原子一致。
- 对应主测试类补齐 UC-01 全部场景和 PASS 映射。

## 当前状态与后续角色

- 目标模型迁移状态：`RESOLVED`
- 历史 S7 缺口状态：`RESOLVED`
- 最终处理结论：以 `FlowDraft.lockVersion` 保护来源编辑，以
  `Flow.id + reversion` 保存不可变部署快照；部署时才解析 YAML，部署后保留
  来源，删除逻辑 Flow 时在同一事务标记最新 Flow 与来源为 `deleted=true`。
  生产 PostgreSQL Repository 和测试内存
  Repository 都拒绝陈旧写入。
- 复验证据：`Uc01FlowLifecycleTest` 按当前 ACCEPTED UC 覆盖 S1～S13，包括
  无效 YAML、部署回滚、多租户、陈旧写、并发部署、来源保留和删除语义。
- UC Agent：保留 S7 和通过规范，不把静态证据写成运行 FAIL。
- 开发角色：已完成领域、命令、仓储、Schema、JOOQ、UC 和测试的完整迁移。
- Test Agent：旧模型复验报告继续保留；本轮结果写入新的历史测试报告，不覆盖
  旧报告。
