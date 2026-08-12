# ADR 0005：统一领域模型并按业务模块组织 Core

## 状态

Accepted（单一 `FlowDefinition` 生命周期模型由 ADR 0008 修订，并由
ADR 0014 的 `FlowDraft + Flow Reversion` 模型取代；ExternalTask 目标
领域边界由 ADR 0016 移除）

## 背景

Flow 生命周期曾同时使用 `FlowDraft`、`FlowVersion`、`FlowSnapshot`、
`FlowReference` 和 `TaskSnapshot`。Execution 查询也建立了对应 Snapshot。
这些类型复制了既有领域对象的数据，使同一个概念存在多套结构，并且让
`core/domains` 混入查询包装和只读映射对象。

同时，Command、Handler、Service、Query 和 Repository 曾直接平铺在技术
目录中，随着 Flow、Execution 和 ExternalTask 链路增加，业务边界难以识别。

## 备选方案

### 方案一：保留领域对象与 Snapshot/Reference 双模型

可以为每层建立独立数据结构，但会产生重复字段、转换代码和一致性维护成本。
当前 Core 没有跨进程查询契约需要这类复制模型。

### 方案二：查询直接暴露仓储中的可变聚合实例

类型最少，但调用方可能绕过领域方法修改仓储状态，破坏充血模型边界。

### 方案三：复用领域对象，由仓储返回隔离副本

领域概念保持唯一，仓储通过复制聚合保证读取隔离，Service 和 Query 不再维护
Snapshot 映射。

## 决策

采用方案三，并同时执行以下约束：

- `core/domains` 只放领域对象和领域状态枚举。
- `FlowDefinition` 是 DRAFT、DEPLOYED 和 CLOSE 定义的唯一领域对象；
  生命周期差异由 `status + version` 表达。
- FlowDefinition 直接持有不可变 Task；Execution 和 TaskRun 查询直接复用
  领域对象。
- 删除 Snapshot、Reference 及与领域对象重复的生命周期类型。
- Repository 保存和返回聚合副本，调用方必须通过领域方法改变状态。
- Core 的技术目录使用 `flows`、`executions` 和必要的 `shared` 业务子包；
  ADR 0016 已移除过渡性的 `externaltasks` 业务子包。
- 技术 ID 统一为 `String`，由 `StringUtil.newId()` 生成；领域对象使用
  `class` 保护构造和行为。

本 ADR 当时对 Java `record` 的全面限制由
[`ADR 0025`](0025-allow-records-for-simple-boundary-contracts.md) 修订：Domain、
持久化映射和需要框架代理的类型继续使用 `class`，简单不可变边界协议可以选择
`record`。

## 理由

该方案以更少的公开概念保持读取隔离，减少转换层和重复定义，同时让目录结构
直接表达业务边界。新增字段或规则只需在唯一领域对象中维护。

## 后果

- Controller 或未来远程 API 如需传输模型，应在 Core 外建立 DTO，不能放入
  `core/domains`。
- JOOQ Record 和数据库映射对象应留在具体 Repository Adapter 内部。
- 若未来读模型与领域对象在性能或结构上确实不同，可以在 Query Adapter 中
  引入明确命名的 Projection，但不得伪装成领域对象或 Snapshot 副本。
- 后续修改已有 Core 链路时，必须同步迁移该链路的业务子包，不能重新平铺。
