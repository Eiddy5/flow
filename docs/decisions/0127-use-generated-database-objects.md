# ADR 0127：通过 gen 统一数据库生成对象

## 状态

Accepted（2026-10-15）。用户确认参考 CSES，完整使用内部 jOOQ 生成器和
`JooqPojo`，允许其必要 PaaS 依赖。本决策修订
[ADR 0126](0126-use-internal-micronaut.md) 中不引入任何 PaaS 模块、保持官方 jOOQ
的条款；Java 21 下限、TOML 管理、原生模块职责和 PostgreSQL 协议保持不变。

## 背景

原生 JDBC 适配器通过表名、字段名和手写类型访问数据库，缺少统一的生成对象。
CSES 使用内部 `ExtJavaGenerator`、`ExtJavaGeneratorStrategy` 及 `JooqPojo`，
可以从真实 PostgreSQL 结构生成统一的 Table、Field、Record 和对象类型。
这些类型的引入不应改变 Flow 原有的 JSONB 数据协议、事务、具名数据源与队列。

内部生成器的默认依赖会选择 PaaS 2.5、Micronaut 5，要求 Java 25。直接复制版本
和连接配置不能满足当前 Java 21 下限，也不能把业务数据库作为普通编译的前置条件。

## 备选方案

1. 保留分散的手写数据库对象：不能满足统一生成对象的要求，不采用。
2. 使用官方生成器或本地模拟 `JooqPojo`：不符合完整沿用内部生成能力的选择。
3. 引入独立 gen 模块，使用内部生成器和必要依赖，约束 Java 21 兼容版本：采用。

## 决策

- 增加 `gen` Java Library，生成代码提交在 `gen/src/main/java/org/flow/gen/flow/`。
  生成器入口放在独立 `codegen` source set；`:gen:generateJooq` 显式执行生成，
  普通编译使用已提交的生成代码，不连接数据库、不隐式重新生成。
- `JooqFlowGenerator` 调用内部 `ExtJavaGenerator` 和
  `ExtJavaGeneratorStrategy`。生成 21 张运行表及 `flow_migration_history`，包含
  Table、Field、Record、`XxxObject extends JooqPojo`、枚举、主键和索引。
  不手工编辑生成代码，不为模拟内部生成能力新增平行基类。
- gen 不依赖 Flow 的 core、Repository 或执行模块。`jdbc`、`jdbc-postgres`、
  `queue-jdbc` 消费 gen；Core Domain 与服务契约不依赖生成类型。生成器依赖留在
  codegen classpath，`JooqPojo` 所需的运行依赖随 gen 传递。
- 现有迁移 SQL 仍由 `jdbc-postgres/src/main/resources/migrations/` 维护，不另建
  第二份 Schema 基线，不改变已有 SQL 或 checksum。生成脚本创建临时 PostgreSQL
  17，按现有八份新库迁移 SQL 建表，并补齐迁移历史表的结构；不伪造迁移执行记录。
- 默认运行 `docs/harness/generate-jooq.sh` 生成，它创建并清理独立空库，日志保留在
  `build/gen-validation/`。显式连接入口只读取 `FLOW_GEN_*` 或命名参数，不复制
  CSES 的环境连接凭据。生成前检查目标表和主键完整性，再允许生成器清理输出目录。
- 生成对象不固化 `public` schema；运行时继续使用现有表配置、别名、schema、
  `search_path` 和独立日志库。生成 Field 提供数据库类型和字段身份，动态表达式
  与临时查询结果仍按实际 SQL 语义构造，不为消除字符串改写查询含义。
- 保留原生 Jackson/JSONB 序列化、可写列集合和事务边界。`GENERATED ALWAYS`
  字段由 PostgreSQL 计算，不能用全列 POJO Map 回写。引入生成对象不要求迁回旧
  DataSourceEngine、为每张表增加旧式 Entry 或改用 PaaS JSON 序列化。
