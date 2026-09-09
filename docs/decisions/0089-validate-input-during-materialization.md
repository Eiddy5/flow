# ADR 0089：Input 在物化返回前完成定义校验

## 状态

Accepted（2026-09-09）。用户确认 Input 定义应在反序列化完成时已经合法，继续收拢
定义校验职责。本 ADR 取代 ADR 0019 和 ADR 0088 中无参/Setter 绑定后由外部校验的条款。

## 背景

ADR 0088 已让 Input 自行处理运行提交值，但 JSON/YAML 仍先返回可变半成品，再由
发布 Handler、ModelValidator、Pause 和持久化 Codec 分别调用 validateDefinition。
直接反序列化或新增调用链可以漏掉该步骤；字段赋值顺序也不能作为完整定义已就绪的标志。

## 备选方案

1. 保留半成品，要求每个调用方记得校验：不能满足返回对象即合法的要求。
2. 在 Jackson 2/3 分别增加反序列化后处理 Module：需要覆盖所有 Mapper 装配路径，
   并且 Java Builder 仍有独立的校验路径。
3. 复用具体 Input 的创建路径完成校验，由 Jackson 调用其 Creator：采用此方案。

## 决策

- 每种具体 Input 提供 `@JsonCreator from(...)`。框架先收齐属性，具体类型再处理原始
  key 和 defaultValue，并调用私有构造方法。使用已有 Jackson 注解，同时适配现有
  YAML/Jackson 2 与 PAAS JSON/Jackson 3，不增加自定义 Mapper 或反序列化器。
- Creator 的 key、defaultValue 接收原始 Object，以保留非文本 key、数字字符串、
  小数转整数和数值越界的拒绝行为；转换仍由具体 Input 复用 DataType 完成。
- 可选 required 使用 Boolean 接收，缺失或 null 采用 false；运行提交中的显式 null
  与缺失仍按 ADR 0088 区分，不因此改变必填运行值规则。
- 私有构造先设置全部公共和类型专有字段，再执行 Input 内部的完整定义校验。
  IntegerInput 的 min/max 与默认值等跨字段约束因此不依赖 JSON 属性顺序。
- 现有类型化 Builder 保留，并调用同一私有构造；非法 Builder.build 同样立即失败。
  移除 Input 的无参创建和 Setter，避免已经校验的定义在外部被修改失效。
- validateDefinition 改为 protected，仅用于具体 Input 创建路径。发布 Handler、
  ModelValidator、Pause 和 DataJsonCodec 不再调用它。Flow/Task/Pause 继续负责
  声明集合的空元素、重复 key 等关系约束。
- 运行数据晚于定义出现，继续通过 Input.bind 接受；本轮不把运行值校验混入定义
  反序列化，不调整输入类型注册、队列或执行状态协议。

## 理由

完整属性在 Creator 被调用时已经就绪，构造只返回满足不变量的对象。具体类型同时
拥有字段、转换、定义规则与创建入口；外部只消费结果，不需要记住额外的完成步骤。
沿用框架现有 Creator 能力，比为两套 Jackson 配置额外的后处理协议更少且更直接。

## 后果与验证

- 这是 Input 定义层次对无参/Setter 自动绑定约定的明确例外；一般 PAAS JSON 绑定
  规范不变。直接调用无参构造、Setter 或外部 validateDefinition 的 Java 调用方需迁移。
- Input JSON/YAML 的字段和类型代码不变；未知字段仍严格拒绝。
- 直接绑定 Input.class、具体 Input 类、Flow.inputs、Task.inputs、Pause.onResume 和
  JSONB 回读都在返回前完成定义校验。测试同时提供合法对照和非法配置。
- 技术验证覆盖顺序变化、默认值越界、min/max 关系、缺失 key、严格值类型，以及
  Builder 无需外部校验即可得到合法定义。UC-01、UC-04 用于发布和 Pause 用户路径回归。
