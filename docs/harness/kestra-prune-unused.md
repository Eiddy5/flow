# 已退出功能的源码残留清理

日期：2026-10-13。分支：`codex/kestra-core-migration`。
依据：[ADR 0118](../decisions/0118-prune-kestra-core-product-features.md) 的补充决策。
本次承接用户“将没用的从代码中也清理掉”的要求，实际删除无用源文件和调用链，
不增加 Route 或业务逻辑，也不变更旧 Flow 的业务 UC。

结论：源码残留清理及保留能力的最终专项验收通过。完整默认回归仍有 5 项既有失败，
不宣称全量全绿或第一阶段迁移整体完成。

## 范围和结果

相对本次开始前快照，删除 46 个文件：40 个生产源文件、6 个专用测试文件。
按模块计为 core 41、jdbc 2、jdbc-h2 1、jdbc-postgres 1、script 1。
删除依据同时检查了当前源码/资源引用、固定上游中的原调用方、Micronaut 注册、
ServiceLoader、序列化和插件导出。没有把“没有静态调用”单独作为删除插件 API 的依据。

| 链路 | 实际删除/收敛 | 保留和验证边界 |
| --- | --- | --- |
| CLI/Web 外围 | FlowAutoLoader、FlowWithPath、Graph2DotService；Web 文档响应 DTO、文件元数据 DTO、专用异常 | 原生 Flow/Task/Execution、GraphService、实际运行协议 |
| 表达式编辑/AI | ExpressionContextService、ExpressionContext/Category、DisplayExpressionRenderer 及专用测试 | VariableRenderer、Pebble 表达式求值、任务执行和原生条件 |
| 插件前端 | PluginUiManifest/Module/ModuleWithGroup/Distribution、TaskWithVersion、TicketingTaskInterface；Scanner 的 UI manifest 读取及 RegisteredPlugin 的对应字段 | 通用插件注册、独立 JAR/ClassLoader、processor、Task Markdown/Schema/SVG 导出；App/FileRenderer 等扩展 SPI |
| Web 日志订阅 | LogStreamingService、FollowLogEventMatcher、FollowLogEvent、队列工厂对应方法 | LogEntry → JDBC Dispatch → Indexer → 日志持久化；Webhook 仍依赖的执行事件流 |
| Web 密码/匿名实例 | V2_0_04BasicAuthPasswordMigration、专用 H2/PG 测试、AuthUtils 密码散列方法、InstanceService 和 INSTANCE_UUID | gRPC BasicAuth 的常量时间比较、引擎节点 ID、版本与迁移检查、历史 SQL 原文 |
| 旧类型和工具 | 无注册/引用的旧统计 DTO、TaskResult、ExecutionResumed、ScheduleContextInterface、FailedResponseInterceptor、ScriptOutputFormat；Dashboard/Docker 专用 MathUtils、UnixModeToPosixFilePermissions 及测试 | 当前统计 Repository、命令驱动执行/恢复、HTTP 响应校验、脚本输出处理 |
| 兼容与配置 | warningOnStdErr 字段、旧脚本 beforeCommands 方法、Web 指标常量、测试中的匿名上报/旧 Web BasicAuth/access-log 配置 | CommandsWrapper 的实际脚本组装/错误处理、配置校验、输出文件能力 |
| 依赖 | main 的 micronaut-http-server-netty、bcprov 直接依赖；LangChain4j BOM、Copilot/MCP、Playwright 平台声明 | HTTP 客户端、测试所需 HTTP Server/密码库、gRPC；其它通用插件平台约束 |

日志发射器现在把日志 Dispatch 队列的确认直接返回给调用方，单条和批量入队的
异步失败都能继续向上传播。移除的是没有消费者的 Web 广播，不是日志本身。
新增 4 项队列确认/失败传播测试，原有日志内容、级别、密文遮罩与批量检查保留。
嵌入运行测试补充“真实 PostgreSQL 写入日志、关闭并重建 Context 后回读”的断言。

datasources.flow、显式迁移与宿主连接池/Context 所有权不变。现有 SQL 文件、历史表
和 settings 数据未删除；没有连接或操作真实业务库。移除的纯 Web Java 密码迁移
不再注册，历史 SQL 的内容与 checksum 保持不变。

部分公共工具没有本仓库内的生产调用，但承担外部扩展用途，例如 StatefulTriggerService、
LogRecordMapper、QueueCache、NoopCache，继续保留。弃用标记不等于无用途；被实际
执行链使用的协议不会为了减少文件数一并删除。

## 验证

```bash
./gradlew :embedded:classes :script:classes :tooling:classes testClasses
bash docs/harness/verify-kestra-embedded.sh --full-regression
python3 verify-upstream.py
```

全模块生产和测试编译通过。完整默认回归耗时 23 分 12 秒、退出码 1，共 6,281 项：
6,248 通过、6 失败、27 跳过。Gradle 执行 46 个任务、83 个 up-to-date，统计包含
命令复用的有效结果。沿用原有 flaky 排除策略；没有新增跳过规则。

