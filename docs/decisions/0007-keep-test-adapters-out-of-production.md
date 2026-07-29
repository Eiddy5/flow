# ADR 0007：生产代码只保留真实基础设施适配

## 状态

Accepted

## 背景

第一阶段为了验证 Flow Core，将内存 Repository、内存事务、无连接 JOOQ
Factory、Session Binder 和 WebSocket Repository 放进了生产源码。这些类型的
实际用途是支持测试和无外部依赖的本地验证，并不是 Flow 的生产基础设施。

同时，Flow 定义的唯一公开形式已经确定为 YAML。YAML 解析不是可替换的外部输入
适配器，而是 Flow Core 定义契约的一部分。

## 备选方案

### 方案一：继续通过配置切换生产内存模式

优点是应用可以不连接 PostgreSQL 启动。缺点是生产包长期携带两套存储和事务
语义，还需要维护 Session、WebSocket 等 PAAS 能力的空实现。

### 方案二：生产代码只保留真实集成，内存实现仅作为测试支架

测试仍能独立验证 Core 行为；生产运行则明确依赖 PostgreSQL Repository 和 PAAS
已经提供的基础能力，不再存在误启用内存模式的风险。

## 决策

采用方案二。

- `src/main` 删除内存 JOOQ Factory、内存事务、内存 Repository、内存 Session
  Binder 和 WebSocket Repository。
- 当前 UC 测试仍需要的内存 Repository、事务、JOOQ 和 Session 支架移入
  `src/test`，不得成为生产 Bean。
- 生产 WebSocket 直接使用 PAAS 已集成的能力，不维护 Flow 专用空 Repository；
  无数据库的装配测试可以在 `src/test` 提供替代 Bean。
- 生产 `infrastructure` 当前只保留 DataPilot/PostgreSQL 接线；真实
  PostgreSQL Repository 在后续存储实现中补充。
- YAML 是 Flow 定义契约，不是外围可选格式；具体解析能力按 ADR 0013 放入
  扁平的 `core/serializers/YamlParser`。
- `CommandExecutor` 当前仍使用 PAAS JOOQ 提供的真实 PostgreSQL 事务边界；本
  决策删除的是无数据源的内存 JOOQ 替代 Bean，不是数据库事务接口。

## 理由

生产源码应准确表达将要部署的运行方式。测试替身留在测试源码可以继续保护已有
UC，同时避免内存存储、伪事务和空外部服务实现进入生产依赖注入图。把 YAML
Parser 放入 Core，则让代码位置与“Flow 只通过 YAML 定义”的产品契约一致。

## 后果

- PostgreSQL Repository 完成前，生产应用不保证能够独立完成 Core Bean 装配。
- UC 测试继续使用测试源码中的内存实现，其结果只验证领域及事务可观察语义，
  不代表 PostgreSQL 映射已经完成。
- PostgreSQL Repository 必须复用既有 Repository 接口和命令事务边界，不得让
 领域模型依赖 DataPilot Record 或数据库表对象。
- ADR 0001、0002 和 0006 中的“第一阶段内存实现”此后均解释为测试实现，不再
 解释为生产运行模式。
