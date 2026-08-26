# ADR 0070：使用 FlowId 统一 Flow Repository 业务选择器

## 状态

Accepted（2026-08-24）

## 背景

Flow Repository 同时需要按数据库技术 ID、稳定业务 key、正式版本和草稿状态查询。
原接口将 `companyId`、`key`、`version` 分散在多个方法参数中，也保留了仅为加锁而
存在的重复读取方法，导致 Flow 创建、恢复、查询和部署链路难以保持同一套选择语义。

## 决策

引入只表达查询条件的 `FlowId` record：

- `companyId` 和 `key` 必填；`version` 可空且必须为正数。
- `version` 存在时表示一个精确的正式 Flow Reversion。
- `version` 为空时表示一个逻辑 Flow，用于选择最新正式版本或唯一草稿。
- `FlowId` 是 Repository 业务选择器，不是 Flow 的实体 ID；`Flow.id` 仍是数据库技术行
  的稳定字符串身份，Task 和 Execution 仍使用真实的 `companyId/key/version` 绑定。

`FlowRepository` 只保留以下六个方法：

1. `findById`：按数据库 `(companyId, id)` 恢复精确持久化行；
2. `findByFlowId`：按完整 `FlowId` 恢复精确正式版本；
3. `findLatestByFlowId`：按无 version 的 `FlowId` 选择最大正式版本，已删除的最大版本
   也必须返回，避免回退到旧版本；
4. `findDrafts`：按租户读取未删除草稿；
5. `findDraftByFlowId`：按无 version 的 `FlowId` 读取唯一未删除草稿；
6. `save`：统一保存草稿和正式 Flow。

精确正式版本查询和最新正式版本查询保留生命周期事实；普通查询服务在向外返回最新
版本前自行过滤已删除对象。草稿查询在 Repository 层过滤已删除对象。并发写入和草稿
乐观锁继续由 `save` 负责，不为单独读取再暴露 `lockById` 或 `lockDraftByFlowId`。

## 理由

把稳定业务选择器集中为一个窄值类型后，调用方只需区分“精确版本”和“逻辑 Flow”两
种构造方式；Repository 方法名与查询语义保持一一对应，同时不会把业务选择器误认为
实体身份。

## 后果

- Flow 领域、Service、Executor 和 PostgreSQL Adapter 共用统一的查询契约。
- 删除旧的 `findByKey`、`findLatestByKey`、`findDraftByKey` 以及仅为读取加锁的 Flow
  方法，调用方不再维护重复的参数顺序。
- 旧 ADR 中“不建立 FlowId 包装值”的局部条款由本 ADR 修订；实体 ID 规则不变。
- `version == null` 的含义必须由调用方通过 `FlowId.from(companyId, key)` 明确表达，
  不能把 `null` 版本传给精确查询。