其中一次失败来自 FlowMigrationTest 仍使用历史“迁移数大于 10”的断言。删除 Web
密码迁移后，该计数已不适用；已改为精确校验当前 10 个必保脚本 ID 集合，并继续
检查实际建表、记录数与注册脚本数相等、全部执行成功、default 数据源未写入、
Context 重建后不重复执行也不改写历史。最终新容器中该测试通过，没有简单降低
数量门槛或移除迁移断言。

其余 5 项与第二轮基线相同，测试源码哈希未改动：

- PebbleDocBasicMdDriftTest 的 3 项：未迁入的 `ui/src/assets/docs/basic.md` 缺失。
- RequestRunnerTest 的 2 项：外网 HTTP 流程仍记录 `Illegal unicode code point in request body: 2044`。

以下是完整运行的原始统计，保留初次迁移断言失败，不用最终专项覆盖它：

| 模块 | 总数 | 通过 | 失败 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| core | 3519 | 3507 | 5 | 7 |
| embedded | 8 | 7 | 1 | 0 |
| executor | 199 | 191 | 0 | 8 |
| indexer | 3 | 3 | 0 | 0 |
| jdbc | 88 | 77 | 0 | 11 |
| jdbc-h2 | 992 | 991 | 0 | 1 |
| jdbc-postgres | 855 | 855 | 0 | 0 |
| processor | 7 | 7 | 0 | 0 |
| queue | 43 | 43 | 0 | 0 |
| repository-memory | 1 | 1 | 0 | 0 |
| runner-memory | 1 | 1 | 0 | 0 |
| scheduler | 140 | 140 | 0 | 0 |
| script | 18 | 18 | 0 | 0 |
| storage-local | 68 | 68 | 0 | 0 |
| tests | 6 | 6 | 0 | 0 |
| tooling | 11 | 11 | 0 | 0 |
| worker | 128 | 128 | 0 | 0 |
| worker-controller | 194 | 194 | 0 | 0 |

最终源码复验命令：

```bash
bash docs/harness/verify-kestra-embedded.sh \
  :core:test --tests 'io.kestra.core.runners.RunContextLoggerTest' \
  --tests 'io.kestra.core.runners.DefaultLogEntryEmitterTest' \
  --tests 'io.kestra.core.utils.AuthUtilsTest' testClasses
```

耗时 1 分钟、退出码 0，43 项全部通过，无失败/跳过：embedded 8 项、core 24 项、
tooling 11 项。该命令使用另一个独立 PostgreSQL 17 容器，并完成全部测试类编译、
embedded/tooling 生产依赖隔离、配置 Schema 生成和来源校验。它是定向复验，
没有再次运行完整 6,281 项并声称全部通过。

扩展/产物验收：

- 独立插件 JAR 经 processor 生成注册资源、ClassLoader 加载；所有 51 个注册 Task
  均导出，包含 62 个 Schema、183 个 Markdown/JSON/SVG 文件。
- 聚合 Schema 仍为 Flow/Task/Trigger 三个根、123 个定义。与清理前比较只有两处
  差异：ScriptProbe 的 warningOnStdErr 属性移除、WorkingDirectory 文档示例移除
  相同配置。其它定义无变化；bundle 离线重载和所有内部 JSON Pointer 检查通过。
- 全部 40 个本次删除的生产类型在当前 JAR/Bean 注册资源中均不存在；当前生产
  源码/文本资源无对它们的引用。历史 20 份 SQL 资源逐一哈希核对，内容未变。
- 不再引入生产 HTTP Server Netty、Docker、Pulsar 或已退出产品后端。HTTP 客户端
  和实际需要的 gRPC 保留；没有把所有 Netty 依赖一概删除。

两次验证容器均由脚本清理，结束后无 `flow-kestra-check-` 容器残留。
`git diff --check` 和来源核对通过。

## 来源与恢复点

原始上游清单仍为 3,508 项；本次后累计退出 865 个文件、迁移 3 个，原路径适配
88 个。`upstream-changes.json` 逐项登记，`verify-upstream.py` 核验当前源码。

本次开始前快照：`/tmp/flow-kestra-prune-unused-20261013-115918.tar.gz`，3,019 个文件，
SHA-256：`7f355f26271e809c8890230698e1dc1b02ea193f4ff70e1b513d6d723bd7dbd6`。
不含 .git、构建缓存、IDE/Agent 配置，不替代此前的旧 Flow 完整备份。恢复前先解压
到其它目录核对，不直接覆盖当前工作区。

本机证据目录 `build/validation/prune-unused/`：

- checkpoint.json：清理前源文件哈希与快照信息。
- removed.json、source-delta.json、changes.diff：本次删除清单和变更证据。
- baseline-plugins-schema.json、schema-delta.json：聚合导出的前后比较。
- static-checks.json、artifact-checks.json：源码/资源引用、SQL 哈希和 JAR 残留检查。
- regression-results/：完整运行原始 JUnit XML、逐模块统计和失败证据。
- final-results/：最终 43 项复验的 JUnit XML、统计和来源清单哈希。
- 验证日志：`/tmp/flow-kestra-prune-unused-compile.log`、`/tmp/flow-kestra-prune-unused-regression.log`、`/tmp/flow-kestra-prune-unused-final.log`。

本次不提交、推送或合并。技术清理不能替代 [阶段一台账](kestra-core-migration.md)
中的真实 CSES 装配、Worker 运行中强杀恢复、调度停机补偿和多引擎验收。
