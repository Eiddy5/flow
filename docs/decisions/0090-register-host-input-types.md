# ADR 0090：注册宿主业务 Input 类型

## 状态

已于 2026-09-14 撤回宿主 Input 扩展需求，由 [ADR 0101](0101-restore-built-in-input-types.md) 取代。下文仅保留历史决策。

Accepted（2026-09-09）。实现用户已确认的外部业务 Input 扩展需求，延续 ADR 0088、0089
的规则所有权和创建即合法约束；修订 ADR 0019、0026、0027 的固定 Input 类型范围。

类型发现和 Input 绑定机制已由 [ADR 0093](0093-discover-inputs-from-jackson-type-names.md)
取代；下文保留当时决策，当前新增类型使用 JsonTypeName 和编译期索引。

## 背景与备选方案

业务 Input 与基础运行值类型不是同一概念。例如业务员工编号可以使用 STRING 值，
同时拥有自己的配置和校验。扩充 DataType 枚举不能解决宿主独立扩展问题。

1. 每增加业务类型就修改核心枚举：无法独立扩展。
2. 新建独立 Input 注册表：重复已有插件允许列表和目录查询能力。
3. 将业务 Input 类加入已有 PluginRegistry：采用；注册时不创建空 Input。

## 决策

- Input 实现 Plugin，`type` 表达定义类型；业务类型使用精确 canonical class name。
  内置九种类型继续使用既有短代码并接受原有大小写、首尾空白兼容。
- Data 的共同值语义改由 `getValueType()` 表达；Input 具体类型提供该值类型，条件
  检查使用它。Output 的 JSON `type` 和运行值协议不变。
- 业务类继承 Input，标注 `@InputPlugin`，编译时生成 Micronaut introspection。
  DefaultPluginRegistry 发现这些类并加入同一目录；不实例化业务 Input，不要求无参
  构造，不把 Input 作为单例 Bean。Task 原有 `@Plugin` Bean 发现方式保留。
- 具体 Input 的 Creator 收齐定义字段后调用自身构造、转换和校验；注册表和反序列化
  Adapter 只选择允许的类，不解释业务字段、不在创建后补一次外部校验。
- YAML 与持久化 JSON 的业务类型绑定复用 PluginModule、PluginDeserializer 和
  JacksonMapper。DataJsonCodec 通过已有受控转换入口回读 Input；普通 JSON 树和
  序列化继续使用 PAAS JSON。PAAS 原生抽象 Input 绑定保留内置类型兼容，业务多态
  对象应通过受控 JacksonMapper 绑定，与 Task 插件一致。
- 插件目录增加同包 inputs，详情 Schema 不为 Input 创建默认空实例；嵌套 Input
  Schema 从同一注册表列出业务类型，使用 canonical 标识，避免固定子类型注解生成
  缺失业务类型或与真实绑定相冲突的类型常量。
- 草稿原始 source 可保留尚不可物化的业务 Input；正式发布仍严格拒绝非法定义，
  不会把非法 Input 作为领域对象保存。
- 仅发现宿主启动 classpath 上已编译的类型；不增加安装、热加载、卸载、远程校验、
  独立 ClassLoader 或任意 Class.forName。

## 理由

业务只维护自己的 Input 和编译注解，类型注册、目录、能力检查及绑定复用现有机制。
Input 是所属 Flow/Task 的定义值，没有独立生命周期或 Repository；发布后随原版本
读取，运行提交继续由同一个具体 Input 接受。

## 后果与验证

- Java 调用方使用 `getValueType()` 读取基础值类型，`getType()` 读取 Input 定义标识。
- 宿主需运行 Micronaut 注解处理，并让插件依赖存在于读取历史定义的应用 classpath。
  缺失类型、类型与能力不匹配、非法定义明确失败，不回退为通用 Input。
- 技术检查覆盖类发现而不实例化、目录、Schema、精确类型、未知字段、JSON/YAML、
  Flow/Task/Pause 持久化和内置兼容；用户路径由外部 Input UC 验证。
