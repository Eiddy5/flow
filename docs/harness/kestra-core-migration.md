# Kestra 第一阶段迁移台账

依据：[ADR 0115](../decisions/0115-migrate-kestra-core-before-business.md)、[ADR 0116](../decisions/0116-replace-flow-root-with-kestra-sources.md)。
分支：`codex/kestra-core-migration`。上游：`v2.0.5` /
`172dd0f3760fb1255d3b22bd1fd07ce31b173fa3`。状态：实施中，未完成阶段验收。

第一轮裁剪已按 [ADR 0117](../decisions/0117-prune-kestra-product-entrypoints.md) 实施；
范围、当前测试结果与剩余问题见 [第一轮记录](kestra-prune-round1.md)。
第二轮按 [ADR 0118](../decisions/0118-prune-kestra-core-product-features.md) 退出
AI/MCP、Dashboard、匿名上报、自动安装、Docker 及专用兼容接口；扩展和 Task 导出
保留，当前证据见 [第二轮记录](kestra-prune-round2.md)。
后续按同一 ADR 继续删除退出功能的无用源代码，见 [源码残留清理记录](kestra-prune-unused.md)。
数据库按 [ADR 0119](../decisions/0119-use-postgresql-only.md) 收敛为 PostgreSQL，
删除 H2/memory 并迁移测试，见 [本轮记录](kestra-postgres-only.md)。
当前 Java 使用入口按 [ADR 0121](../decisions/0121-use-native-modules-directly.md) 归入原生模块，
embedded 已移除；配置、调用和验证命令见 [直接 Java 使用指南](java-engine.md)。
以下根替换和续验段保留当时 CLI/模块数量及失败证据，不代表当前目录。

## 完整迁移清单

“迁入源码”“编译通过”“能力验收通过”分别记录；不能互相替代。

| 编号 | 能力与上游位置 | 实施内容 | 验收标准 |
| --- | --- | --- | --- |
| K01 | platform/model/core | 固定版本、依赖平台、模型、许可、来源清单 | 干净检出可构建；全部来源有哈希，补丁可审计 |
| K02 | core serializers/validations | YAML/JSON、插件物化、校验、定义版本 | 嵌套原生 Task 正确往返；缺类型/必填/错误类型明确拒绝 |
| K03 | core execution/task/state | Execution、TaskRun、Attempt、状态历史 | 原生顺序/并行/循环/子流程和终态事实正确 |
| K04 | executor | 命令消费、状态推进、错误、finally、并发限制 | 成功/失败/取消竞争不丢推进、不重复完成 |
| K05 | worker/worker-controller | Worker 交付、gRPC、心跳、失联重投 | 强杀与重启恢复；交接身份和上游 Attempt 语义可追踪 |
| K06 | queue/queue-jdbc | Dispatch/Broadcast、序列化、路由、领取/ACK/异常 | 真实 PG：重启不丢消息、异常可恢复、重复消息处理正确 |
| K07 | jdbc/jdbc-postgres | 全部 Repository、状态存储、短事务、锁 | 默认库与 flow 库并存，所有引擎写入只进 flow；独立事务同源 |
| K08 | jdbc-postgres migrations | 完整表/索引/显式迁移工具、表冲突审计 | 空验证库可初始化；嵌入启动不执行 DDL；旧业务库不受影响 |
| K09 | scheduler/core triggers | Cron/日期/事件触发、归属、禁用更新、补偿 | 真实时钟触发、重启与错过触发策略、重复事件均符合上游 |
| K10 | core runners/conditions | RunContext、变量、Pebble、checks/when | 缺值/类型/分支判断；条件求值时机不混淆 |
| K11 | core plugin/core | 原生控制流与基础任务、Pause/Resume/Retry/Replay | 原生协议验证；不以旧 Flow 业务恢复契约替代上游预期 |
| K12 | model/processor/core plugins | 扩展接口、注解处理器、服务资源、注册 | 外部源码独立构建；最终 JAR 含真实注册资源；无手工注册兜底 |
| K13 | core plugins | 内置/外置加载、ClassLoader、标识/版本、配置 | 外部 JAR 自动发现；缺失类型和依赖冲突可诊断 |
| K14 | core docs/embedded export | Task 目录、属性、约束、输出结构、Schema/文档/图标/示例 | 宿主 Java 调用可获取/导出；产物可解析且与实际注册类型一致 |
| K15 | tooling schema/core plugins | 聚合 Schema、目录与生成资源 | 固定版本对应工具可生成资源，重载后仍可查询 |
| K16 | core services/storage-local | Task/Flow Output、文件、namespace/KV/secret 所需接口 | 结构化/大输出/文件可读取、供下游使用且重启后可读 |
| K17 | indexer/core metrics/logs | 日志、指标、查询、保留与清理 | 执行可追踪，输出/日志查询正确，清理不删保留对象 |
| K18 | script/其他 SPI | TaskRunner、存储、文件渲染等扩展与开发测试支撑 | 接口及装配不遗漏；对支持类型逐项取证，不以一个 Task 代替全部 |
| K19 | embedded 自有适配 | Java 门面、生命周期、宿主 Context/配置/依赖 | CSES 同 JVM、同 Context，启停不误关宿主，不增加全局端口/默认配置 |
| K20 | 交付与切换 | 无 Pulsar 的新产物、源码补丁、开发模板、操作手册 | 依赖树无 Pulsar；旧实现完整备份后退出当前工程；完整阶段通过后才进入 Route |

