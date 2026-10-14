# Kestra 第一轮清理记录

日期：2026-10-13；分支：`codex/kestra-core-migration`。
依据：[ADR 0117](../decisions/0117-prune-kestra-product-entrypoints.md)。

## 范围与恢复点

- 删除完整 `cli`、`webserver`、`jdbc-mysql`，移除共享测试的 `Problems` Web 断言。
  同时删除引用 CLI 格式化类的 `core/src/main/resources/logback/gcp.xml` 发行版日志配置；
  该资源的原件保存在旧工程完整备份的 `flow/kestra-engine/core/` 下。
- 将聚合与配置 Schema 生成器提取到 `tooling`；保留 Task 文档、图标、Schema、示例
  导出服务，以及原生 Flow 校验/迁移计划/执行/修复/锁管理 Java 入口。
- 未修改原生执行、JDBC 队列、调度与插件 SPI 协议；继续绑定宿主 `datasources.flow`。
  Worker Controller/gRPC、Indexer、H2/内存测试后端仍保留。Route 和业务尚未加入。
- 原始清单 3,508 项不变；逐文件登记删除 593 项、迁移 3 项、原路径适配 6 项。
  模块由 24 个减至 22 个（删除 3 个，新增 1 个）。

本轮改动前额外复制并逐文件 SHA-256 核验 936 个相关源码、配置和文档文件：

`/System/Volumes/Data/workspace/java/flow-backups/before-kestra-prune-round1-20261013-100839`

该目录的 `checkpoint-sha256.json` 为校验凭据，不含模块 build 产物，也不是整个仓库的
完整备份。要恢复本轮涉及的文件，从该检查点恢复对应路径，并删除新增 tooling、
upstream-changes.json 与 ADR 0117 等本轮文件；应先保存此后产生的修改。
旧 Flow 的完整恢复仍使用 [原工程完整备份](kestra-source-backup.md)。

## 已完成验证

1. `./gradlew :tooling:test :tooling:exportPluginSchemas :tooling:exportConfigurationSchema
   :tooling:verifyRuntimeIsolation :embedded:verifyRuntimeIsolation`：成功。
   配置 Schema 原生测试 **11/11 通过**，无失败/跳过。工具运行依赖不含 CLI/Web、
   数据库后端或执行服务；embedded 不依赖 tooling。
2. 新 `plugins-schema.json` 与清理前快照解析成 JSON 后做全量结构比较：**完全相等**。
   保留 4 类根、290 个共享定义、1 个外部插件坐标和空的外部 renderer 目录。
   空 renderer 目录来自当前 fixture 无该扩展，不代表 renderer SPI 已完成验收。
3. `bash docs/harness/verify-kestra-embedded.sh`：成功，一次性 PostgreSQL 的嵌入技术
   测试 **7/7 通过**，无失败/跳过。涵盖队列 ACK/失败回滚/竞争/持锁进程强杀后重领、
   原生 If、Cron、外部插件执行、输出持久化、生命周期与数据源所有权。
   Task 导出仍为 50 个 Task、73 个 Schema、218 个文件，并验证聚合离线重载。
4. 显式管理改用原生 `runOrFailIfLocked()`。首次 `pendingScripts()` 只创建迁移历史，
   尚不创建 flows；执行迁移后完整建表。新 Context 重跑不重写历史，default 库无写入。
5. 来源校验通过；当前 Gradle 模块依赖与 Java import 不再引用被移除模块。保留的
   `upstream-build/`、原始清单、变更清单和历史记录中的旧路径是来源证据。

## 更大范围回归

共享测试模块被所有原生模块使用，因此进一步执行：

```bash
./gradlew test --continue -x :jdbc-postgres:test -x :embedded:test
```

本条命令耗时 20 分 4 秒，退出码 1，仅 `:core:test` 失败。报告合计 **5,670 项：
5,636 PASS、7 FAIL、27 SKIP**；其中 tooling 的 11 项复用本轮此前成功结果，
其余有测试的模块均实际执行。原始报告快照和 counts.json 保存在
`build/validation/prune-round1/regression/`，不会被后续定向复验覆盖。

| 模块 | 总数 | 通过 | 失败 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| processor | 7 | 7 | 0 | 0 |
| core | 3664 | 3650 | 7 | 7 |
| queue | 43 | 43 | 0 | 0 |
| jdbc | 92 | 81 | 0 | 11 |
| executor | 199 | 191 | 0 | 8 |
| worker | 126 | 126 | 0 | 0 |
| worker-controller | 194 | 194 | 0 | 0 |
| scheduler | 140 | 140 | 0 | 0 |
| indexer | 3 | 3 | 0 | 0 |
| storage-local | 68 | 68 | 0 | 0 |
| script | 52 | 52 | 0 | 0 |
| tests | 6 | 6 | 0 | 0 |
| repository-memory | 1 | 1 | 0 | 0 |
| runner-memory | 1 | 1 | 0 | 0 |
| jdbc-h2 | 1063 | 1062 | 0 | 1 |
| tooling | 11 | 11 | 0 | 0 |

