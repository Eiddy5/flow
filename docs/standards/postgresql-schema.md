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

## 基线演进

项目仍处于允许丢弃旧开发数据的阶段。修改 Schema 时直接编辑所属表文件；新增表时
新增同名表文件并把它显式加入入口。基线不包含旧表兼容、`ALTER` 升级、数据回填或
应用启动迁移。

修改完成后：

1. 在空 PostgreSQL 数据库执行完整入口，并再次执行同一入口。
2. 确认每个表文件只定义同名的一张表，最终表集合与文件集合完全一致。
3. 确认没有 PostgreSQL 外键，时间字段类型遵循对应领域与数据库决策；当前 Flow
   基线的领域时间和 Queue 顺序时间使用 Epoch 毫秒 `bigint`，不由数据库生成领域
   审计事实。
4. 重建开发数据库，重新生成 JOOQ，并同步 Repository、Entry 和相关测试引用。

统一验证命令：

```bash
.codex/skills/design-postgres-schema/scripts/validate_postgres_schema.sh \
  gen/sql/flow/001_create_flow_tables.sql
```
