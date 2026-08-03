# JSON 使用规范

## 适用范围

本规范适用于项目中的生产代码、测试代码、持久化映射、HTTP 边界和手写代码生成
逻辑。凡是需要创建、解析、序列化、反序列化或转换 JSON 的代码，都必须遵守本
规范。

PAAS JSON 是本项目统一的 JSON 能力入口。业务代码不得绕过公共封装直接使用
Jackson `ObjectMapper`。

## 1. 统一使用 PAAS JSON

按数据形态选择 `org.paas.json` 提供的公共类型：

| 类型 | 用途 |
| --- | --- |
| `JsonObject` | 创建、解析和操作 JSON 对象 |
| `JsonObjects` | 创建、解析和操作 JSON 数组 |
| `JsonFactory` | 公共序列化、反序列化和类型转换能力 |

新增或修改 JSON 代码时，应先使用这些类型已有的公共方法，不得重复封装
`JsonUtil`、`JsonHelper` 或项目私有的 Mapper。只有 PAAS JSON 确实无法表达已确认
的公共需求时，才可以先形成架构决策，再扩展统一能力。

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

- Controller 可以使用 Micronaut 的请求和响应绑定；需要手动处理 JSON 时，仍
  必须使用 PAAS JSON。
- Core 优先接收领域对象和值类型。JSON 只是 HTTP、配置或持久化格式时，应在
  Controller、Serializer、Entry 或其他边界完成转换，不能让 JSON 技术类型扩散
  为领域模型。
- ADR 0019 确认的 Input 定义物化是窄化边界：Flow 可以把 YamlParser 产生的单个
  Input Map 临时转为 `JsonObject`，并通过 `asObject(Input.class)` 按 `type`
  多态实例化；Repository Codec 使用 `asObjects(Input.class)` 恢复。该例外不允许
  `JsonObject` 成为领域字段、公共方法参数，也不允许再建立项目私有 Mapper。
- JOOQ Entry 中的 JSON/JSONB 字段转换必须使用 `JsonObject`、
  `JsonObjects` 或 `JsonFactory`。转换和扩展方法仍放在对应的 `XxxEntry` 或其
  专用 Codec 中。
- 测试代码解析请求、响应或构造 JSON 数据时同样使用 PAAS JSON，不能因为测试
  代码生命周期短而直接创建 `ObjectMapper`。
- `org.flow.gen` 下的生成代码由生成器维护，不手工修改；生成类已经提供 PAAS
  JSON 字段和转换能力时，调用方必须直接复用。

## 4. 禁止直接使用 ObjectMapper 处理 JSON

手写项目代码禁止直接导入、创建、注入、缓存或封装
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

1. `core/serializers/YamlParser` 按 ADR 0013 使用
   `ObjectMapper(new YAMLFactory())` 解析 YAML。它是非 JSON 格式边界，例外不能
   扩散到其他类，也不能用于 JSON。
2. Micronaut、PAAS JSON 或其他第三方库内部使用 Jackson，项目代码不直接绕过
   PAAS API。
3. 无 Micronaut 容器的基础设施测试为 PAAS JSON 初始化
   `JsonFactory.instance`。该初始化只负责测试引导，测试中的 JSON 创建、解析和
   断言仍必须通过 PAAS JSON 完成。
4. 生成器产出的代码由生成模板决定，不直接手工修改。项目维护的生成模板和生成
   逻辑仍应优先生成 PAAS JSON 用法。

新增例外必须记录 ADR，说明为什么 PAAS JSON 无法满足需求、例外边界和后续收敛
方式。不能仅因熟悉 Jackson API 或实现方便而新增例外。

## 6. 存量代码迁移

- 存量代码中直接使用 `ObjectMapper` 处理 JSON 的写法属于待迁移项。
- 新代码不得复制存量写法。
- 修改相关类或链路时，应在不改变对外 JSON 契约的前提下迁移到 PAAS JSON，并
  补充序列化、反序列化和异常输入测试。
- `FlowDemoControllerTest` 当前直接创建 `ObjectMapper` 解析 HTTP JSON 响应，
  后续修改该测试时应迁移到 PAAS JSON。

## 7. 开发与审查清单

新增或修改 JSON 代码时逐项检查：

1. 是否统一使用 `org.paas.json` 下的公共类型和方法。
2. 是否根据对象、数组和类型转换场景选择了正确的 PAAS JSON API。
3. 是否避免新增 `ObjectMapper`、`JsonNode` 或私有 JSON 工具类。
4. JSON 技术类型是否被限制在 HTTP、Serializer、Entry、Codec 等必要边界。
5. JOOQ JSON/JSONB 字段转换是否放在对应 Entry 或专用 Codec 中。
6. 转换失败或公共方法返回 `null` 时是否按业务契约处理。
7. 正常输入、缺失字段、错误类型、非法 JSON 和空值场景是否有测试。
8. 如需例外，是否已有明确的 ADR，并且例外没有扩散。
