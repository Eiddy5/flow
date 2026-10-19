# ADR 0130：持久化归回所属模块并保留数据库底层

## 状态

Accepted（2026-10-16）。用户确认采用 13 模块方案：保留不依赖 core 的 jdbc，
删除 queue-jdbc、jdbc-postgres，将各能力专属存储归回所属模块，script 保持独立。
本决策修订此前独立数据库适配模块的职责和依赖方向；
[ADR 0128](0128-use-generated-tables-directly.md) 的生成表及字段使用规则、
[ADR 0129](0129-remove-tests-and-local-storage-modules.md) 的测试和宿主存储边界继续有效。

## 背景

当前仅支持 PostgreSQL，但专属持久化仍横跨 jdbc、jdbc-postgres、queue-jdbc，
同一能力的契约、实现和表配置分散。jdbc 同时引用 core 的领域和服务契约，使其
不能作为不依赖业务能力的数据库基础层使用。

单纯合并所有代码会把执行、Worker、调度和索引职责集中到 core，也可能产生
core 与运行模块之间的循环依赖。模块收敛需要同步调整持久化归属及底层依赖方向，
同时保留现有数据库结构、迁移历史和运行协议。

## 备选方案

1. 保留现有模块，只改变目录或包名：专属存储仍跨模块分散，不采用。
2. 将全部 JDBC、队列和运行持久化集中到 core：削弱能力边界并引入反向依赖，
   不采用。
3. 删除独立适配模块，保留无 core 依赖的 jdbc 底层，各能力拥有自己的持久化：采用。

## 决策

- 删除 queue-jdbc、jdbc-postgres 两个模块及其 Gradle 注册。保留 model、processor、
  gen、jdbc、core、queue、executor、worker、scheduler、indexer、script、tooling、
  plugin-fixture 共 13 个子模块；根 Java Library 继续聚合运行依赖。
- jdbc 仅保留数据源、jOOQ、事务执行和通用数据库工具，以及最低优先级数据库事件
  和消费者默认配置。jdbc 不依赖 core、领域对象、运行角色或能力专属仓储。
- 数据库死锁及序列化冲突的重试耗尽异常，由 core 的 `RetryUtils.RetryFailed`
  改为 jdbc 的 `JooqDSLContextWrapper.TransactionRetryFailedException`，保存最后
  一次失败的 cause、`attemptCount` 和 `elapsedTime`。普通不可重试异常按原实例
  传播，不包装为重试耗尽异常，不提供旧异常类的桥接。重试策略保持 50–1000 ms
  随机间隔，不设置尝试次数上限，由 60 秒最大重试时长约束。
- core 依赖 jdbc/gen，承载通用仓储基类、core 自身仓储，以及现有迁移 SPI 的数据库
  适配、历史表和锁。core 的领域对象与公开服务契约仍不使用生成数据库类型。
- queue、executor、worker、scheduler、indexer 分别承载各自专属的持久化和表配置，
  按实际需要依赖 core/jdbc/gen。core 只持有运行角色契约，生产依赖不反向引用这些
  实现模块；数据库技术测试可在测试 classpath 加载能力模块及其迁移资源。
  ExecutionRepository 归 executor，并在该模块实现 ExecutionStateStore；
  ServiceInstance 仓储归 core，日志、指标、执行统计仓储归 indexer，其余 core
  仓储留在 core。
- 数据库底层继续使用 `org.flow.jdbc`；各能力数据库实现统一使用
  `org.flow.<owner>.jdbc`，PostgreSQL 具体实现为该包下的 `postgres`；迁移抽象
  和 Java 数据迁移为 `org.flow.<owner>.jdbc.migration`，PostgreSQL 迁移实现为
  `org.flow.<owner>.jdbc.postgres.migration`。源码、资源注册、反射配置和测试
  引用随所属模块调整，不保留旧包名或已删除模块的兼容入口。
- 所有物理表和字段继续直接使用 gen 生成类型；生成表的配置改名、查询别名、
  筛选排序与值类型转换继续遵循 ADR 0128。不恢复手写无类型字段或未知表兜底。
- jdbc 底层的 `JdbcTableConfig` 由调用方显式传入生成表，不保留中央表名 switch
  或集中工厂。各能力用 `<Owner>JdbcTableConfigsFactory` 声明自己的表，逐表读取
  `flow.jdbc.tables.<name>.table`，未配置时使用生成表名称。局部表名覆盖不会取消
  其他表的默认配置，也不引入按任意名称重建字段的兼容路径。
