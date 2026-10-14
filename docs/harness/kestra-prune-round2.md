# 第二轮核心功能清理与验收

日期：2026-10-13。分支：`codex/kestra-core-migration`。
依据：[ADR 0118](../decisions/0118-prune-kestra-core-product-features.md)。
用户最终要求为不保留退出功能的兼容接口；保留执行、JDBC 队列、调度、通用扩展和
Task 导出，不引入 Route 或业务逻辑。本记录属于技术验收，不修改历史业务 UC。

结论：本轮源码清理与保留能力的专项验收通过；完整默认回归仍有 5 项既有失败，
不宣称全量测试通过或第一阶段核心迁移整体完成。

后续清除退出功能的源码残留，详见 [源码残留清理与最终验收](kestra-prune-unused.md)。
本记录保留第二轮结束时的原始统计。

## 清理结果

相对本轮开始前的快照，删除 226 个源码、资源与专用测试文件。清理按功能调用链
覆盖 core/script 与它们在 JDBC、Worker/Controller 中的引用，注册模块数保持 22。

| 范围 | 已删除 | 继续保留 |
| --- | --- | --- |
| AI/MCP | 助手会话、消息、MCP Server/Session、McpToolTrigger、专用 Repository、广播入口、标签与授权查询接口 | 普通 Flow/Execution 查询、原生 Trigger 和执行协议 |
| 产品运维 | 匿名使用上报及 gRPC 上报服务、管理端点 Basic Auth、升级提示 | Worker 连接、心跳、日志、指标、加密配置下发、实例版本及迁移版本检查 |
| 插件获取 | 官方插件目录、自动安装服务/任务及专用 Java 迁移 | Registry、Scanner、ClassLoader、processor、手动 Maven/JAR 管理、离线 bundle |
| Dashboard | 模型、Chart/DataFilter SPI、注册分支、校验、查询服务、Repository 专用查询、文档/图标/Schema | QueryFilter、普通执行/日志/指标查询、统计与持久化 |
| script | 内置 Docker、docker-java、RunnerType/DockerOptions 及关联旧方法 | AbstractExecScript、CommandsWrapper、ScriptOutput、Kotlp；core 中的 Process 和 TaskRunner SPI |

原生 If、Switch、Loop、Subflow、Pause、Retry、Timeout 等能力保留。Storage、Secret、
Namespace/KV、Assets、App、FileRenderer、日志等通用扩展未作本轮裁剪。

脚本必须显式配置 `taskRunner`。已退出的 Docker 类型、`runner`、`docker` 配置在严格
解析中拒绝；没有把它们自动转成宿主 Process 执行。外部插件需面向保留的 API 编译。
未知插件类型在 Flow 校验中报错，离线提示改为显式安装，不再承诺保存时自动安装。

## 数据源与迁移边界

宿主仍提供可配置的 `datasources.flow`；JDBC 队列、Repository、日志和显式迁移使用
同一数据源，连接池及 ApplicationContext 归宿主。缺少 flow 时失败，不回退 default。
退出功能的 Java Repository/Bean 不再注册。现存 20 个 H2/PostgreSQL SQL 资源逐个核对
上游 SHA-256，内容均未变化；历史表暂留，没有对真实业务库执行删除或结构迁移。

本轮将上游 PostgreSQL 测试的连接参数改成支持 `FLOW_KESTRA_UPSTREAM_TEST_*`
环境变量，包括原先硬编码地址的时区测试。生产数据源配置不受这些测试变量影响。
首次完整运行发现上游 `jdbc-postgres` 的 `application-queue.yml` 错配为 H2，
`V2_0QueueMigration` 因此未注册，7 个 Broadcast/Dispatch 用例报队列表不存在。
已改为 PostgreSQL，保留所有测试断言；新临时库中完整队列测试组 24 项全部通过。
验收脚本仅创建和清理自己持有的 PostgreSQL 17 容器，其中 `flow`、`control`、
`kestra_unit` 三个库分别用于嵌入引擎、default 反向检查和上游 PostgreSQL 回归。

## 扩展和导出证据

独立 `plugin-fixture` 新增无业务的 ScriptProbe 和 LocalProcess，前者继承通用脚本
基类，后者扩展原生 Process。它们通过 processor 生成注册资源，构建成独立 JAR，
从配置目录加载，不作为宿主普通 classpath 依赖。

- 真实 JDBC 队列执行外部脚本，检查退出码、结构化变量和存储中的输出文件内容。
- 保留外部 Echo、原生 If、持久化大输出、Context 重建回读和真实 Cron 验证。
- 导出覆盖全部 51 个已注册 Task，包含 62 份 Schema 和 183 个导出文件。
- 聚合资源为 Flow/Task/Trigger 三个根、123 个定义、1 个外部插件坐标。
- 对比清理前的 290 个定义：退出 170 个 Dashboard/MCP 定义，新增 Process、
  ScriptProbe、LocalProcess 三个定义。120 个保留定义逐项比较，只允许新增验证
  Task 引用、去掉 McpToolTrigger 引用和 WorkingDirectory 的 Process 示例调整，
  没有其它差异。全部内部 JSON Pointer 均能解析，bundle 可离线重载。

文件渲染器 SPI 保留；验证插件没有实现文件渲染器，因此此目录仍为空，不宣称其
实际渲染路径已由本轮插件测试覆盖。

## 执行记录

```bash
./gradlew :embedded:classes :script:classes :tooling:classes testClasses
bash docs/harness/verify-kestra-embedded.sh
./gradlew :script:test :worker:test \
  --tests 'io.kestra.worker.services.GrpcWorkerConnectionServiceTest' \
  :core:test --tests 'io.kestra.core.validations.SafeRegexValidationTest' \
  --tests 'io.kestra.core.docs.*' --tests 'io.kestra.core.models.QueryFilterTest' \
  --tests 'io.kestra.core.services.VersionServiceTest'
bash docs/harness/verify-kestra-embedded.sh --full-regression
python3 verify-upstream.py
```

