---
name: implement-flow-postgres-repository
description: 为 Flow Micronaut/JOOQ 项目中已有的 Core Repository 契约实现并验证 PostgreSQL 适配器。当需要新增、补全、替换或审查 `*PostgresRepository`、仓储内部的 `*Entry` 映射、聚合持久化、租户过滤、审计上下文、乐观锁、PostgreSQL 数据库迁移、JOOQ 重新生成或真实 PostgreSQL 集成测试时使用。
---

# 实现 PostgreSQL Repository

从 Core 契约向外实现。将 Repository 视为聚合持久化适配器，不要把它实现成围绕
数据表的简单 CRUD。

## 读取项目记忆

修改文件前：

1. 读取 `AGENTS.md`。
2. 读取 `docs/project-structure.md` 和
   `docs/standards/development-basics.md`。
3. 读取 `docs/standards/jooq.md` 和
   `docs/standards/DATAPILOT_USAGE.md`。
4. 如果 `docs/agents/` 下存在相关角色文档，读取该文档。
5. 检查相关 ADR、UC 规格、开发规范和验证 Harness。
6. 编写或运行 UC 测试前，同时读取 `docs/standards/uc-testing.md` 和
   `docs/agents/test.md`。

编辑前检查 `git status`。保留所有无关和预先存在的工作区修改。

## 1. 还原持久化契约

定位并读取：

- Core Repository 接口。
- 聚合根以及它拥有的所有子实体和值对象。
- 调用 Repository 的 Handler、Service 和 Query。
- 内存实现和现有测试。
- 状态迁移、生命周期和并发相关文档。

设计 SQL 前，先明确以下契约：

- 聚合身份和租户身份。
- 必须原子保存的子集合。
- 必须保持的顺序和稳定 ID。
- 查询语义和未找到时的行为。
- 新增与更新的判断规则。
- revision 或 lockVersion 规则。
- 写操作需要的审计数据。
- 事务的所有者。

不要只根据表名推断契约。如果接口与数据库 Schema 不一致，应保留领域契约，并明确
记录两者之间的差异。

## 2. 检查 PostgreSQL 是否能表达契约

检查：

- `gen/sql/production-release/flow/`
- `gen/src/main/java/org/flow/gen/flow/`
- 现有主键、外键、索引、默认值、生成列和 JSONB 类型。

确认数据库能够无损还原聚合，重点检查：

- 包含租户维度的复合键。
- 聚合不同版本之间复用的稳定子实体 ID。
- 有序子集合。
- 可空值与数据库默认值。
- 枚举和时间类型的表示。
- JSONB 数据。
- revision 或 lockVersion 字段。
- 审计字段中的字符串用户 ID。

当 Schema 无法满足契约时：

1. 在 `gen/sql/production-release/flow/` 下增加带日期的迁移。
2. 确保已有数据库能够安全升级。
3. 使约束和索引与 Repository 的查询方式保持一致。
4. 在 `docs/decisions/` 中记录重要或过渡性的数据建模决定。
5. 基于迁移后的 Schema 重新生成 JOOQ。

禁止手工修改 `gen/src/main/java/org/flow/gen/flow/` 下的生成文件。

## 3. 设计 Entry 映射

将生产适配器放在：

```text
server/src/main/java/org/cses/flow/infrastructure/repositories/
  <module>/postgres/
    XxxPostgresRepository.java
    entries/
      XxxEntry.java
```

为适配器使用的每张业务表：

- 创建 Repository 内部使用的 `XxxEntry`。
- 继承对应的 JOOQ 生成类 `XxxObject`。
- 实现 Domain 到 Entry 的转换。
- 当框架转换不能无损工作时，实现生成 Record 到 Entry 的转换。
- 实现 Entry 到 Domain 的重建。
- 将 JSONB、枚举、时间、空值、默认值和快照转换放在 Entry 中；复杂转换可以放在
  `entries` 下职责单一的 Codec 中。

使用生成的表字段和对象辅助方法：

- 插入时使用 `buildInsertMap()`。
- 更新非空、非主键字段时使用 `buildUpdateMap()`。
- 需要把字段明确更新为 `NULL` 时，使用字段级 `set(...)`。

不要让 Entry、生成 POJO、Record 或 JOOQ 类型越过基础设施适配器边界。不要在
Entry 中实现领域状态迁移。

不要假设 `fetchInto(XxxEntry.class)` 一定能转换自定义 JSON 类型。必须使用真实
PostgreSQL 验证；必要时使用显式的生成 Record 映射方法。

## 4. 实现聚合持久化

按以下顺序实现读取：

1. 使用租户身份和聚合身份查询聚合根。
2. 按 Repository 契约返回未找到结果。
3. 使用相同的租户身份和聚合身份查询所属子记录。
4. 应用确定性的排序。
5. 重建一个完整聚合。

