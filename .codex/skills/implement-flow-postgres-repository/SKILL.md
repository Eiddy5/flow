---
name: implement-flow-postgres-repository
description: 为 Flow 项目中已有的 Core Repository 契约实现、修改或审查 PostgreSQL/JOOQ 适配器。适用于 Repository 实现、聚合持久化、XxxEntry 与领域转换、同级 Codec、租户与唯一性条件、开发期 Schema 基线、JOOQ 重新生成以及真实 PostgreSQL 集成测试。
---

# 实现 Flow PostgreSQL Repository

从 Core Repository 契约和聚合所有权出发实现数据库适配器。表结构、JOOQ 生成类和
Entry 都是基础设施表示，不能反向定义领域模型。

## 必读项目记忆

修改前依次读取：

1. `AGENTS.md`。
2. `docs/project-structure.md` 和 `docs/standards/development.md`。
3. `docs/standards/jooq.md`、`docs/standards/postgresql-schema.md` 和
   `docs/standards/command-executor.md`。
4. 涉及 JSON/JSONB 时读取 `docs/standards/json.md`。
5. 目标聚合对应的 ADR、UC、Repository 契约、领域对象和现有测试。
6. `docs/harness/postgresql-repositories.md`。

编写或运行 UC 测试前，再读取 `docs/standards/uc-testing.md` 和
`docs/agents/test.md`。编辑前检查 `git status`，保留无关修改。

## 工作流程

### 1. 还原持久化契约

确认：

- 聚合身份、业务唯一键和租户边界。
- 聚合根拥有的子集合、稳定 ID 和顺序。
- 每个查询的结果基数和未找到语义。
- 调用方拥有的事务边界。
- 领域已经产生、需要原样持久化的状态和审计事实。
- 由 Schema 与 Repository 负责的唯一性、行锁、CAS 或事务隔离协议。

不要根据表名、历史 Schema 或旧实现反推领域契约。发现冲突时，以当前已接受的 ADR
和 Core 契约为准，并修正基础设施表示。

### 2. 检查 Schema 与生成代码

检查：

- `gen/sql/flow/001_create_flow_tables.sql`
- `gen/sql/flow/tables/<table_name>.sql`
- `gen/src/main/java/org/flow/gen/flow/`

Schema 变更直接修改开发期基线，并完整遵守
`docs/standards/postgresql-schema.md`。修改后重建开发数据库、运行 Schema 校验并重新
生成 JOOQ。禁止手工修改 `gen/src/main/java/org/flow/gen/flow/`。

### 3. 实现 Adapter、Entry 与 Codec

生产实现位于：

```text
core/src/main/java/org/cses/flow/infrastructure/repositories/
  <business>/
    XxxRepositoryImpl.java
    entries/
      XxxEntry.java
    codec/
      XxxCodec.java
```

严格按 `docs/standards/jooq.md` 实现：

- 完整表行直接映射到继承生成 `XxxObject` 的 `XxxEntry`。
- Domain 与 Entry 的双向转换只位于 Entry。
- 序列化和专用字段转换只位于与 `entries` 平级的 `codec`。
- Repository 不复制字段转换，不把 Entry、Record 或生成类型暴露给 Core。
- 插入、更新、批量写入和查询映射使用规范指定的 JOOQ 形式。

### 4. 实现聚合读写

读取时先按租户和业务身份取得根 Entry，再按相同边界读取、排序并恢复子 Entry，最后
装配完整聚合。写入时使用方法收到的 `DSLContext`，在调用方事务内保存根与所属子集合。

每个查询、更新和删除都必须包含租户以及主键或业务身份。业务唯一键由 PostgreSQL
主键或唯一索引保护；竞争冲突由 Repository 转换为 Core 能理解的稳定异常。只有对应
ADR 已经确认时才增加行锁、CAS 或特定隔离协议。

### 5. 验证

至少覆盖：

- Domain、Entry 与数据库的完整往返。
- JSONB、空值、默认值和异常持久化数据。
- 子集合顺序、稳定 ID 和聚合装配。
- 每个查询与写入的租户隔离。
- 业务唯一键冲突以及已确认的并发协议。
- 领域审计事实原样往返，Adapter 不生成领域事实。
- Schema 与当前 JOOQ 生成代码一致。

先运行受影响的单元测试和架构测试，再按
`docs/harness/postgresql-repositories.md` 使用真实 PostgreSQL 运行 Repository 集成
测试，最后运行 `./gradlew build`。未实际执行的环境门控测试不能报告为通过。

## 完成门槛

- 实现满足当前 Core Repository 契约和对应 ADR。
- 目录、Entry/Codec 边界及 JOOQ 调用符合 `jooq.md`。
- Schema 基线符合 `postgresql-schema.md`，生成代码未被手工修改。
- 聚合往返、租户边界、唯一性和适用的并发协议均有验证。
- 相关测试与构建通过，未覆盖无关工作区修改。