使用 Gradle Wrapper 9.7.1、Java toolchain 25。

| 检查 | 当前结果 |
| --- | --- |
| 全模块生产与测试编译 | 通过 |
| 临时 PostgreSQL 嵌入技术测试 | 8 项通过，无失败/跳过 |
| 离线配置 Schema 工具测试 | 11 项通过 |
| core 文档/Schema、通用过滤、版本及正则定向回归 | 893 项通过 |
| script 默认测试 | 18 项通过 |
| Worker 加密下发定向测试 | 2 项通过 |
| H2 默认后端回归 | 995 项，994 通过、1 项上游跳过，无失败 |
| 最终 PostgreSQL 队列组复验 | 24 项通过，包含首次失败的全部 7 项 |
| 最终插件 bundle/扫描/注册复验 | 17 项通过 |
| embedded/tooling 生产依赖隔离 | 通过，不含 docker-java/Pulsar/已退出产品模块 |
| 默认全部 test（含上游 PostgreSQL）初跑 | 6,323 项：6,277 通过、12 失败、34 跳过，耗时 24 分钟 |
| 来源清单 | 3,508 个原始条目；累计退出 819、迁移 3、原路径适配 76，其余与固定上游相同 |

脚本日志测试原先依赖 Docker 运行器的额外日志数量；切换 Process 后，已调整为
逐条检查脚本实际发出的四条结构化日志及其级别，不删除日志协议断言。

初跑只在 core 与 jdbc-postgres 失败，进程退出码为 1。Gradle 实际执行 54 个任务，
75 个任务复用 up-to-date 结果；没有把整条命令记作成功。各模块统计如下：

| 模块 | 总数 | 通过 | 失败 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| core | 3,555 | 3,543 | 5 | 7 |
| embedded | 8 | 8 | 0 | 0 |
| executor | 199 | 191 | 0 | 8 |
| indexer | 3 | 3 | 0 | 0 |
| jdbc | 88 | 77 | 0 | 11 |
| jdbc-h2 | 995 | 994 | 0 | 1 |
| jdbc-postgres | 858 | 844 | 7 | 7 |
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

core 默认回归为 3,555 项，其中 5 项失败、7 项跳过。
失败与上一轮相同：`PebbleDocBasicMdDriftTest` 的 3 项无法找到未迁入的
`ui/src/assets/docs/basic.md`；`RequestRunnerTest` 的 2 项外网 HTTP 流程失败，
日志再次记录 `Illegal unicode code point in request body: 2044`。这些测试未删改、
未新增跳过规则。默认 test 沿用上游排除 `flaky` 标签的规则。

最终源码复验执行以下命令，耗时 1 分 58 秒、退出码 0。全模块测试重新编译，
嵌入 8 项、工具 11 项、PostgreSQL 队列 24 项、插件 17 项全部通过；依赖隔离、
Schema 生成及来源校验通过。该次为定向复验，没有将它替换为一份“全量全绿”报告。

```bash
bash docs/harness/verify-kestra-embedded.sh \
  :jdbc-postgres:test --tests 'io.kestra.queue.postgres.*' \
  :core:test --tests 'io.kestra.core.plugins.PluginSchemaBundleServiceTest' \
  --tests 'io.kestra.core.plugins.PluginScannerTest' \
  --tests 'io.kestra.core.plugins.RegisteredPluginTest' testClasses
```

两次临时验证容器均由各自脚本清理。`git diff --check` 通过。
本轮未提交、推送或合并；同分支此前的源码级迁移改动仍保留。

本轮专项不替代 [第一阶段台账](kestra-core-migration.md) 的全部门槛；真实 CSES
依赖装配、Worker 执行中强杀恢复、调度停机补偿、多引擎等仍须另行完成验收。

## 恢复点与证据位置

本轮前快照：`/tmp/flow-kestra-prune-round2-20261013-105319.tar.gz`，3,240 个文件，
归档 SHA-256：`1edac510d3d53e38e0b4133d5315dc1f45bcac3a4e6b61f07f8d5699997b5c92`。
不含 `.git`、构建缓存、IDE 与 Agent 配置；这是本轮源代码检查点，不代替旧 Flow
[完整备份](kestra-source-backup.md)。恢复时先解压到其它目录核对，避免覆盖后续工作。

- `build/validation/prune-round2/checkpoint.json`：清理前每个文件的哈希。
- `build/validation/prune-round2/source-delta.json`：本轮删除和修改列表。
- `build/validation/prune-round2/baseline-plugins-schema.json` 与 `schema-delta.json`：导出对比证据。
- `build/validation/prune-round2/focused-results/`：完整回归前保存的专项 JUnit XML。
- `build/validation/prune-round2/regression-results/`：初次完整回归 XML 与逐模块统计，避免后续定向复验覆盖原始失败证据。
- `build/validation/prune-round2/final-results/`：最终源码复验 XML 与统计。
- 各模块 `build/test-results/test/` 与 `build/reports/tests/test/`：最近一次回归结果。
- `/tmp/flow-kestra-prune2-compile.log`、`/tmp/flow-kestra-prune2-embedded.log`、
  `/tmp/flow-kestra-prune2-focused.log`、`/tmp/flow-kestra-prune2-regression.log`、
  `/tmp/flow-kestra-prune2-final.log`：本机过程日志。

`/tmp` 与 `build` 属于本机临时证据，可能被系统或后续 clean 清除；结论和命令以本文为准。
逐项源码来源以不可变 `upstream-manifest.json` 和最新 `upstream-changes.json` 为准。
