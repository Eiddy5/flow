# ADR 0093：从 Jackson 类型注解自动发现 Input

## 状态

已于 2026-09-14 撤回宿主 Input 扩展需求，由 [ADR 0101](0101-restore-built-in-input-types.md) 取代。下文仅保留历史决策。

Accepted（2026-09-09）。依据用户确认的“新增 Input 只继承基类并声明 Jackson 注解”，
取代 ADR 0090 中的 InputPlugin 注解、固定内置类型映射和 Input 手写反序列化路径。

## 背景

Input 的类型身份同时出现在固定 JsonSubTypes、InputTypeIdResolver 和插件注册表中，
业务扩展还需要额外的 InputPlugin/Creator 注解。Jackson 原生名称多态可以负责类型选择，
但不会自动枚举宿主 classpath 中的子类。类型发现必须在框架内完成，且不能创建无效空 Input。

## 备选方案

1. 固定 JsonSubTypes 或逐类 registerSubtypes：新增类还需修改集中列表，不满足需求。
2. Id.CLASS：无需索引，但持久化身份与 Java 包名绑定，原短类型代码无法直接读取。
3. 运行期扫描整个 classpath：需要扫描库或自建目录/JAR 遍历，增加启动工作。
4. 编译期索引 JsonTypeName，启动后使用 Jackson 原生 Id.NAME：采用。

## 决策

- 新增独立 `processor` 模块，仅依赖 JDK 注解处理 API，不依赖 Core 或 Micronaut。
  InputTypeProcessor 找到标注 JsonTypeName 的具体 Input 子类，生成
  `META-INF/flow/inputs`。宿主为声明业务 Input 的模块配置一次 annotationProcessor。
  类型发现不需要 InputPlugin、Introspected、Micronaut Creator 或无参构造。
- Input 基类声明 Id.NAME / EXISTING_PROPERTY；每个具体 Input 用 JsonTypeName 声明
  唯一非空名称，getType 读取该声明。输入转换和定义校验继续属于具体类的 Creator/构造路径。
- InputTypes 只读取编译索引，使用应用 ClassLoader 加载类而不初始化类；拒绝非法类型、
  重名和别名冲突。内置 Input 和宿主业务 Input 进入同一份类型元数据。
- PluginModule 为 Jackson 2 注册原生 NamedType；PluginDeserializer 继续仅服务 Task。
  InputJacksonModule 为 PAAS/Micronaut 的 Jackson 3 注册同一份名称和别名，经 Java SPI
  和 Micronaut Bean 装配。普通 PAAS `asObject(Input.class)` 同样支持业务 Input。
- 业务名称精确匹配；原完整 canonical class name 保留为只读别名。内置代码继续写出
  STRING 等名称；历史大小写/首尾空白容错只在 Jackson 的未知类型回调中集中兼容，
  不启用影响业务名称的全局忽略大小写配置。
- PluginMetadata.typeName 表达定义协议名称，canonicalType 继续表示 Java 类名。
  目录、顶层和嵌套 Input Schema 使用相同注册元数据，不再遍历 DataType 构造类型表。
- 打包必须保留类型索引；合并 JAR 时追加 `META-INF/flow/inputs` 并合并服务描述文件。

## 理由

借鉴 Kestra 插件编译期索引和集中装配的方式，但 Input 类型分派由 Jackson 自身完成。
基础设施配置一次之后，每个新 Input 只声明自身名称、字段和行为；不再维护第二处类型列表。
该实现同时支持普通目录、独立业务 JAR 和合并 JAR，不把运行中的 Input 实例当成类型元数据。

## 后果与验证

- 新增一个可独立交付的处理器构建产物；宿主依赖要同时配置 Core 和处理器。
- 读取历史业务定义的应用仍需携带对应类和索引。改 JsonTypeName 是协议变更，不能随意改名。
- JSON/YAML/PAAS、持久化、Schema、目录必须共同覆盖自定义短名与旧类名别名。
- 必须验证独立宿主编译、增删类后的索引更新、普通 JAR 索引保留，以及注册时不调用构造器。
- UC-12 用户场景保持不变，由 Test Agent 验证真实 PostgreSQL 发布、启动、恢复和重启链路。
