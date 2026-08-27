# ADR 0013：集中 YAML 解析与 Flow 领域转换

## 状态

Accepted（其中过渡期 `FlowDefinition`、Draft 创建入口和迁移差距描述由
ADR 0014 取代；Input 定义片段的多态物化方式由 ADR 0019 的 2026-08-03 修订
取代；TaskExtension/Dispatcher、类型规范化和 Flow 直接消费 Map 的部署链路由
[ADR 0026](0026-use-task-class-as-in-project-plugin.md) 取代。集中、严格的 YAML
格式边界继续有效）

## 背景

现有 Flow 定义链路由 `YamlFlowDefinitionReader` 同时承担 YAML 语法解析、
Flow 字段映射和 Task 类型可用性检查，再由 `TaskDefinitionAssembler` 在
Handler 外部生成 Task 身份、父子关系和具体 Task 子类型。结果是“从定义输入形成
完整 Flow”的规则分散在 Reader、Service、Handler、Assembler、Dispatcher 和
Flow 之间。

已经确认的新边界要求：

- Core 提供扁平的 `serializers` 包，统一承载序列化格式能力。
- YAML 格式只由一个通用 `YamlParser` 解析。
- Flow 领域接收一个无系统身份、不可持久化的定义中间态。
- Flow 聚合负责把中间态转换为具有完整 Task 身份和父子关系的完整定义。

该决定只调整解析与领域构造职责，不改变 ADR 0008 已确认的
`FlowDraft`、部署和 `reversion` 目标生命周期。

## 备选方案

### 方案一：保留 Flow 专用 Reader 与外部 Assembler

改动最小，但 YAML 语法、Flow 映射、Task 身份和领域创建继续分散，Flow 无法独立
保护聚合内部实体的构造规则。

### 方案二：让 Flow 直接依赖 YAML ObjectMapper

能够把入口集中到 Flow，但领域对象会直接依赖 Jackson `ObjectMapper`、
`YAMLFactory` 和具体格式配置，以后任何格式细节都会进入聚合。

### 方案三：通用 Parser、现有 Flow 直接消费映射

`YamlParser` 只把 YAML 转成通用只读映射；不为解析结果新增
`FlowDefinitionInput`、`TaskDefinitionInput` 或其他平行模型。现有 Flow 聚合
在一次完整创建或部署中解释映射、生成 Task 身份和父子关系，并委托类型分派器
调用具体 Task 的静态 `create(...)`。

## 决策

采用方案三：

- 新增扁平包 `org.cses.flow.core.serializers`，包内不再按业务建立子目录。
- `YamlParser` 是唯一 YAML 语法入口；它使用 Jackson `ObjectMapper` 和
  `YAMLFactory`、拒绝重复键与尾随文档，并返回深度只读的字符串键映射。
- `YamlParser` 是无状态静态类；它不参与 Bean 注入，静态方法直接取得
  `JacksonMapper` 提供的 YAML Mapper。
- `YamlParser` 不导入 Flow、Task、Command、Repository 或具体 Task 扩展类型，
  不校验 Flow 字段和 Task 类型。
- `YamlParser` 返回的通用只读 `Map<String, Object>` 是唯一解析中间结果；它只在
  当前命令调用链中传递，不成为领域对象、聚合字段或持久化对象。
- 现有 `Flow.createDraft(...)`、`Flow.saveDraft(...)` 以及目标模型中的
  `Flow.deploy(...)` 直接接收该映射。Flow 解释自身字段和递归 Task 节点，
  统一复用或生成 Task ID、计算 `parentId`、校验系统字段，并保护 Flow key 与
  Task 树不变量。Input 定义片段是窄化例外：Flow 在当前物化调用内使用 PAAS
  JSON，以 `Input.class` 和 `type` 多态元数据直接形成具体 Input，不再通过项目
  私有 Mapper 选择类型。
- 现有 `FlowDefinition` 继续只表达已经物化的完整定义，不兼任解析半成品；
  本次迭代复用并增强 `Flow`，不新增同义定义模型。
- `TaskTypeDispatcher` 是 `core/plugins` 面向 Flow 提供的内部运行 Interface。
  Flow 向它传递
  已经提取的完整创建参数；Dispatcher 不包含具体类型分支，而是按 `type` 从
  `PluginRegistry` 的 `TaskExtension` 扩展点解析唯一实现。
- 每个 `TaskExtension` Bean 只声明一个稳定类型，并直接调用对应 Task 子类型的
  `create(...)` 或 `rehydrate(...)`。插件不生成身份、不解析 YAML，也不拥有
  Flow 聚合规则。
- `PluginRegistry` 在应用启动时收集 classpath 中的 `Plugin` Bean；TaskExtension
  扩展点内的类型经过 `trim + Locale.ROOT` 大写规范化后必须唯一，重复注册使
  应用装配失败。
- 删除 `FlowDefinitionReader`、`YamlFlowDefinitionReader` 和
  `TaskDefinitionAssembler`，不保留兼容别名。
- 不新增或恢复 `FlowDefinitionInput`、`TaskDefinitionInput`。

本 ADR 修订：

- ADR 0004 中将 YAML 实现固定在
  `core/services/flows/YamlFlowDefinitionReader` 的位置决策。
