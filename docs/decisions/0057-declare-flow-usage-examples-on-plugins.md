# ADR 0057：在插件元信息中声明完整 Flow 使用示例

## 状态

Accepted（扩展 ADR 0027 的插件描述元信息与详情查询协议，并修订插件示例格式）

## 背景

ADR 0027 允许插件通过 `@Plugin` 声明标题和描述，并根据具体 Task 类生成字段级
JSON Schema。Schema 能说明一个字段接受什么数据，但不能表达完整 Task 如何组合、
编排 Task 如何包含子 Task，或者一个插件在 Flow YAML 中的典型用法。若这些示例只
保存在页面或独立文档中，插件类、插件目录和使用说明会再次形成多套事实。

示例属于插件定义文档，不参与 Task 运行、类型解析或页面布局。宿主已有插件还必须在
不修改源码的情况下继续注册，因此新增示例不能成为所有插件的强制启动条件。

## 备选方案

### 方案一：只在接入手册中维护示例

不改变插件接口，但插件与文档容易分别演进，插件详情查询也无法把示例提供给编排工具。

### 方案二：把 `@Example` 作为可重复注解直接标在插件类上

可以声明多个示例，但插件元信息存在 `@Plugin` 和多个直接注解两种入口，注册表需要
额外合并规则，调用方也无法只读取 `@Plugin` 得到完整描述。

### 方案三：独立定义 `Example`，仅作为 `@Plugin.examples` 的值类型

`Example` 保持独立和可复用的声明类型，但通过空 `@Target` 限制为注解成员值；插件的
全部目录元信息仍从一个 `@Plugin` 入口读取。

## 决策

采用方案三。

### 注解与元信息

- 在 `core.plugins.annotations` 中独立定义 `Example`，使用运行时保留策略和空
  `@Target({})`。它不能直接标注类，也不使用 `@Repeatable`、`@Inherited` 或额外的
  `Examples` 容器注解。
- `@Plugin` 增加 `Example[] examples() default {}`。插件作者只通过该字段声明使用
  示例；空数组保持现有宿主插件的源码和启动兼容性。
- 每个 `Example` 包含可选 `title`、一个或多个独立 `code` 源码块、默认值为
  `yaml` 的 `lang`，以及默认值为 `false` 的 `full`。当前插件示例统一声明为完整
  Flow YAML，并显式设置 `full = true`；默认值保留用于兼容历史元信息。
- 注册表按注解声明顺序将示例转换为不可变 `PluginExample`，并保存在
  `PluginMetadata.examples`。示例源码块不能为空，语言标识不能为空。
- `PluginMetadata` 保留不带 examples 的旧构造入口，并将其归一为空列表，避免已有
  Core 调用方因新增元信息而必须立即修改。

### `full` 与完整 Flow YAML

- `full = true` 表示源码块已经是最小完整 Flow mapping，必须自行包含 Flow `key`、
  `tasks` 以及插件所在 Task 的准确 canonical `type`；消费方直接解析和展示源码，
  不再补充 Flow 或 Task 字段。
- `full = false` 仅为兼容历史插件元信息保留；新插件不得使用只包含插件字段的片段作为
  示例。
- 完整 Flow 及其嵌套 Task 都不得声明 `id`、`parentId` 或 `taskId`。这些值仍只由
  Flow 定义物化链路生成，示例不能改变 ADR 0013 和 ADR 0026 的系统身份规则。
- `@Schema.example` 和 `@Schema.examples` 继续只描述单个字段；`@Plugin.examples`
  描述插件在 Flow 中的组合用法，两者不合并。

### 查询与验证

- `GET /api/plugins` 继续返回轻量目录，不携带源码块。
- `GET /api/plugins/{canonicalType}` 在原有 `metadata + schema` 之外返回顶层
  `examples`，每项保持 `title`、`code`、`lang` 和 `full`。
- Flow 内置的 AutomaticTask、Log、Parallel、Pause、Loop 和 LoopUntil 都至少声明
  一个最小完整 Flow 示例。测试直接通过真实 `FlowDefinitionDeserializer` 物化每个
  YAML 源码块，确保示例使用已注册类型并满足当前定义约束。
- 外部插件的示例仍是可选文档元信息。注册阶段验证其基本结构，但不执行示例，也不让
  示例承担插件可运行性的证明责任。

## 理由

`@Plugin` 继续作为插件目录元信息的唯一接口，注册表隐藏注解数组、不可变复制和 HTTP
转换细节。独立 `Example` 又避免把一个完整值结构嵌套在 `Plugin.java` 中。调用方只需
理解详情查询中的统一示例模型，不需要反射注解或识别具体插件类。

## 后果

- 新增内置插件时必须同时提供至少一个可物化的 Flow YAML 示例。
- 宿主插件可以渐进补充示例；未声明时详情接口返回空数组。
- 示例随插件类和 canonical type 一同发布，类改名或换包时 Flow 中的 `type` 也必须
  同步修改。
- 示例只提供定义文档，不增加运行时脚本、代码执行、页面组件协议或插件安装生命周期。