运行中发现日期测试受 macOS/JDK 的 `user.script=Hans`
影响：只设置 en/US 后实际为 `en_US_#Hans`，时间缩写输出 GMT+08:00，且无法解析
英文 July。独立 Java 25 探针清空 script 后恢复 AWST 与日期解析；根测试配置补充
`user.script=`，只影响测试 JVM。日期两个测试类独立复验 **21/21 通过**。

首批失败分布：

- `DateAddFilterTest.toDateAdd`、`DateFilterTest.dateStringFormat`：上述区域设置问题。
- `PebbleDocBasicMdDriftTest` 三项：缺少 `ui/src/assets/docs/basic.md`。该测试及生产
  表达式源码未改，初始迁入清单本身没有 UI；这是此前来源/测试资源缺口，本轮未删除
  或放宽其断言。
- `RequestRunnerTest` 两项：访问 Google 的 HTTP 任务失败，输出记录响应正文被原生
  `Request` 拒绝（`Illegal unicode code point ... 2044`）。请求实现和测试仍与固定上游
  哈希一致；进一步根因及修复未在本轮完成，不能仅凭源码未改就宣称无回归风险。


定向复验命令：

```bash
./gradlew :core:test \
  --tests 'io.kestra.core.runners.pebble.filters.DateAddFilterTest' \
  --tests 'io.kestra.core.runners.pebble.filters.DateFilterTest' \
  --tests 'io.kestra.core.runners.pebble.PebbleDocBasicMdDriftTest' \
  --tests 'io.kestra.plugin.core.http.RequestRunnerTest'
```

结果 **27 项：21 PASS、5 FAIL、1 SKIP**，仍为上述 UI 文档 3 项及 HTTP 2 项；
退出码 1，27 秒。定向报告保存在 `build/validation/prune-round1/recheck/core/`。
没有删除、禁用或放宽失败断言，也没有重跑并宣称整个 5,670 项集合已经通过。
重建 Core JAR 后确认 `logback/gcp.xml` 不再残留于资源包。

原生 PostgreSQL 全套测试需要其专用环境，未由本条命令
执行；embedded 已由上面的隔离 PostgreSQL 独立实测。该命令不运行标记为 flaky 的
测试，沿用上游默认测试任务配置。

## 最终专项复验

最终代码（包括 GCP 资源清理、测试区域配置修正和更新后的 harness）重新执行
`bash docs/harness/verify-kestra-embedded.sh`：**退出码 0，35 秒**。
嵌入 PostgreSQL 技术测试 **7/7**、配置 Schema 测试 **11/11** 全部通过；两种
Schema 导出和两项运行依赖隔离检查通过。临时数据库容器及卷由脚本正常清理。
最终聚合资源再次与清理前 JSON 全量结构比较一致，来源校验与 `git diff --check` 通过。

第一轮代码清理已经完成，广回归的 5 项剩余失败和原有完整核心阶段缺口仍未关闭。

## 证据与剩余范围

- JUnit XML/HTML：`tooling/build/test-results/test/`、`embedded/build/test-results/test/`
  及相应模块 `build/reports/tests/test/`。
- 清理前聚合快照与结构比较凭据：`build/validation/prune-round1/baseline-plugins-schema.json`
  和同目录的 `schema-parity.json`（原文件键顺序可能变化，因此比较解析后的全量 JSON）。
- 构建、PG 与回归日志：`/tmp/flow-kestra-prune-tooling.log`、
  `/tmp/flow-kestra-prune-acceptance.log`、`/tmp/flow-kestra-prune-regression.log`、
  `/tmp/flow-kestra-prune-recheck.log`、`/tmp/flow-kestra-prune-final-acceptance.log`。
- 生成文件：`build/exports/plugins-schema.json`、`build/exports/configuration-schema.json`。

这是核心裁剪的技术验证，不是业务 UC 或真实 CSES 接入验收。完整阶段仍缺 Worker
运行中故障恢复、调度补偿、复杂控制流、其它 SPI 和宿主依赖/端口等证据，沿用
[迁移台账](kestra-core-migration.md)。前一批根回归出现的缓存计数失败保留在该台账，
不能被本轮单项成功覆盖。第一轮未执行数据库业务迁移、提交或推送。