- jOOQ 家族统一为内部 `3.21.4.1`，PaaS BOM 为 `1.0.0.161`；Micronaut 选择与之
  配套的平台 `4.10.6.335`，核心与 SQL 集成由平台分别管理为 `4.10.12.23`、
  `6.3.2.13`。gen 的普通依赖与 codegen 依赖均使用普通 Micronaut platform 和
  enforced jOOQ/PaaS BOM；排除 jooq-paas 固定引入的 Micronaut JOOQ 7.x，再显式
  引入平台管理的 6.x。所有显式版本由 TOML 维护，不混用两套框架代际。
- 必要 PaaS 范围包括 `common`、`json`、`sql`、`exception`，以及内部 Bean 装配
  需要的 `paas-pulsar` 与必要客户端。内部 DBEventSender 及条件 Bean 定义依赖
  Pulsar 类型，运行期保留这些类型。不引入 `paas-all`、
  `paas-service-all`、AuthX 或 CSES 业务模块，Flow 工作消息继续由 JDBC
  Dispatch/Broadcast 传输。
- 在相关依赖边局部排除 `micronaut-http-server-netty`，jOOQ codegen/meta 仅留在
  独立 codegen classpath。生产隔离允许五个 PaaS 模块、五个 Apache Pulsar 客户端
  配套模块及 jooq/jooq-paas；其余聚合包、产品后端和生成器制品不进入根运行产物。
  具体制品清单见生成指南。
- jdbc 的 `FlowDatabaseContextConfigurer` 通过 `@ContextConfigurer` 注册
  `flow-jdbc-runtime-defaults` PropertySource，顺序为 `Integer.MIN_VALUE`。
  `jooq.send-event`、`table.db-change.listeners.pulsar.enabled`、
  `pulsar.consumer.enabled` 默认均为 false。默认值用于关闭内部 DB 事件和消费者，
  宿主 YAML、环境变量、系统属性与 builder 显式配置具有更高优先级，不强制覆盖宿主。
- Java 下限继续为 21，构建仍遵循环境与标准 Gradle Java 配置；不固定 toolchain
  或掩盖依赖的真实字节码要求。

## 理由

独立生成模块使数据库结构成为 Table、Field 和对象类型的统一来源，同时保持
生成工具与正常构建分离。复用内部能力需要接受其实际传递依赖，再明确兼容版本和
装配边界，而不是仅复制生成文件名。原生迁移与运行协议继续保持单一来源，避免
数据库对象统一演变为领域模型或持久化协议迁移。

## 后果与验证

gen 及其运行依赖进入数据库适配器的传递依赖；内部仓库解析范围也需包含内部 jOOQ
和必要 PaaS 制品，外部消费者应同步配置自己的仓库。不能沿用 ADR 0126 的
“无 PaaS、jOOQ 版本不变”验证结论。

Java 21 清理编译、73 个生成文件重复生成、根 PostgreSQL/引擎验证、表名/schema
元数据及默认配置覆盖专项已通过。完整回归执行 5,246 项，5,216 通过、26 跳过、
4 失败；相对 `fc1e3f4` 基线新增 49 项全部通过，无缺失工程或套件。
3 项为既有 UI 文档缺失检查，另 1 项 PurgeKV 日期筛选失败已在提交前 `fc1e3f4`
同环境复现：既有触发器刷新旧版本物理更新时间，导致其晚于测试 cutoff；本轮
未为消除该失败改动业务语义、测试断言或迁移。

129 个配置解析成功，410 个外部 JAR 与 2,612 个生产类通过 Java 21 字节码核对；
独立 composite 消费的 API 编译、角色类加载及依赖检查通过。来源账本及差异检查
通过。独立消费者未启动 Context，本轮没有发布 Maven 制品；完整回归仍为 4 项
失败，不能记为全绿。
详细证据与验证边界见 [生成指南](../harness/jooq-generation.md)；本决策的 Accepted
状态不代表验收全部通过。
