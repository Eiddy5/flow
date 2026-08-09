# ADR 0043：使用 SerializableObject 作为 JSON 模型基类

## 状态

Accepted

## 背景

Flow 的模型曾通过大量 Micronaut Serialization 注解接入框架。该做法把
JSON 框架元数据散落到每个模型上，也与现有 PAAS JSON 的模型约定不一致。项目中
已有的标准模型形态是 Lombok `@Getter`、`@Setter` 配合
`org.paas.json.SerializableObject`，由 PAAS JSON 注册的 Jackson serializer 统一
处理对象字段和扩展字段。

仅把模型改为继承 `SerializableObject`、但继续使用 Micronaut Serde HTTP codec
并不能工作：`SerializableObject` 是第三方类，Micronaut Serde 不会因继承关系自动
生成可序列化内省信息。

## 备选方案

### 方案一：保留每个模型的 Micronaut Serialization 注解

可以继续使用 Micronaut Serialization，但模型必须携带框架注解，且 PAAS JSON 与
HTTP codec 形成两套模型识别规则。

### 方案二：集中注册第三方模型

可以绕过逐类注解，但仍然把第三方模型注册到 Micronaut Serde，增加集中式类型
清单和维护负担。

### 方案三：使用 Jackson Databind 与 SerializableObject

让 Micronaut HTTP 使用 Jackson Databind，复用 `paas-json` 提供的
`SerializableObjectSerializer`，模型只保留自身的 Java/Lombok 结构。

## 决策

采用方案三。

- `core` 和 `server` 使用 `micronaut-jackson-databind`，移除 Micronaut Serde
  Jackson 实现和处理器依赖。
- HTTP 请求、响应模型统一采用 `@Getter`、`@Setter` 与
  `extends SerializableObject`。
- Flow Input 多态模型同样继承 `SerializableObject`。由于第三方基类没有 Lombok
  `@SuperBuilder`，具体 Input 在构造器上使用 `@Builder`，保留公共字段的
  `builder()` API；JSON 仍通过无参构造和 Setter 绑定。
- Input 通过 `@JsonAnySetter` 拒绝未知字段，并在绑定后继续执行既有的
  `validateDefinition()`，保持严格多态定义契约。
- 项目源码不再使用逐类或集中式 Micronaut Serialization 模型注册。

## 理由

- 模型结构与项目已有的 PAAS JSON 约定一致。
- JSON codec 只在 Micronaut 组合根切换一次，不需要为每个 DTO 维护框架注解。
- 保留 Input 的多态字段、无参绑定、严格校验和 Java 侧 builder 能力。

## 后果

- HTTP 与 `JsonFactory` 共用 Jackson Databind 语义，`SerializableObjectSerializer`
  负责统一输出模型字段。
- Input 的 builder 实现从继承式 `@SuperBuilder` 改为构造器级 `@Builder`；调用方
  的 `builder()` 入口保持不变，但不再依赖第三方基类提供 Lombok builder。
- 新增 JSON 模型应继续继承 `SerializableObject`，不应重新引入 Micronaut Serde
  模型注册注解。JSON 创建、转换仍遵循 [`JSON 使用规范`](../standards/json.md)。
