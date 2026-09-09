# 宿主业务 Input 接入

新增类型只维护一个业务类：继承 `Input<T>`，声明 `@JsonTypeName`，提供正常的
`@JsonCreator`。不需要修改 Flow 的枚举、注册表、Mapper 或 Schema 列表，也不需要
`@InputPlugin`、Micronaut `@Creator`、`@Introspected`、无参构造或 Setter。

## 一次性构建配置

在声明业务 Input 的每个 Java 模块中，将 Flow 的 `processor` 产物加入
`annotationProcessor`；测试源也声明业务 Input 时加入 `testAnnotationProcessor`。
本仓库对应配置为：

```groovy
annotationProcessor(project(':processor'))
testAnnotationProcessor(project(':processor'))
```

外部宿主使用发布后的同一处理器产物或 composite build 引用。处理器仅依赖 JDK，
不要求业务包进入 Micronaut 的扫描范围。编译后自动产生 `META-INF/flow/inputs`。
应用启动时会合并依赖 JAR 内的索引；类只被加载，不会被初始化或构造。

## 声明自身类型和规则

```java
@JsonTypeName("BUSINESS_CODE")
public class BusinessCodeInput extends Input<String> {
    // 业务字段
    // @JsonCreator 创建方法
    // 自身转换和校验
}
```

完整可运行类位于 `core/src/test/java/com/example/flow/inputs/BusinessCodeInput.java`。

1. JsonTypeName 必须是唯一、非空且没有首尾空白的协议名称；重复名称或别名冲突在装配时失败。
2. 用 `@JsonCreator` / `@JsonProperty` 接收定义字段。原始 key、defaultValue 使用 Object
   接收，以便具体 Input 拒绝框架的宽松类型转换；key 可用继承的 definitionKey 检查。
3. 私有构造先调用 super，再设置业务字段，最后调用 validateDefinition；非法定义不能返回实例。
4. 实现 getValueType、convert，按需要实现 validateSubtypeDefinition、validateValue、
   specificEquals 和 specificHashCode。getType 已由基类读取 JsonTypeName，不再覆盖。
5. 需要插件目录标题或描述时可选用 `@Schema(title = "...", description = "...")`，
   不影响发现或序列化。

```yaml
inputs:
  - key: employee
    type: BUSINESS_CODE
    prefix: EMP
    required: true
    defaultValue: ' emp-12 '
```

示例默认值在创建返回前变为 EMP-12，运行提交 employee: ' emp-34 ' 得到 EMP-34，
OTHER-34 被该 Input 拒绝。定义同样适用于 Task.inputs 和 Pause.onResume。

## JSON、YAML 与存储

- PAAS JSON 可直接使用 `JsonObject.Parse(json).asObject(Input.class)`；Java SPI 自动安装
  Input 类型模块，Micronaut HTTP Mapper 同样安装该模块。无需调用方手写类型选择。
- YAML 继续通过 YamlParser / FlowService；持久化仍通过原 Codec。两套 Jackson 版本
  使用相同的索引、名称与规则。
- 内置类型在自己的类上声明 STRING、INTEGER 等原有名称。业务名称精确匹配，内置
  历史代码保留大小写与首尾空白兼容。原业务 canonical class name 是回读别名，写出统一
  使用 JsonTypeName；更改类型名称仍属于协议变更。
- 未知类型、错误能力、未知字段、非法配置和默认值都会拒绝，校验属于具体 Input。
- 插件目录与 Schema 共用同一份注册元数据，目录及 Schema 中的 type 是 JsonTypeName。
- 发布普通 JAR 时保留生成索引。Shadow 合并 JAR 要追加 META-INF/flow/inputs 并合并
  META-INF/services；本仓库 Server 已配置。不要让打包过滤器丢弃索引或 Jackson 模块服务文件。

## 验证

```bash
./gradlew :core:test --tests '*InputPluginTest'
```

UC-12 的真实发布、启动、恢复和重启场景使用 Uc12BusinessInputsTest，数据库配置见
[postgresql-repositories.md](postgresql-repositories.md)。本地依赖当前需要 JDK 25。
技术测试通过不替代 UC 场景结论。设计依据见 [ADR 0093](../decisions/0093-discover-inputs-from-jackson-type-names.md)。
