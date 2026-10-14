# 当前构建与验收入口

当前根项目通过原生 Java 模块直接使用，包名为 `org.flow.*`，配置前缀为 `flow.*`。
运行入口依据 [ADR 0121](../decisions/0121-use-native-modules-directly.md)，
命名约定依据 [ADR 0122](../decisions/0122-use-flow-namespace-and-configuration.md)。

- [原生 Java 入口、具名数据源、生命周期和插件导出](java-engine.md)
- [临时 PostgreSQL 技术验收脚本](verify-flow.sh)
- [TOML 依赖管理与版本比较](dependency-management.md)

以下记录保留各次迁移当时的命名、命令和验收结果：

- [完整核心迁移清单、实施顺序与验收证据](kestra-core-migration.md)
- [第一轮裁剪范围、恢复点与验证](kestra-prune-round1.md)
- [第二轮核心功能裁剪与验证](kestra-prune-round2.md)
- [退出功能的源码残留清理与验证](kestra-prune-unused.md)
- [PostgreSQL 唯一后端清理与验证](kestra-postgres-only.md)
- [旧工程完整备份与恢复](kestra-source-backup.md)

本目录其它早期文档与探针保留为旧 Flow 的历史证据，所引用的旧 core/server/gen、
Pulsar、DataSourceEngine 和 Maven 发布命令应在对应备份中查阅。当前执行入口以本页
Java 使用指南和验收脚本为准；历史记录不表示旧业务功能已经迁入。
