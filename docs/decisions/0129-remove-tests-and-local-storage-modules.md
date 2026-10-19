# ADR 0129：移除 tests 与 storage-local 模块

## 状态

Accepted（2026-10-16）。用户确认暂不需要 `tests` 和 `storage-local`，并明确要求
“连同依赖这两个模块的测试一起移除”。本决策修订此前模块清单及
[ADR 0124](0124-scope-micronaut-dependencies-by-module.md) 中对应模块的依赖归属。
内部 Micronaut、TOML 依赖管理、Java 21 下限、gen 和 PostgreSQL 存储协议保持不变。

## 背景

`tests` 原来承载多个模块共同使用的测试辅助代码、测试 Bean 和架构检查，不参与
生产运行。只删除模块依赖而不处理调用方，会使相关模块测试无法编译。用户选择
连同依赖这些支持能力的测试一并删除，不把辅助代码迁移到其他模块。
`storage-local` 原来提供文件系统存储插件，并被默认加入根运行依赖和模块测试。

core 已拥有 `StorageInterface`、存储插件 SPI 及条件工厂；
`FlowBeansFactory` 仅在没有 `StorageInterface` Bean 时创建配置指定的插件。
宿主已经可以提供实现，无需新增另一套适配或替换工厂。

根 `src/test`、来源清单、变更账本、原始构建备份及 `verify-upstream.py` 已在此前
提交删除。当前文档和验证脚本需要反映这个状态；历史 ADR 与按日期记录的验证证据
仍保留其当时含义。

## 备选方案

1. 仅取消模块注册，留下源码和原有依赖：仍需维护失效目录，且测试无法编译，不采用。
2. 把本地存储移入 core 生产源码，或提供空存储实现：继续内置不需要的生产实现，
   不能满足本次移除范围，不采用。
3. 删除模块并迁移辅助代码与测试存储以保留现有测试：不符合用户确认的移除范围，
   不采用。
4. 删除模块及依赖它们的测试与专用资源，保留不依赖二者的测试，生产存储通过现有
   契约由宿主提供：采用。

## 决策

- 删除 `tests/`、`storage-local/`、Gradle 模块注册和对应项目依赖。注册子模块从
  17 个变为 15 个，不创建同职责的替代模块。
- 删除这两个模块中的辅助代码、架构测试、本地存储实现及测试。依赖它们的其他
  模块测试按直接引用、继承、测试资源与装配依赖一并删除，并清理专用资源。
  不迁移辅助代码，不以复制、替代实现或禁用用例保留这些测试。
- 保留不依赖这两个模块的普通测试和数据库技术测试，以及实际使用的资源和依赖。
  其中包括 PostgreSQL 设置仓储、迁移及生成元数据验证。测试制品、处理器与
  任务配置是否保留由剩余调用方决定，不为已无使用方的支持能力保留占位配置。
- 不在 core 或其他模块的生产、测试源码中保留 `LocalStorage` 或
  `TestStorage` 替代实现；根运行依赖不再包含 `local` 存储插件。
- 插件管理器不再读取 `flow.storage.local.base-path` 推导仓库目录；继续使用
  `flow.plugins.management.localRepositoryPath` 或既有默认临时目录。
- core 保留 `StorageInterface`、存储插件工厂和相关文件、KV 消费契约。宿主必须
  提供实际 `StorageInterface` Bean，或引入存储插件并配置 `flow.storage.type`。
  缺少实现时明确失败，不提供自动回退或空实现。
- 宿主 Bean 使用现有 `@Requires(missingBeans = StorageInterface.class)` 让位
  机制；宿主管理其初始化和关闭。通过配置创建的存储插件继续由现有工厂执行
  `init()` 和 Context 关闭时的 `close()`，不改变插件生命周期协议。
- `FlowEngine` 仍要求 `flow.server-type: STANDALONE`；此配置启用的
  `ServerCommandValidator` 仍要求 `flow.storage.type`。提供宿主 Bean 不绕过该
  校验，宿主可以使用 `host` 等标识满足属性要求；此时存储工厂让位，不按该标识
  查找插件。未提供宿主 Bean 时，类型必须对应实际已注册的存储插件。
- 清理根测试源码删除后遗留的专属依赖和任务配置；仍被各模块使用的公共测试
  配置继续保留。默认验证执行使用模拟角色的 `FlowEngineTest`、真实 PostgreSQL
  上的 `PostgresSettingRepositoryTest` 和 `PostgresSortMetadataTest`，并执行
  构建、运行隔离与 Schema 检查；完整回归在独立 PostgreSQL 上执行全部保留的
  模块测试，不再涵盖已删除的完整引擎和存储集成场景。
- 验证入口移除对已删除 `verify-upstream.py` 的调用。当前结构和使用指南不再将
  根集成测试、来源账本或旧脚本视作可执行入口；历史记录不改写成新的验证结论。

## 理由

模块及其测试依赖链一起删除，使当前工程保留用户需要维护的实现和不依赖二者的测试。
现有存储 SPI 足以承接宿主实现，删除内置本地存储无需改变文件或 KV 协议，也无需
新建兼容层。本次移除会减少相应测试覆盖，不能再把以前的集成验证结果视作当前
版本已经完成运行验收。

## 后果与验证

根 Java Library 不再提供开箱即用的本地文件存储。已有调用方必须提供实际存储
实现；旧的 `flow.storage.type: local`、`flow.storage.local.base-path` 配置
不再有效。多进程 Worker 需要由宿主实现保证访问同一份存储数据。

验证应覆盖全模块生产与剩余测试编译、保留的普通测试和数据库技术测试、根运行依赖与 tooling
隔离、Schema 导出。源码与实际制品不能残留已移除模块或本地存储的替代实现。
既有失败与本次新增失败必须分开报告，删除的测试覆盖单独说明，历史全量结果不能
替代本次验证。Accepted 状态只表示边界已确认，不表示回归已经全部通过。

数据库技术测试覆盖其目标组件的真实 PostgreSQL 装配和读写；使用模拟角色的
`FlowEngineTest` 只验证生命周期协调。它们不能证明完整引擎执行和宿主生产存储
接入已通过验收，宿主仍需在实际存储实现与独立数据库环境下完成运行验证。

当前使用入口见 [Java 使用指南](../harness/java-engine.md)，目录职责见
[项目结构](../project-structure.md)，测试依赖归属见
[依赖管理指南](../harness/dependency-management.md)。

## 2026-10-16 验证记录

Java 21.0.12 下先执行 `./gradlew clean --offline`，再执行：

```bash
bash docs/harness/verify-flow.sh --full-regression --offline --no-build-cache \
  --rerun-tasks --console=plain classes testClasses jar :tooling:exportPluginSchemas
```

80 个任务全部实际执行，耗时 2 分 42 秒，构建成功。生产与剩余测试编译、JAR、
根与 tooling 运行隔离、插件及配置 Schema 导出均通过。10 个有测试的模块共
2,415 项：2,414 通过、1 跳过、0 失败。数据库技术测试使用脚本创建并清理的独立
PostgreSQL；这是移除依赖测试后的覆盖范围，不与此前全量测试数量等同。

16 个生产 JAR 及导出 Schema 均未包含已移除本地存储或测试支持类。
本机执行日志、逐模块统计和 JUnit XML 归档位于忽略目录
`build/module-removal-validation/`。以上结果不包含真实宿主存储或完整引擎运行验收。