- 混合基线 `baseline-postgres.sql`、`2.0.01-schema-postgres.sql` 以及
  `2.0.09-execution-outputs-postgres.sql` 留在 core。queue 拥有三份队列 SQL，
  indexer 拥有日志建表与扩宽 SQL，executor 拥有状态时长修复 SQL，scheduler 拥有
  触发器禁用字段 SQL；均置于所属模块的 `src/main/resources/migrations/`。
  保持原 `/migrations/...` classpath 路径、文件字节、脚本标识、checksum、执行
  顺序、迁移历史表与锁协议，不拆分旧 SQL 内容。现有迁移入口继续协调执行，gen
  的临时 PostgreSQL 生成入口读取这些模块资源，不维护第二套结构来源。
- `datasources.flow`、PostgreSQL、原生 JSONB、JDBC 队列和事务语义保持不变。
  宿主存储仍由现有 StorageInterface/SPI 提供，不恢复本地存储模块或测试替代实现。
- tooling 依赖 core 后会传递包含 jdbc、gen 和数据库库，运行隔离改为允许这部分
  依赖，继续禁止引入实际运行角色、jOOQ codegen/meta 等非离线导出依赖。
  工具仍只读取类型和 Bean 元数据，不启动 ApplicationContext、不连接数据库。
- 依赖版本继续使用 TOML，内部 Micronaut/jOOQ/PaaS 来源及 Java 21 下限不变。
  按归属调整实际所需库、处理器和测试依赖，不恢复全局框架依赖或新增聚合平台。

## 理由

模块拥有自己的能力与持久化，可以一起理解和修改调用链；数据库基础层保持无
core 依赖，使事务与数据源配置能够稳定地被上层复用。将通用仓储基类留在 core，
避免把依赖领域协议的代码伪装成通用数据库工具。
数据库重试错误由 jdbc 自身表达，避免底层为了异常类型继续依赖 core 的任务重试
模型；保留 cause、次数和耗时，使调用方仍能获取失败原因及重试信息。

保留混合 SQL 内容和原资源路径，可以移动代码职责而不改变迁移事实。core 获得持久化依赖
后，tooling 的 classpath 会扩大；接受这些库并保持不启动 Context，比通过错误的
排除规则制造运行期缺类更符合当前离线导出方式。

## 后果与验证

直接依赖 queue-jdbc 或 jdbc-postgres 的调用工程应改为消费所属能力模块，或继续
使用根 Java Library。使用旧数据库实现包名的调用点需要随之更新，不提供兼容层。
直接捕获 JDBC 重试耗尽异常的调用方需要改为
`org.flow.jdbc.JooqDSLContextWrapper.TransactionRetryFailedException`，通过
`getCause()`、`getAttemptCount()`、`getElapsedTime()` 读取失败信息；原来捕获
core `RetryUtils.RetryFailed` 的分支不再覆盖该数据库失败。
历史混合基线仍留在 core，其他 SQL 已随能力归属调整。后续若要拆分 SQL 内容，
需要另行明确脚本身份、顺序与加载协议，不能通过更名或重新编号隐式迁移。

Java 21.0.12 下先执行 clean，再运行以下命令成功；89 个任务全部实际执行：

```bash
bash docs/harness/verify-flow.sh --full-regression --offline --no-build-cache --rerun-tasks --console=plain build :tooling:exportPluginSchemas
```

本轮保留测试合计 2,427 项，2,426 项通过、1 项跳过、0 项失败。验证包括生产及
剩余测试编译、真实 PostgreSQL 技术测试、重试耗尽元数据与普通异常原实例传播、
构建、根与 tooling 运行隔离，以及插件和配置 Schema 导出。13 模块依赖扫描通过；
jdbc 不依赖 core，core 生产依赖不反向引用运行角色。tooling 允许数据库依赖，
继续禁止实际运行角色与 jOOQ codegen/meta，离线导出不启动 Context。

`bash docs/harness/generate-jooq.sh --offline --console=plain` 生成成功，
`gen/src/main/java` 没有 Git 差异。10 份迁移 SQL 的 SHA-256 与搬迁前一致。
最终清理冗余 validation runtime 和 testArtifacts 配置后，再执行以下命令成功：

```bash
./gradlew classes testClasses jar verifyRuntimeIsolation :tooling:verifyRuntimeIsolation --offline --console=plain
```

证据保存在本地忽略目录 `build/module-consolidation-validation/`：`summary.json`、
`validation.log`、`generation.log`、`junit-results.zip` 与 `final-graph-check.log`。
本轮验证针对仍保留的技术测试；不包含此前删除的测试覆盖，也不代表完整引擎执行、
业务 UC 或宿主 Storage 实现已经通过验收。历史回归记录继续保留其当时的范围与结果。
当前目录职责见 [项目结构](../project-structure.md)，消费方式见
[Java 使用指南](../harness/java-engine.md)，生成入口见
[数据库对象生成指南](../harness/jooq-generation.md)。
