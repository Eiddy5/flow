# PostgreSQL Schema 基线规范

## 适用范围

本规范约束 Flow 开发期 PostgreSQL 基线中的表命名、文件隔离和完整入口。具体领域
关系、字段、状态与索引选择由对应 ADR 决定；JOOQ 生成与 Entry 边界由
[`jooq.md`](jooq.md) 约束。

## 单一基线入口

Flow Schema 使用一个显式入口和按表隔离的脚本：

```text
gen/sql/flow/
├── 001_create_flow_tables.sql
└── tables/
    └── <table_name>.sql
```

- `001_create_flow_tables.sql` 是人工执行、验证和运行手册引用的唯一完整入口。
- 入口通过 psql `\ir tables/<table_name>.sql` 显式声明执行顺序，并用
  `BEGIN` / `COMMIT` 保证空库建表原子性。
- 入口不直接包含 `CREATE TABLE`，也不通过目录遍历、通配符或文件名排序自动发现
  脚本。
- `tables/<table_name>.sql` 恰好定义一张表，并同时拥有该表的约束和索引；一个文件
  不修改或创建其他表。
- 表级文件名必须与 `CREATE TABLE` 使用的表名完全一致。

## 表名

- 表名统一使用小写 `snake_case`。
- 表名的最后一个单词使用复数形式。为了让静态校验确定可重复，Flow 表名的最后
  一个单词必须以 `s` 结尾；不使用 `people` 等不以 `s` 结尾的不规则复数。
- 约束和索引名称中的表名前缀使用相同的完整复数表名，例如
  `pk_task_runs`、`idx_task_runs_execution`。
- 字段名表达单行引用时仍使用单数，例如 `task_run_id` 指向一个 TaskRun，不随表名
  机械复数化。

## 时间字段

- PostgreSQL 中所有表达时间点的字段统一使用 `bigint`，值为 UTC Unix timestamp
  毫秒数。该规则同时适用于领域审计时间、生命周期时间和 Queue 排序时间等技术时间。
- 时间点字段统一使用 `*_at` 命名；时长、超时和间隔统一使用 `bigint` 毫秒值，并在
  字段名中包含 `*_millis` 等明确单位。
- 项目自有 Schema 不使用 `date`、`time`、`timestamp`、`timestamptz` 或 `interval`
  保存时间语义。可空时间使用可空 `bigint`；必填时间使用 `bigint NOT NULL`。
- 时间由领域、应用或基础设施中的实际事实所有者生成。数据库可以为自身拥有的纯技术
  时间提供 `bigint` 默认值，但不得生成或改变领域审计时间。

## 约束与校验边界

- 表结构不得创建 `FOREIGN KEY` 或 `REFERENCES`。跨表关系只保留关系字段和查询索引，
  由领域聚合、应用校验、Repository 租户条件及同一事务写入保证。
- 数据库不承担业务合法性校验。不得使用 `CHECK`、排他约束、Trigger、Rule、存储
  Function/Procedure、自定义 Domain 或 Enum 等数据库对象校验状态值、JSON 结构、
  时间范围、父子关系、租户归属或生命周期规则。
- 业务字段、状态转换、JSON 内容和跨表关系必须在 Domain、Service、Handler、
  Repository 的对应所有权边界完成校验，并由测试保护。
- `PRIMARY KEY`、`UNIQUE`、`NOT NULL`、查询索引以及纯技术默认值仍然允许。它们只
  表达行身份、幂等或唯一性、必需的存储形状和访问路径，不承载业务状态校验。

## 基线演进

项目仍处于允许丢弃旧开发数据的阶段。修改 Schema 时直接编辑所属表文件；新增表时
新增同名表文件并把它显式加入入口。基线不包含旧表兼容、`ALTER` 升级、数据回填或
应用启动迁移。

修改完成后：

1. 在空 PostgreSQL 数据库执行完整入口，并再次执行同一入口。
2. 确认每个表文件只定义同名的一张表，最终表集合与文件集合完全一致。
3. 确认没有 PostgreSQL 外键、`CHECK`、排他约束、Trigger、Rule、存储
   Function/Procedure、自定义 Domain 或 Enum 等数据库校验对象。
4. 确认所有时间点字段和毫秒时长字段均为 `bigint`，没有 PostgreSQL 原生日期时间
   类型，且数据库没有生成领域审计事实。
5. 重建开发数据库，重新生成 JOOQ，并同步 Repository、Entry 和相关测试引用。

统一验证命令：

```bash
.codex/skills/design-postgres-schema/scripts/validate_postgres_schema.sh \
  gen/sql/flow/001_create_flow_tables.sql
```