不迁入全套管理 UI、企业版和所有第三方插件，但保留上述机制及必要后端。
数据库只使用 PostgreSQL，H2/MySQL/memory 实现与驱动退出生产和测试依赖。
导出同时指插件制品与 Task 定义信息，运行 Output 单独验收。

## 实施顺序

1. **基线与构建**：完整备份旧工程，迁入固定源码、许可和哈希清单，直接形成根后端构建；首先编译模型、
   processor、core，再编译生产依赖链。保留上游测试，记录框架/Java 兼容差异。
2. **持久化和队列**：具名 flow 数据源适配覆盖 wrapper、日志、状态存储与独立事务；
   禁用启动 DDL，提供显式验证库初始化；用真实 PG 验证队列生产/领取/ACK/回滚。
3. **运行与调度**：同 Context 启动 Executor/Worker Controller/Worker/Indexer/Scheduler；
   Java 发布运行，验证原生流程、取消、失败、强杀恢复和调度补偿。
4. **扩展与导出**：完整移入 processor、加载器、目录和生成工具；建立无业务验证插件工程，
   跑通构建→加载→目录/Schema/文档/图标→YAML 运行→输出→重启。
   此步骤与前面并行开发时仍须独立验收，不能推迟到业务阶段。
5. **宿主与交付验收**：核对真实 CSES 的依赖、Bean、配置和停止所有权；检查新产物
   无 Pulsar/旧业务依赖，记录全部适配及尚未迁移的入口。
6. **阶段收口**：K01–K20 全部有证据、没有未决必选能力；才进入 Route 和业务阶段。

## 验证边界与证据要求

- 技术验收使用原生能力与中性插件；本台账不新建或修改业务 UC。
- 真实 PostgreSQL 使用本任务独立验证库/容器，拒绝运行针对现有业务库的初始化命令。
- 数据源负向测试必须同时提供另一默认库并验证无写入；缺失 flow 数据源应明确失败。
- 宿主关闭测试检查 Context、连接池与非引擎 Bean 仍可用；强杀测试使用独立 JVM。
- 每次结果记录命令、JDK、数据库、通过数及未覆盖项。构建失败不能记作跳过或通过。
- 第一阶段完成后再由 UC/Test Agent 处理业务兼容场景；不能提前声称旧 UC 全通过。

## 根项目替换（2026-10-13）

