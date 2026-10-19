# ADR 0131：移除独立示例插件模块

## 状态

Accepted（2026-10-16）。用户确认移除 plugin-fixture，修订 ADR 0130 的模块数量。

## 背景

plugin-fixture 仅用于独立插件注册、加载和导出验证，不承担生产业务。
tooling 原先默认构建该示例插件 JAR，导致离线导出依赖额外模块。

## 备选方案

1. 保留独立验证模块：继续增加模块数量，不采用。
2. 把示例迁到其他模块：仍保留本次不需要的示例和打包链路，不采用。
3. 删除示例模块，显式指定实际插件目录：采用。

## 决策

- 删除 plugin-fixture 的源码、图标、构建文件与 Gradle 注册，当前共 12 个子模块。
- tooling 不再构建或默认加载示例插件；两项 Schema 导出任务均要求
  `-PschemaPlugins=/absolute/path/plugins`，目录必须存在，空目录仅加载内置插件。
- 未提供属性时报告明确错误；普通编译和测试不要求此属性。
- verify-flow.sh 创建并清理专属空插件目录，验证内置 Schema 导出。
- 保留原生插件 SPI、processor 和 Registry，不迁移 Echo、ScriptProbe、LocalProcess。
- 历史 ADR 和验证记录保留原始含义，当前使用说明及目录图同步更新。

## 理由与后果

减少无业务模块，离线工具直接面向调用方的插件目录。导出命令需补充目录参数；
生产依赖和运行能力不变。本轮不再验证已删除示例插件的独立 JAR 加载和执行。

## 验证

Java 21 下运行成功：

```bash
bash docs/harness/verify-flow.sh --full-regression --offline --console=plain classes testClasses :tooling:exportPluginSchemas
```

74 个任务中 10 个实际执行、64 个复用已有结果；现存测试结果合计 2,427 项，
2,426 通过、1 跳过、0 失败。最终输入跟踪调整后，另行清理并重跑 tooling 的
11 项测试，同时执行运行隔离与两项 Schema 导出，均通过。

两份 JSON 可解析且不含 `org.flow.fixture` 类型。成功导出后去掉 schemaPlugins
再次运行两项导出任务，均按预期失败并提示指定目录，验证增量检查不会跳过缺参
校验。普通构建不要求该参数。日志位于本地忽略目录
`build/plugin-removal-validation/`，包括 `regression.log`、`export.log`、`missing-directory.log`。
本轮不覆盖已删除示例插件的外部 JAR 验收。
