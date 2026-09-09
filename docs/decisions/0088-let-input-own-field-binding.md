# ADR 0088：由 Input 拥有单字段绑定规则

## 状态

Accepted（2026-09-09）。用户确认先收拢 Input 的行为职责，本次不开放外部类型注册。
本 ADR 修订 ADR 0019 的公开值校验接口及 ADR 0052 的单字段规则归属。

定义物化的无参/Setter 方案及外部 validateDefinition 调用已由
[`ADR 0089`](0089-validate-input-during-materialization.md) 取代；运行值绑定的职责保持不变。

## 背景

Flow 启动、Pause 恢复分别提取字段并决定默认值，Input 再执行转换和校验；
持久化 Codec 还重复解释默认值及公共定义字段。单个输入的规则分散在多条调用链中，
具体 Input 无法独立说明自己如何接受一次提交。

## 备选方案

1. 保留外部预处理，只让 Input 校验最终值：调用方仍必须了解缺失、null 和转换顺序。
2. 新增统一 InputValidator 或 InputBinder：规则仍在 Input 之外，且增加一层分派。
3. 由每个 Input 绑定自己的字段，所属定义只管理字段集合：采用此方案。

## 决策

- `Input.bind(submittedInputs)` 是单字段运行值入口。Input 根据自身 key 读取值，
  仅字段缺失时使用 defaultValue，显式 null 不使用默认值。基类保证 required，
  再调用具体 Input 的 `convert` 与 `validateValue`，返回合法值或带字段 key 的异常。
- `convert`、`validateValue` 是受保护的具体类型行为；移除公开 `valid`、`normalized`
  和供子类分步调用的 `validateRequired`、`validateDefaultValue`，避免调用者漏掉步骤。
  基础数值安全转换复用 DataType 已有能力，整数范围与浮点有限值仍由具体 Input 保护。
- `validateDefinition()` 继续是完整定义检查入口。公共定义归 Input，专有配置归具体
  Input；默认值通过同一转换与值规则检查。JSON/YAML 原始默认值由 Input 的绑定方法
  接受，避免框架先把数字字符串或小数宽松转换为整数；原始 key 也由 Input 拒绝
  非字符串和空白值，不能因移除 Codec 预处理而放宽定义校验。
- Flow 的集合操作命名为 `bindInputs`，Pause 命名为 `bindResume`；它们只检查未声明
  字段、逐项调用 Input.bind 并收集不可变结果，不读取 defaultValue 或解释值类型。
  Flow 继续省略 null 结果；Pause 继续保留显式提交的 null。声明 key 唯一性仍归所属定义。
- `DataJsonCodec` 仅负责物化具体 Input、调用完整定义检查并补充持久化字段位置；
  不再补 displayName/required 或规范化默认值，这些由 Input 自身负责。
  具体 Input 显式标记无参 JSON 构造，避免框架选择 Builder 构造器后提前转换参数。
  PAAS JSON 当前使用 Jackson 3，而 YAML 类型解析器属于 Jackson 2；Codec 仅保留
  type 判别字段的大小写规范化，不在该处执行字段值规则。
- Service 在投递队列前绑定输入，消费者在加载精确 Flow 后再次绑定。保留现有同步拒绝
  非法提交、冻结队列输入和异步创建 Execution 的语义，不为移除一个集合方法而提前
  创建 Execution 或新增启动 DTO。
- 本轮不改变 DataType、Input 类型标识、数据库结构、Task 输入映射协议、Output 或插件注册。

## 理由

字段 key、缺失语义、默认值、转换及具体值约束共同决定一个 Input 能否接受提交，
因此由 Input 统一拥有。未声明字段与 key 重复描述一组声明之间的关系，仍由所属定义
负责。调用方只需消费绑定结果，无须依赖单字段规则的执行顺序。

## 后果与验证

- Java 调用方迁移到 Input.bind、Flow.bindInputs 与 Pause.bindResume；不保留旧方法别名。
- 输入定义与运行值的 YAML/JSON 协议保持原类型代码，历史 key/type 定义仍可回读。
- 普通技术测试覆盖九种基础类型、缺失与显式 null、默认值、required、整数范围、
  非有限浮点数、严格默认值物化及 JSONB 往返；UC-02/UC-04 作为用户入口回归。
- 外部业务 Input 插件与依赖业务查询的校验仍是后续设计事项。
