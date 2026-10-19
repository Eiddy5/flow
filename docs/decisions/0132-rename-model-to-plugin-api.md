# ADR 0132：将 model 更名为 plugin-api

## 状态

Accepted（2026-10-16）。用户确认模块改名，同时更新包名、引用和文档。

## 背景

原 model 只有插件接口、注解和语言枚举，Flow、Task、Execution 等领域模型位于
core。model 名称不能准确表达该模块职责。

## 备选方案

1. 保持 model：名称继续含混，不采用。
2. 合入 core：processor 依赖这些定义，core 编译又使用 processor，会产生依赖循环。
3. 更名为 plugin-api，并迁移 API 包名：采用。

## 决策

- Gradle 子项目和目录改为 plugin-api，发布坐标改为 `org.flow:plugin-api`。
- 接口为 `org.flow.plugin.api.Plugin`；七个注解保留 annotations 子包，
  MonacoLanguages 保留 enums 子包，均以 `org.flow.plugin.api` 为根。
- core 与 processor 依赖 plugin-api；不改动核心领域对象所在包和模块。
- 更新所有源码、测试、注解名称字符串及处理器引用。SPI 注册资源统一为
  `META-INF/services/org.flow.plugin.api.Plugin`，由 processor 在编译时生成。
- 不保留旧坐标或包名桥接。外部插件须更新导入后重新编译。
- 两个保留的预编译测试插件同步重定位类引用与 SPI 文件名；保留许可证和 Maven
  来源元数据。重放命令为 `python3 docs/harness/relocate-plugin-fixtures.py --plugin-api-only`。

## 理由与后果

名称与职责一致，编译期处理器仍只依赖公共 API，避免依赖整个引擎。
这是插件二进制 API 变更；现有外部插件 JAR 不能直接继续使用旧类型。
历史 ADR 和执行记录保留当时名称，当前目录说明与接入文档更新。

## 验证

Java 21 下先执行 `./gradlew clean --offline --console=plain`，再运行：

```bash
bash docs/harness/verify-flow.sh --full-regression --offline --no-build-cache --console=plain build :tooling:exportPluginSchemas
```

89 个任务全部实际执行成功，现存测试合计 2,427 项：2,426 通过、1 跳过、0 失败。
原 core 的三项 Plugin 接口测试归入 plugin-api，测试总数不变。构建、运行隔离和
两项 Schema 导出通过。随后执行加强后的加载断言：

```bash
./gradlew :core:test --tests org.flow.core.plugins.PluginScannerTest :plugin-api:test --offline --console=plain
```

插件扫描两项测试通过，验证外部示例任务被加载，且两份插件制品中的插件类实现
新的 Plugin 接口。12 个模块 JAR 均没有旧插件 API 类名和注册路径。两个测试插件
的许可证及 Maven 来源内容未变，重定位可重复执行。

证据归档于本地忽略目录 `build/plugin-api-validation/`，含全量日志、完整 JUnit
结果压缩包、测试汇总、制品检查结果和定向检查日志。本轮不代表宿主完整引擎或
业务 UC 的运行验收。