按用户修订完成旧工作区的完整外部备份与逐文件哈希校验，见
[备份凭证与恢复](kestra-source-backup.md)。22 个 Kestra 后端模块直接位于根目录，
保留原生模型、状态机、JDBC 队列、调度与扩展源码；根构建直接编译它们。
`embedded` 和 `plugin-fixture` 为同一根构建中的宿主基础设施与无业务验证模块。
原 `kestra-engine/`、旧 core/scheduler/processor 源码、server/gen/buildSrc、Pulsar
实现与旧构建缓存已退出当前工程。原生 Wrapper/编辑配置和启动脚本加入来源清单。

根目录重新执行的证据（2026-10-13 09:50 +08:00）：

- `./gradlew :embedded:classes :embedded:jar :embedded:verifyRuntimeIsolation --no-build-cache`：通过，1 分 1 秒，22 项实际执行；旧模块和原子目录工程的 build/.gradle 均已清理。
- `bash docs/harness/verify-kestra-embedded.sh --no-build-cache`：通过，1 分 13 秒；真实 PostgreSQL 技术测试 **7/7 PASS、0 FAIL、0 ERROR、0 SKIP**，临时容器和卷已清理。
- 原生 CLI 在新根构建中重新导出：4 类入口、290 个共享定义、1 个外部插件坐标；内部引用、目录与离线加载由 PluginExportTest 验证。
- `python3 verify-upstream.py`：**3,508 个文件、4 处已登记适配**通过。新增 8 个来源文件为 Wrapper、编辑配置和原生 JAR 启动脚本。
- 24 个已注册模块的生产源码扫描无旧 Flow core/executor/scheduler 或 Pulsar 导入；依赖隔离检查通过。
- `bash -n docs/harness/verify-kestra-embedded.sh`、`git diff --check`：通过。

报告位于 `embedded/build/reports/tests/test/index.html`，JUnit XML 位于
`embedded/build/test-results/test/`。本机过程日志为 `/tmp/flow-kestra-root-build.log` 和
`/tmp/flow-kestra-root-acceptance.log`。以上是新根工程结果；旧子工程证据未被冒充为
本次运行。完整原生回归与第一阶段门槛仍分别记录，不由这 7 项推导全部通过。

### 根目录上游通用回归

执行：

```bash
./gradlew test --fail-fast -x :jdbc-postgres:test -x :jdbc-mysql:test -x :embedded:test
```

原生 PostgreSQL/MySQL 测试直接配置本机固定端口，部分 Java 测试硬编码连接 URL，
本轮尚未将它们接入专用环境，故明确排除这两组。`embedded:test` 已在上面的临时
PostgreSQL 中独立通过，通用回归不重复运行它。该命令不是全量测试通过的证明。

本轮耗时 6 分钟后失败并提前停止：

| 范围 | 结果 |
| --- | --- |
| CLI | 80 项通过，0 失败、0 跳过 |
| Core | 1,801 项完成：1,796 通过、1 失败、4 跳过 |
| 后续模块 | 因 fail-fast 未完成，不计作通过 |

失败为 `io.kestra.core.runners.TaskCacheTest.shouldCacheTaskRunOutput` 第 55 行：
第二次执行预期读取缓存计数 1，实际计数 2。原始批次 XML 与 HTML 已独立保存在
`build/validation/root-regression/core/`（CLI 同批结果在相邻 `cli/`），过程日志为
`/tmp/flow-kestra-root-regression.log`。

随后只运行该方法：

```bash
./gradlew :core:test --tests 'io.kestra.core.runners.TaskCacheTest.shouldCacheTaskRunOutput'
```

单独重跑 **1/1 通过**，耗时 23 秒，日志
`/tmp/flow-kestra-root-cache-recheck.log`，当前 `core/build/test-results/test/` 是该次
单方法结果。没有修改缓存实现、测试断言或上游源码。整批失败的根因尚未定位，不能
以单跑成功覆盖原始失败，也不能据此认定为上游缺陷或迁移缺陷；完整回归保持未通过。

## 当前实施记录

以下功能证据来自 2026-10-12 续验；2026-10-13 根工程替换另见上节，仍未达到第一阶段整体完成门槛：