- ADR 0007 中关于 `YamlFlowDefinitionReader` 生产代码位置的决定。
- ADR 0008 中“解析器保持在领域边界之外”的表述：具体 YAML 库仍在领域对象
  之外，Flow 只直接消费 Parser 产生的通用只读映射。

## Kestra 参考实现与本项目取舍

本决策参考 Kestra 在 2026-07-28 的
[`FlowParsingService`](https://github.com/kestra-io/kestra/blob/38efc82b43a6d3b2425b55e27d6c4210cb98dfde/core/src/main/java/io/kestra/core/services/FlowParsingService.java)、
[`YamlParser`](https://github.com/kestra-io/kestra/blob/38efc82b43a6d3b2425b55e27d6c4210cb98dfde/core/src/main/java/io/kestra/core/serializers/YamlParser.java)
和
[`PluginDeserializer`](https://github.com/kestra-io/kestra/blob/38efc82b43a6d3b2425b55e27d6c4210cb98dfde/core/src/main/java/io/kestra/core/plugins/serdes/PluginDeserializer.java)、
[`PluginRegistry`](https://github.com/kestra-io/kestra/blob/38efc82b43a6d3b2425b55e27d6c4210cb98dfde/core/src/main/java/io/kestra/core/plugins/PluginRegistry.java)
与
[`DefaultPluginRegistry`](https://github.com/kestra-io/kestra/blob/38efc82b43a6d3b2425b55e27d6c4210cb98dfde/core/src/main/java/io/kestra/core/plugins/DefaultPluginRegistry.java)
的职责拆分：

1. 先把 YAML 解析成通用 Map，并在此阶段拒绝重复键等格式错误。
2. 反序列化器只提取 `type` 标识，注册表负责把标识解析为具体插件类型。
3. 再把 Map 物化为完整 Flow，由已解析插件构造具体 Task 实现。
4. 将未知类型、未知字段和语法错误关联到定义路径，避免只返回无上下文的转换
   失败。

Flow 项目采用相同的两阶段思路，但不把整棵 Flow/Task 交给 Jackson POJO 绑定：

- Kestra 的领域类型使用 Jackson Builder 和插件反序列化器完成对象构造；本项目
  的领域对象必须通过静态 `create(...)` 保护身份生成和聚合不变量。
- ADR 0019 确认的 Input 定义子类是局部例外：PAAS JSON 只负责依据 `type`
  多态实例化具体 Input，构造器仍负责 Input 自身不变量；Flow 身份、版本、Task
  树和插件类型仍由领域入口及注册表控制。
- Kestra 的“标识解析、注册表查找、具体类型物化”被保留为独立步骤；本项目用
  `PluginRegistry` 在 TaskExtension 扩展点查找实现，Dispatcher 只隐藏这条装配
  链路，Flow 仍负责稳定 ID、`parentId` 和完整 Task 树校验。
- ADR 0023 在 Micronaut classpath Bean 之外增加了 `ServiceLoader<Plugin>`，但
  仍不复制 Kestra 的插件目录扫描、独立 ClassLoader、安装卸载和版本选择。需要
  这些能力时应在注册表之外增加插件包管理层，而不是改变 Flow 的 YAML 物化协议。
- 当前项目没有插件版本注入和历史定义宽松读取需求，因此只保留严格解析链路，
  不增加 strict/lenient 双模式。
- YAML 语法错误保留行列位置，Flow/Task 物化错误保留映射路径；两类错误不混为
  一个无定位的“解析失败”。

## 理由

- 序列化格式与领域构造仍然解耦，Flow 不依赖 Jackson YAML API。
- Flow 成为 Task 身份、父子关系和完整聚合构造的唯一业务入口。
- 解析中间结果只是通用只读映射，不再制造与现有 Flow、FlowDefinition 或 Task
  重复的输入模型。
- 新增 Task 类型只需提供具体 Task、TaskExtension 和执行 Handler，不要求 Flow、
  Parser 或 Dispatcher 依赖具体扩展类，也不修改中心分支。
- Reader 与 Assembler 两层转发被移除，调用链更短且职责可以分别测试。

## 后果

- `core/serializers` 是 Core 技术目录中的明确例外：保持扁平，不应用业务分包
  规则。
- Command 只携带 Parser 返回的通用只读映射，不再重复拆分 Flow 字段和 Task
  列表，也不新增输入对象。
- 保存草稿时提前解析的当前实现仍是 ADR 0008 目标生命周期的迁移差距；完成
  `FlowDraft` 后，同一链路应移动到部署命令中，而不重新引入 Reader 或
  Assembler。
- PostgreSQL 重建继续使用 `TaskTypeDispatcher.rehydrate(...)`，不经过 YAML
  映射链路。
- 持久化 Task 引用未安装插件时，重建必须失败；系统不得回退到通用 Task。
- 动态插件安装、卸载、版本选择和 ClassLoader 隔离不在本 ADR 范围内。
- 架构测试需要验证 `serializers` 包扁平、`YamlParser` 与 Flow/Task 解耦，
  禁止恢复旧 Reader/Assembler 和定义输入类型，并验证 Dispatcher 不再包含
  具体 Task 类型分支。
