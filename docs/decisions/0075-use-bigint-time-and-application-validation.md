# ADR 0075：PostgreSQL 统一使用 bigint 时间并由应用负责业务校验

## 状态

Accepted（2026-08-28）

## 背景

项目自有 Java 类型已经统一使用 UTC Unix timestamp 毫秒值 `long/Long`，当前 Flow
PostgreSQL 基线也已经使用 `bigint` 保存时间，但 ADR 0015、开发规范和 Schema 校验
脚本仍要求 `timestamptz`，形成了互相冲突的规则。

当前基线不创建外键，领域关系由聚合、Repository 和事务保证。部分早期 ADR 仍提出
用 PostgreSQL `CHECK` 等对象重复校验状态、JSON 或生命周期规则，这会让同一业务
不变量同时存在于 Domain 和数据库中，并增加两套规则不同步的风险。

## 备选方案

### 方案一：使用 PostgreSQL 原生时间类型和数据库业务约束

可以利用 PostgreSQL 的日期运算和约束能力，但 Java、JOOQ 与数据库之间需要转换，
业务规则也会同时分散在应用和数据库中。

### 方案二：时间使用 bigint，保留数据库业务校验

时间类型能够端到端一致，但状态、JSON、关系和生命周期规则仍有两个事实来源。

### 方案三：时间统一使用 bigint，业务校验归应用所有

数据库保存应用已经确认的事实，只维护稳定存储和查询所需的结构约束；领域与应用
边界统一拥有业务合法性和跨表关系规则。

## 决策

采用方案三：

- Flow PostgreSQL 中所有时间点使用 `bigint` 保存 UTC Unix timestamp 毫秒值；
  时间点字段使用 `*_at`，时长、超时和间隔使用带明确单位的 `*_millis` 等字段，
  同样以 `bigint` 保存。
- 项目自有 Schema 不使用 `date`、`time`、`timestamp`、`timestamptz` 或 `interval`
  保存时间语义。JOOQ、Entry 和 Repository 直接使用对应的 `Long`。
- Flow 表不创建 PostgreSQL 外键。逻辑关系由关系字段、查询索引、聚合校验、租户
  条件和同一事务写入保证。
- 数据库不使用 `CHECK`、排他约束、Trigger、Rule、存储 Function/Procedure、
  自定义 Domain 或 Enum 校验业务字段、状态、JSON、时间范围、关系或生命周期。
- 继续允许 `PRIMARY KEY`、`UNIQUE`、`NOT NULL`、查询索引和纯技术默认值。它们只
  服务于行身份、幂等或唯一性、必需存储形状和访问路径，不定义业务状态机。
- 业务合法性、状态迁移、JSON 内容和跨表关系由 Domain、Service、Handler、
  Repository 的对应所有权边界保证，并使用测试验证。
- Schema 静态与真实 PostgreSQL 校验必须检查时间类型、外键和数据库业务校验对象。

本决策取代 ADR 0015 中“PostgreSQL 时间列继续使用 `timestamptz`”以及更早 ADR 中
要求通过数据库 `CHECK` 或同类对象保护业务不变量的条款。被这些条款表达的领域
不变量继续有效，只是统一由应用代码和测试保护。

## 理由

- Java、JOOQ 和 PostgreSQL 使用同一种毫秒数值，减少类型转换和精度差异。
- 领域与应用成为业务规则的唯一事实来源，避免数据库约束与代码规则分叉。
- 不创建外键后，聚合关系、租户隔离和事务边界继续由现有 Repository 协议统一保证。
- 当前基线已经符合目标形态，本决策主要消除文档和验证工具之间的矛盾。

## 后果

- PostgreSQL 不提供原生日期时间运算；需要展示或日期计算时在应用或查询边界按 UTC
  毫秒转换。
- 数据库不会独立拒绝孤立关系、非法状态或错误 JSON；写入路径必须完成应用校验，
  Repository 和集成测试必须覆盖相关不变量与原子性。
- Schema 可以继续使用主键、唯一性、非空和索引保护存储身份与查询路径。
- 当前表结构不需要迁移；规范、项目 Schema Skill 和验证脚本需要同步更新。
