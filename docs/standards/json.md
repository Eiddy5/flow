# JSON 使用规范

## 适用范围

本规范适用于项目中的生产代码、测试代码、持久化映射、HTTP 边界和手写代码生成
逻辑。凡是需要创建、解析、序列化、反序列化或转换 JSON 的代码，都必须遵守本
规范。

PAAS JSON 是一般业务和基础设施 JSON 的首选能力入口。只有 PAAS JSON 不支持目标
格式或必要转换能力时，才允许在对应技术边界提供自定义序列化和反序列化实现。当前
Flow YAML 和 Task 插件的严格多态绑定是
[ADR 0026](../decisions/0026-use-task-class-as-in-project-plugin.md) 定义的受控例外：
PAAS JSON 不支持 YAML，因此该链路只能通过 `core/serializers/YamlParser` 和
`core/serializers/JacksonMapper` 使用受控 Jackson 配置。其他业务代码不得绕过公共
封装直接使用 Jackson `ObjectMapper`。

## 1. 优先使用 PAAS JSON

按数据形态选择 `org.paas.json` 提供的公共类型：

| 类型 | 用途 |
| --- | --- |
| `JsonObject` | 创建、解析和操作 JSON 对象 |
| `JsonObjects` | 创建、解析和操作 JSON 数组 |
| `JsonFactory` | 公共序列化、反序列化和类型转换能力 |

新增或修改一般 JSON 代码时，应先使用这些类型已有的公共方法，不得重复封装
`JsonUtil`、`JsonHelper` 或项目私有 Mapper。只有确认 PAAS JSON 不支持目标格式或
必要转换能力时，才可以提供自定义实现；该实现必须集中在对应 Serializer 等技术
边界，只覆盖 PAAS JSON 不支持的部分。Task 插件和 YAML 链路必须复用现有
`YamlParser`、`JacksonMapper`，也不能在插件、Repository 或 Controller 中创建第二套
Mapper。只有现有两条受控入口都无法表达已确认的公共需求时，才可以先形成架构决策，
再扩展统一能力。

## 2. 常用方法

### JSON 对象

```java
import org.paas.json.JsonObject;

JsonObject payload = JsonObject.Create()
    .put("executionId", executionId)
    .put("status", status);

String content = payload.toJson();
JsonObject restored = JsonObject.Parse(content);
String restoredExecutionId = restored.getString("executionId");
```

已有 `Map<String, Object>` 与 JSON 对象之间转换时使用：

```java
JsonObject json = JsonObject.FromMap(values);
Map<String, Object> restoredValues = json.asMap();
```

Java 对象与 JSON 之间转换时，优先使用公共类型转换方法：

```java
String content = JsonObject.ToJsonContent(value);
FlowPayload payload = JsonObject.Parse(content).asObject(FlowPayload.class);
```

### JSON 数组

```java
import org.paas.json.JsonObjects;

JsonObjects json = JsonObjects.FromList(values);
String content = json.toJson();

JsonObjects restored = JsonObjects.Parse(content);
List<FlowPayload> payloads = restored.asObjects(FlowPayload.class);
```

常用选择规则：

- 已有 JSON 字符串或字节数组时，使用 `JsonObject.Parse(...)` 或
  `JsonObjects.Parse(...)`。
- 已有 Map 时，使用 `JsonObject.FromMap(...)`。
- 已有 List、Set 或 Collection 时，使用 `JsonObjects.FromList(...)`、
  `FromSet(...)` 或 `FromCollection(...)`。
- 需要转换为确定的 Java 类型时，使用 `asObject(...)`、
  `asObjects(...)` 或 `JsonFactory` 已提供的类型转换方法。
- 只读取少量字段时，优先使用 `getString(...)`、`getInt(...)`、
  `getBoolean(...)` 等明确的类型方法，避免先转 Map 再进行未经检查的强制类型
  转换。

调用可能返回 `null` 的公共转换方法后，调用方必须结合所在边界的业务契约进行
校验，不能让转换失败以空值形式继续进入领域逻辑。

## 3. 分层使用规则

- Controller 可以使用 Micronaut 的请求和响应绑定；当前 HTTP JSON codec 使用
  Jackson Databind，边界模型统一继承 PAAS `SerializableObject` 并使用 Lombok
  `@Getter`、`@Setter`。需要手动处理 JSON 时，仍必须使用 PAAS JSON。
- Core 优先接收领域对象和值类型。JSON 只是 HTTP、配置或持久化格式时，应在
  Controller、Serializer、Entry 或其他边界完成转换，不能让 JSON 技术类型扩散
  为领域模型。
- 正式 Flow YAML 由 `YamlParser.parse(source, Flow.class)` 直接绑定；草稿先由
  `YamlParser.parse(source)` 形成通用 Map，去除不属于定义的 Flow 系统字段后再由
  `YamlParser.bind(...)` 绑定。Input/Output 和 Task 由受控 `JacksonMapper` 配置的
  Mapper 绑定。`Task.class` 已通过
  `PluginModule` 注册 `PluginDeserializer`，它从
  `type` 读取精确插件标识、通过注册中心解析具体类，再让 Jackson 递归绑定所有
  Task 字段。`PublishFlowHandler` 在补充 Session、draft、状态和 source 后调用
  `ModelValidator`；版本只由 Repository 保存时分配，校验失败的部署对象不能进入
  聚合。持久化 Input 继续由
  Repository Codec 使用 PAAS JSON 恢复。