| 范围 | 当前结果 | 仍缺的阶段证据 |
| --- | --- | --- |
| K01 基线 | 22 个后端模块、3,500 个上游文件（含根 lombok.config）；4 处上游适配已登记；嵌入生产链及原生 CLI 编译成功 | 干净机器复现、宿主依赖对齐 |
| K06/K07 队列与数据源 | 发布、异常回滚、ACK 删除、双消费者 SKIP LOCKED、强杀持锁消费者 JVM 后重领、独立事务同源通过；默认库无写入；缺失 flow 的事务/迁移历史/锁拒绝回退 | Broadcast、重复提交/结果、全部 Repository/状态存储验收；队列强杀不能替代 Worker 失联恢复 |
| K08 显式迁移 | 完整原生基线/升级链在独立 schema 初始化成功；历史/锁同源；新 Context 重跑不重写历史；启动无隐式 DDL | 正式命名空间与旧库切换、在途数据处理；未在业务库执行 |
| K03/K04/K09/K10 运行与调度 | 同 Context 六类服务就绪；Java 发布 Create，经 JDBC 和 Worker 完成原生 If、表达式、Log、外部 Task；真实秒级 Cron 触发并完成 | 并行/循环/子流程、失败/取消竞争、Pause/Resume/Retry/Replay、Worker 强杀、调度重启/错过补偿 |
| K12/K13 扩展 | 独立 fixture JAR 含 processor 注册资源；配置加载与外部 ClassLoader、YAML 类型解析通过 | 所有扩展协议逐类验证、冲突诊断、独立插件工程模板 |
| K14/K15 导出 | 实际目录 50 个 Task 全部发现并导出文档；73 个 Schema、218 个文件可解析；原生 CLI 生成四类入口/290 个共享定义/1 个插件坐标，内部引用及离线重载通过 | 其它扩展协议、依赖冲突、文件渲染器注册及打包依赖；设计器消费对接 |
| K16 输出 | 结构化 Task/Flow Output、超阈值输出转文件存储、下游表达式读取，以及关闭并重建 Context 后读回通过 | 独立文件上传下载、namespace/KV/secret 扩展及清理边界 |
| K19 生命周期 | 故障 shutdown 转为引擎停止；六类服务优雅终止；重复 close 安全；宿主 Context 存活，flow/default 可借新连接，数据可继续查询 | 真实 CSES 装配、启动中故障、运行中任务停止、辅助线程清理、Controller 监听策略；不能把独立 Micronaut 验证等同 CSES 验收 |
| K20 依赖 | `embedded:verifyRuntimeIsolation` 通过；新产物无 Pulsar、旧 Flow、CLI/Web、H2/MySQL/内存后端依赖 | 当前工程已移除旧 Pulsar 实现；仍需新链路整体通过及正式宿主切换 |
| K17/K18 等 | 上游源码保留，script 已纳入生产链；原生运行日志可见；未加入 Route 或 CSES 业务代码 | 指标/保留清理及全部 SPI 协议验收，详见上述清单 |

本批没有修改 CSES 的依赖，没有启动旧引擎与新引擎双跑，没有操作现有业务库。
旧 Flow 模块已完整备份并退出当前工程；这不等于第一阶段整体通过或 CSES 已切换。

### 可复现命令与续验结果

历史续验使用 Wrapper 9.5.1、Java toolchain 25，首次编译 5 分 10 秒。当前根构建使用固定上游 Wrapper 9.7.1，Java 版本由构建选择，见 [ADR 0125](../decisions/0125-use-build-selected-java-version.md)。以下命令保留该次 embedded 续验的含义，当前入口见 [Java 使用指南](java-engine.md)。
验证库为本任务创建的 PostgreSQL 17.10 容器，内含独立的 `flow` 与 `control`
空库；验证结束后移除该容器及其卷，不保留测试连接配置。

