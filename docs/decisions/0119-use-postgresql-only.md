# ADR 0119：数据库统一为 PostgreSQL

## 状态

Accepted（2026-10-13，用户明确数据库只使用 PostgreSQL）。修订 ADR 0117/0118
中为上游测试保留 H2 和 memory 后端的选择。

## 背景

生产嵌入入口已经使用宿主的 `datasources.flow`，但核心回归仍依赖 H2，
`repository-memory` 和 `runner-memory` 也只是 H2 的配置包装。继续维护多个数据库
会保留无用实现，并使测试与生产的事务、锁和 JSONB 行为不同。

## 备选方案

1. 只将生产配置固定为 PG，保留 H2 测试与多方言源码：无法满足源码清理要求。
2. 删除其它数据库实现，将保留能力的测试迁至真实 PostgreSQL：采用。
3. 合并全部 JDBC 模块并改写原生 Repository/队列接口：本轮没有这一必要。

## 决策

- 删除 `jdbc-h2`、`repository-memory`、`runner-memory` 及其构建注册、驱动、
  临时 H2 数据源重写和 memory 别名。测试同样只使用 PG。
- 保留 `jdbc` 的持久化/事务基础、`jdbc-postgres` 的 PG Repository/SQL/队列客户端、
  `queue-jdbc` 的 Dispatch/Broadcast 协议实现；这些层只装配 PostgreSQL。
- 清理 MySQL/H2 专用插入竞争处理、错误码、日志批量删除参数和时间转换分支。
  插入竞争使用 PG 的 `ON CONFLICT DO NOTHING`；其它 SQL 错误继续向上传播。
- 保留日志扩展 SPI；原生日志数据源提供者只识别 PG。embedded 继续拒绝独立日志
  URL，所有持久化和 JDBC 队列共用宿主可配置的 `datasources.flow`，不关闭宿主池、
  不回退 default、不隐式迁移。事务包装器拒绝非 PG 方言。
- 保留原生 PG 的历史迁移 SQL 和校验和。数据库只使用 PG 不等于更改表协议，
  不加入 Route 或业务模型。
- H2 共有测试已有 PG 实现时保留 PG 版本；独有且仍有价值的并发状态、日志
  插件发现、独立日志库和能力降级测试迁到 PG。已有 PG 锁测试继续复用，不重复增加入口；仅删除 H2 专用函数/缓存/迁移测试。
- 排序字段元数据按 PG 的 `to_regclass` 与当前连接 search_path 解析实际表，
  仅读取该表的 `pg_attribute`，不扫描整个数据库或混合同名表的列。
- 测试连接由独立 PostgreSQL 容器提供，各测试 Context 使用独立 schema，
  测试脚本退出时清理自己创建的容器。不使用宿主业务数据库。

## 理由

统一数据库实现与测试语义，同时保留原生执行和队列的职责边界。配置能力体现在
宿主的连接、凭据、池和 schema 可配置，不再体现在数据库产品可切换。

## 后果与验收

数据库集成测试需要真实 PG，不再支持纯内存运行。H2/MySQL/memory 配置和
`PurgeLogs.batchSize` 退出支持，不提供兼容别名或空实现。

验收包括全部测试类编译、默认完整回归、真实 PG 队列/Repository/调度测试、
宿主具名池隔离及重建 Context 后回读、插件加载和 Task 导出、运行依赖隔离、
PG SQL 哈希与来源核对。已有失败必须与本轮新增失败分开记录；结果见
`docs/harness/kestra-postgres-only.md`。