在调用方拥有的同一事务中实现写入：

1. 将聚合根转换为 Entry。
2. 插入聚合根，或使用 compare-and-set 更新聚合根。
3. 协调聚合拥有的子记录。
4. 保持稳定子实体 ID 和集合顺序。
5. 只删除属于同一租户和同一聚合的记录。

始终使用 Repository 方法接收到的 `DSLContext`。不要在适配器内部打开、提交或
嵌套独立事务。

每一个查询、更新、删除、加锁、计数和存在性检查都必须包含租户条件。不要依赖
业务 ID 全局唯一来实现租户隔离。

按照现有配置方式将生产适配器注册为 Micronaut Bean。仅供测试的内存实现只能放在
`src/test` 下。

## 5. 执行并发控制

对带版本号的聚合使用 compare-and-set 更新：

```text
UPDATE ...
SET lock_version = next_version
WHERE company_id = tenant
  AND id = aggregate_id
  AND lock_version = expected_version
```

要求更新行数恰好为 1。更新行数为 0 时，转换为项目定义的稳定并发冲突异常。

明确区分：

- 第一次插入。
- 合法的单步版本递增。
- 使用旧版本写入。
- 同一命令事务内多次保存同一聚合。

当并发首次插入可能发生竞争时，将唯一键冲突转换为与旧版本更新相同的稳定并发冲突
抽象。不要让数据库方言相关异常穿透 Core Repository 契约。

只有在区分合法的事务内重复保存确有需要时，才使用事务作用域上下文。在事务边界
绑定并清理该上下文，不要在 HTTP Controller 中处理。

## 6. 填充审计数据

写操作从事务 `DSLContext` 的配置中读取当前 `Session`。缺少必要操作者身份时，
立即抛出含义明确的异常。

保持审计转换逻辑小而集中，并确保：

- 用户 ID 使用字符串。
- 时间统一使用 UTC。
- 更新时保留原始创建人和创建时间。
- 写入时记录当前更新人和更新时间。

只有当契约和数据表包含操作者字段时，才读取和断言操作者信息。对于只有时间字段的
表，只验证时间的创建与保留，不要虚构操作者持久化。

不要把 HTTP 层对象传入 Core Repository 契约。

## 7. 使用真实 PostgreSQL 验证

在对应的 `server/src/test/java` 包下增加聚焦测试，并根据适配器行为覆盖：

- 插入以及完整聚合往返。
- 更新并重新读取。
- 子实体顺序和稳定 ID。
- JSONB 空值、嵌套值和可空值。
- 每个查询方法的租户隔离。
- revision 或 lockVersion 成功更新与旧版本冲突。
- 支持时验证同一事务内重复保存。
- 审计操作者和时间。
- Repository 特有的过滤查询。
- 未找到时的行为。

使用真实 PostgreSQL 完成集成验证。Mock 或 H2 测试无法验证 PostgreSQL JSONB、
生成列、部分索引、复合键和 compare-and-set 行为。

只有在提供所需环境并且测试确实执行时，环境门控的集成测试才算完成验证。在任务
交付说明或对应测试报告中记录实际执行的命令和结果。

每个测试生成唯一的租户 ID，清理时只删除该租户的数据。禁止清空开发者共享表。

先运行范围最小的集成测试，再运行完整构建：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
FLOW_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/flow \
FLOW_POSTGRES_TEST_USER=flow \
FLOW_POSTGRES_TEST_PASSWORD=flow \
./gradlew :server:test --tests '*PostgresRepositoryIntegrationTest'

JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```

Schema 变化后，在编译前重新生成 JOOQ：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
./gradlew :gen:generateJooq --rerun-tasks
```

需要使用一次性数据库生成代码时，通过 `FLOW_JOOQ_JDBC_URL`、
`FLOW_JOOQ_JDBC_USER` 和 `FLOW_JOOQ_JDBC_PASSWORD` 指定数据库连接。

## 8. 更新项目记忆

只更新由本次实现产生或改变的文档：

- 可复用的强制规则放入 `docs/standards/`。
- 可重复执行的环境准备和验证命令放入 `docs/harness/`。
- 重要或过渡性的架构选择放入 `docs/decisions/`。
- 新增或变化的验收行为放入 `docs/uc/`。
- 目录职责变化时更新 `docs/project-structure.md`。

不要在 Skill 中复制项目已有文档。

## 完成门槛

满足所有适用条件后才能报告完成：

- 适配器实现了现有 Core 契约。
- 聚合往返不会丢失状态。
- 每个数据库操作都包含租户限制。
- Entry 负责所有数据库与领域之间的转换。
- Schema 与生成的 JOOQ 代码一致。
- 契约要求的并发、时间和操作者审计行为已有测试。
- 真实 PostgreSQL 集成测试通过。
- 完整 Gradle 构建通过。
- 没有覆盖任何无关的工作区修改。
