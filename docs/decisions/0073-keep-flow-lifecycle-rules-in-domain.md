# ADR 0073：Flow 生命周期规则由领域拥有，Repository 仅负责持久化

## 状态

Accepted（2026-08-27）

ADR 0083 取代本 ADR 中由 Domain 计算版本、按 Flow 行 ID 更新以及 Repository 不读取
版本历史的条款。删除限制、定义校验、Task 身份和审计事实仍由领域与 Handler 负责。

## 背景

Flow 的 PostgreSQL Repository 曾在保存时读取已有版本并判断版本递增、删除状态、
正式定义不可变和审计状态变化。这样会让持久化 Adapter 同时承担 Flow 生命周期的
业务决策，也使保存一个已经由领域完成决策的 Flow 仍需要 Repository 重新解释其
业务状态。

## 决策

Flow 的生命周期规则由领域和用例 Handler 负责，`FlowRepository` 只负责查询、聚合
恢复和持久化已经准备好的领域状态。

具体规则如下：

1. `Flow.deploy(...)` 或 `Flow.initialize(...)` 根据调用方加载的最新 Flow 计算
   `latest + 1`。
2. 领域发布流程在最新 Flow 已删除时拒绝创建新版本。
3. 已部署 Flow 没有修改定义和 Task 的领域行为；新定义通过创建新的正式版本保存。
4. 已存在的正式 Flow 只通过领域审计状态/删除行为产生可保存的变更，保存时不改变
   定义和 Task。

Repository 的 `save` 只按租户和 Flow 实体 ID 判断数据库行是否存在：

- 已有行只更新 `flows`；
- 新行插入 `flows`；
- 新插入的正式 Flow 同时插入对应的 `flow_tasks` 快照；
- 后续 Flow 保存不更新、替换或删除 `flow_tasks`。

PostgreSQL 的主键、租户范围和唯一索引仍然保留，用于结构完整性和并发竞争的最后
保护，但不通过触发器、存储过程或 Repository 校验实现上述生命周期规则。数据库
冲突由 Adapter 转换为稳定的持久化冲突异常。

## 理由

领域对象是 Flow 身份、版本、定义、Task 和审计状态的唯一业务事实来源。Repository
因此可以保持较窄且稳定的持久化接口，聚焦 Entry 映射、租户条件、事务内聚合装配和
数据库异常转换；未来更换数据库实现时不需要复制一套 Flow 生命周期规则。

## 后果

- `FlowRepositoryImpl` 不再读取已有 Flow 以进行业务比对，也不再判断版本、删除状态、
  定义相等性或审计状态变化。
- Handler 必须先查询所需的最新 Flow，再调用领域创建或状态行为，最后调用一次
  Repository `save`。
- `flow_tasks` 成为正式 Flow 版本的追加式定义快照；Task 不需要独立更新流程。
- 直接绕过领域行为构造或修改 Flow 的代码不属于合法调用方，领域测试负责保护公开
  行为；数据库只提供结构性约束和持久化事实。