- Task 插件 properties 的拆分与合并统一由 Repository Adapter 的
  `TaskPropertiesCodec` 完成。Codec 通过 `JacksonMapper` 内部的持久化转换入口复用
  同一个受控 JSON Mapper；`FlowTaskEntry` 只静态调用 Codec，不接收或持有 Mapper。
  恢复具体 Task 仍经过注册表驱动的 `PluginDeserializer`。YAML Mapper 注册 source
  定义配置，自动拒绝用户声明的 Task 系统字段并生成首次身份；JSON Mapper 注册
  持久化配置，按原值恢复已保存身份。调用方不得传递业务 reader attribute 或创建
  第二套 Mapper。
- JOOQ Entry 中的 JSON/JSONB 字段转换必须使用 `JsonObject`、
  `JsonObjects` 或 `JsonFactory`。需要序列化或专用字段转换时，Entry 只调用专用
  Codec；Codec 的局部放置、跨数据库对象复用和静态调用规则由
  [`jooq.md`](jooq.md) 统一定义。
- 测试代码解析请求、响应或构造 JSON 数据时同样使用 PAAS JSON，不能因为测试
  代码生命周期短而直接创建 `ObjectMapper`。
- `org.flow.gen` 下的生成代码由生成器维护，不手工修改；生成类已经提供 PAAS
  JSON 字段和转换能力时，调用方必须直接复用。

## 4. 禁止在受控边界外直接使用 ObjectMapper

除下一节列出的受控边界外，手写项目代码禁止直接导入、创建、注入、缓存或封装
`com.fasterxml.jackson.databind.ObjectMapper` 来处理 JSON。

禁止写法包括但不限于：

```java
private static final ObjectMapper MAPPER = new ObjectMapper();

String content = MAPPER.writeValueAsString(value);
FlowPayload payload = MAPPER.readValue(content, FlowPayload.class);
FlowPayload converted = MAPPER.convertValue(value, FlowPayload.class);
```

以下方式同样不允许：

- 在 Bean 构造方法或字段中注入 `ObjectMapper` 处理 JSON。
- 为每个模块配置一套 ObjectMapper Module、命名策略或序列化规则。
- 用 Jackson `JsonNode` 作为项目公共接口、领域对象或持久化契约。
- 新建工具类包装 `ObjectMapper`，以间接方式绕过 PAAS JSON。

这些写法会造成序列化行为、日期格式、空值处理和类型转换规则分散，无法统一通过
PAAS 公共能力演进。

## 5. 允许的边界例外

以下情况不属于“直接使用 ObjectMapper 处理 JSON”：

1. `core/serializers/JacksonMapper` 按 ADR 0026 集中创建严格 JSON/YAML Mapper、
   注册 `PluginModule` 并隐藏具体 ObjectMapper。`YamlParser` 和
   `PluginSchemaGenerator` 可在同一包内使用 Jackson tree model；`YamlParser`
   不接收 reader attributes，也不导入、创建或解释业务绑定上下文；
   `core/plugins/PluginDeserializer` 可在一次 Jackson 回调内读取插件节点并通过
   当前 `DeserializationContext` 递归绑定具体类。该例外只服务 Flow 定义和 Task
   插件的多态绑定，不得成为公共领域契约。
2. `TaskPropertiesCodec` 是持久化 Task properties 调用 `JacksonMapper` 的唯一边界；
   `FlowTaskEntry` 只调用 Codec，不能直接接触 `JacksonMapper`、ObjectMapper 或
   JsonNode。
3. Micronaut、PAAS JSON 或其他第三方库内部使用 Jackson，项目代码不直接绕过
   PAAS API。
4. 无 Micronaut 容器的基础设施测试为 PAAS JSON 初始化
   `JsonFactory.instance`。该初始化只负责测试引导，测试中的 JSON 创建、解析和
   断言仍必须通过 PAAS JSON 完成。
5. 生成器产出的代码由生成模板决定，不直接手工修改。项目维护的生成模板和生成
   逻辑仍应优先生成 PAAS JSON 用法。

新增例外必须记录 ADR，说明为什么 PAAS JSON 无法满足需求、例外边界和后续收敛
方式。不能仅因熟悉 Jackson API 或实现方便而新增例外。

## 6. 存量代码迁移

- 存量代码中直接使用 `ObjectMapper` 处理 JSON 的写法属于待迁移项。
- 新代码不得复制存量写法。
- 修改相关类或链路时，应在不改变对外 JSON 契约的前提下迁移到 PAAS JSON，并
  补充序列化、反序列化和异常输入测试。

## 7. 开发与审查清单

新增或修改 JSON 代码时逐项检查：

1. 是否统一使用 `org.paas.json` 下的公共类型和方法。
2. 是否根据一般 JSON 或 Task 插件多态场景选择了 PAAS JSON 或现有
   `JacksonMapper`。
3. 是否避免在受控包之外新增 `ObjectMapper`、`JsonNode` 或私有 JSON 工具类。
4. JSON 技术类型是否被限制在 HTTP、Serializer、Entry、Codec 等必要边界。
5. JOOQ 的序列化和专用字段转换是否交给专用 Codec，并按 `jooq.md` 的复用范围规则
   放置。
6. 转换失败或公共方法返回 `null` 时是否按业务契约处理。
7. 正常输入、缺失字段、错误类型、非法 JSON 和空值场景是否有测试。
8. 如需例外，是否已有明确的 ADR，并且例外没有扩散。