```bash
# 推荐：自动创建专用 PostgreSQL，执行技术验收，退出时清理容器及卷。
bash docs/harness/verify-kestra-embedded.sh

# 独立执行原生聚合导出工具，不启动 HTTP 服务。
./gradlew :tooling:exportPluginSchemas

# 先创建本任务专用空库，端口按实际容器映射填写。
FLOW_KESTRA_TEST_URL=jdbc:postgresql://127.0.0.1:PORT/flow \
FLOW_KESTRA_CONTROL_URL=jdbc:postgresql://127.0.0.1:PORT/control \
./gradlew :embedded:test :embedded:jar :embedded:verifyRuntimeIsolation

./gradlew :embedded:verifyRuntimeIsolation
python3 verify-upstream.py
```

本轮技术测试 **7/7 PASS，0 FAIL，0 SKIP**（五个测试类；包含新增验收场景，
不是七条业务 UC）。测试禁止 up-to-date/cache 跳过：

- `FlowJdbcTest`：队列仅写 flow，消费失败回滚/成功删除；外层回滚不撤销独立事务；
  日志复用同一池，关闭日志提供者不关闭宿主池；启动不自动建引擎表。
  新增双消费者竞争与独立 JVM 强杀：子进程在消费回调持有未提交事务时写领取凭证，
  父进程 `destroyForcibly()` 后验证消息重领、ACK 后不再被领取。
- `MissingFlowDataSourceTest`：只有 default 时事务包装器、迁移历史和锁均拒绝装配。
- `FlowMigrationTest`：原生完整迁移链、advisory lock 数据库归属、历史与脚本数量一致、
  空 schema 初始化、第二个新 Context 校验后不重复写历史，默认库无引擎表。
- `EmbeddedRuntimeTest`：六类服务真实启动；Java 发布→外部 Task→If→Log→结构化/大
  输出→终态；Cron 自动触发；大输出实际存储 URI；停止后宿主与池可用；新 Context
  可读持久化执行及文件输出。这里只验证完成后的重建，不等于运行中崩溃恢复。
- `PluginExportTest` 三项：外部 JAR 注册/加载/物化/基础导出；全部内置注册资源与实际
  Task 集合比对及全部导出；上游 CLI 聚合资源完整性、坐标与离线重载。

首次 PG 测试的直接 DSL 探针因缺少 Micronaut 连接上下文失败，改为从本任务连接池
显式借连接执行管理探针后通过。生产队列与事务仍通过真实注入的上游包装器执行。
构建中发现上游构建脚本的 SnakeYAML 类路径依赖和版本资源重复，已修复并重跑。

续验修复：

- 显式迁移仍有直接注入 default 的入口，增加 `FlowMigrationFactory` 绑定具名 flow。
  上游历史存储带 `@Replaces`，工厂再次替换会使候选 Bean 被级联过滤；通过条件关闭
  原实例、按历史接口提供具名实例修复，真实数据库验证未再写 default。
- 漏迁根 `lombok.config` 导致 WorkerTaskResult 无法反序列化，任务停留 SUBMITTED。
  补回原文件并声明编译输入，重编译后真实 Worker 链路通过。
- 插件 JAR 使用上游坐标文件名并生成独立 staging 目录，聚合目录可以识别其 Maven
  坐标，旧构建残留 JAR 不会参与扫描。

仍可见上游告警：PG 17.10 低于当前 JOOQ 方言声明支持版本、Prometheus 同名指标标签
不一致、服务停止时队列/锁清理告警。当前功能断言通过不代表这些运维项已验收，
K17 和 K19 保持未收口；后续需结合真实宿主的数据库与指标栈处理。

JUnit XML 与 HTML 保留在 `embedded/build/test-results/test/`、
`embedded/build/reports/tests/test/`。这是基础设施技术验证，未运行或
宣称完成业务 UC 回归；也不证明全部控制流、Worker 故障恢复或 CSES 装配。
聚合资源位于 `build/exports/plugins-schema.json`。

下一验收顺序：Worker/Executor 运行中强杀与调度停机补偿 → 复杂控制流/取消/重放 →
其它扩展协议及冲突诊断 → 真实 CSES 依赖、生命周期、端口和指标适配 → 交付与切换。
第一阶段结束前上述门槛均必须完成；不能提前转入 Route 或业务开发。
