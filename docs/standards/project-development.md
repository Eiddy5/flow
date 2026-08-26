# 项目开发规范（开发必读）

## 适用范围

本规范适用于本仓库中的所有生产代码、测试代码和代码生成逻辑。开发者和
AI Agent 在开始编码或审查代码前必须阅读本文件。

本文件集中维护项目通用开发规则，包括技术 ID、Java 类型、timestamp 毫秒值、
复用和小范围重构。领域对象、聚合和状态机的设计规则由
[`domain-object-modeling.md`](domain-object-modeling.md) 维护；Flow Core 当前选择的
具体对象、包和运行边界通过
[`../decisions/README.md`](../decisions/README.md) 查找对应 ADR。

领域、模块或场景规范可以增加更严格的要求，但不得绕过本规范。改变公共接口、
模块边界、领域模型、数据模型或本规范核心规则时，必须新增或修订 ADR。

## 1. 技术 ID

### 统一生成入口

- 系统内部新建实体、聚合、执行实例或其他持久化对象时，技术 ID 统一使用
  `org.paas.common.util.StringUtil.newId()` 生成。
- 技术 ID 的 Java 类型统一为 `String`。
- ID 只在对象首次创建时生成一次。对象重建、持久化回读、复制快照或状态更新时
  必须沿用原 ID。
- Service、Handler、Domain 和 Repository 之间传递同一个 ID，不得由不同层重复
  生成。

```java
import org.paas.common.util.StringUtil;

String executionId = StringUtil.newId();
```

领域对象在自身受控创建入口中完成首次身份生成，具体创建与重建规则见
[`domain-object-modeling.md`](domain-object-modeling.md)。

### 禁止写法

项目代码不得自行选择或实现其他技术 ID 生成方式，包括但不限于：

```java
UUID.randomUUID();
System.currentTimeMillis();
new Random().nextLong();
```

不得对 `StringUtil.newId()` 的结果截断、拼接、修改大小写或删除字符。ID 的格式由
公共库负责，业务代码不得依赖或重复实现该格式。

### 技术 ID 与业务标识

- 默认情况下，`id` 是系统内部技术主键；`key`、编码、名称等是业务标识。
- `id()` 统一返回对象自身稳定的字符串实体 ID，不提供 `recordId()`、`identifier()`
  或 `ExecutionId` 等平行实体身份表达。`FlowId` 是 ADR 0070 允许的 Flow Repository
  业务查询选择器，不是实体身份。被其他对象引用时使用 `executionId`、
  `taskId`、`taskRunId` 等语义化字符串字段。
- `key`、编码以及 `companyId + key + version` 等复合字段是业务标识或业务选择器，
  必须按真实字段表达，不能替代对象自身的 `id`。Flow Repository 可按 ADR 0070 将
  这三个字段封装为 `FlowId`；该封装不能扩散为实体 ID 或跨对象引用。
- 业务标识由业务规则或外部输入决定，不能代替技术 ID，也不能使用技术 ID 生成器
  代替业务规则。
- 查询、更新、删除和恢复已有对象时必须校验并使用已有 ID，不得生成新 ID。
- 数据库自增 ID、第三方 ID 或其他策略只有在已接受 ADR 明确要求时才允许使用。

### 测试与审查

- 测试应验证新对象 ID 非空，以及保存、读取、重建和状态变化后 ID 保持不变。
- 除非公共库契约明确规定，不应断言 ID 的长度、分隔符或字符格式。
- 新增 UUID、时间戳 ID、自增 ID 或自定义随机 ID 应判定为违反规范。
- 修改使用旧 ID 策略的存量链路时，应在保证接口和数据兼容性的前提下完成迁移。

## 2. Java 类型

项目不全面禁止 Java `record`。类型选择由职责、不变量和框架约束决定。

### 必须使用 `class` 的类型

- 聚合根、实体、值对象及其他拥有领域行为或生命周期的 Domain 类型。
- JOOQ Entry、数据库映射对象及需要继承生成父类的持久化类型。
- 需要被 Micronaut、AOP 或其他框架通过继承、代理或受控构造方式管理的类型。
- 需要隐藏构造方法、提供 `create(...)`/`rehydrate(...)` 或保护复杂不变量的类型。
- 具有可变生命周期、延迟初始化或框架要求无参构造/Setter 绑定的类型。

普通 `class` 不使用 `final` 修饰。类和字段的统一 `final` 规则见下文。

### 可以选择 `record` 的类型

同时满足以下条件的简单边界协议可以使用 `record`：

- 只表达一组不可变数据，不拥有独立领域身份、生命周期或状态迁移。
- 构造校验简单，能够在紧凑构造方法中一次完成。
- 不需要继承父类，也不要求框架通过子类代理。
- 序列化、反序列化、Micronaut 绑定或其他使用框架已经通过测试确认支持。
- 使用 `record` 不会把 HTTP、数据库或第三方协议类型扩散进 Core Domain。

简单 Command、查询条件、边界请求/响应、不可变内部传输信封和测试参数可以选择
`record`，也可以继续使用普通 `class`；不要求为了简短而迁移已有类型。

- Java `record` 的实例统一通过类型提供的静态 `from(...)` 工厂创建；业务代码、
  测试代码和其他类型不得直接调用 `new RecordType(...)`。record 自身的
  `from(...)` 实现可以调用其构造方法；语义更明确的 `success(...)`、
  `failed(...)` 等便捷工厂也必须委托给 `from(...)`。

```java
public record ExecutionSummary(
    String id,
    String state
) {
    public ExecutionSummary {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
```

### 不可变性与集合

- 选择 `record` 不得绕过领域对象创建规则或模块边界。
- `class` 中需要保持不变的字段通过构造方法一次初始化，不提供 Setter，并只允许
  受控领域行为访问或替换其状态；不能依赖 `final` 修饰符表达不变量。
- ADR 0019 的 Input 定义层次是窄化例外：PAAS JSON 需要先通过无参构造和字段
  绑定形成具体子类，再由 Flow 或 Repository 边界执行完整定义校验。该层次允许仅供
  Micronaut Serialization 默认方法内省使用的公共 Setter；Setter 是技术绑定入口，
  不是业务变更方法。校验通过并进入聚合后仍按只读定义使用，Service、Handler 和
  其他领域对象不得调用 Setter；此例外不得扩展到其他领域对象。
- `record` 组件包含集合、Map、数组或其他可变对象时，紧凑构造方法必须防御性
  复制；访问方法不能泄漏可变引用。
- 使用 `class` 表达值语义时，应按需要实现 `equals()`、`hashCode()` 和
  `toString()`。
- 代码审查不能仅因类型使用 `record` 判定违规，应检查它是否满足职责条件。

详细决策见
[`ADR 0025`](../decisions/0025-allow-records-for-simple-boundary-contracts.md)。

### `final` 修饰符

- 项目自有的手写 Java 类和字段声明禁止显式使用 `final`。该规则同时适用于顶层类、
  嵌套类、实例字段和静态字段。
- 局部变量和方法参数不属于类或字段，本规则不约束它们。
- `record` 及其组件由 Java 语言隐式提供的不可变语义不属于显式使用 `final`，仍按
  上述职责条件选择。
- JOOQ 等工具生成且禁止手工修改的源码遵循生成器输出；不得为了本规则直接修改生成
  结果。项目自有的生成器配置和生成逻辑仍不得在类或字段声明中显式使用 `final`。
- 审查新代码或修改既有类时，应移除相关类和字段声明上的 `final`。字段不变量继续
  通过私有可见性、受控构造、无 Setter、领域行为和可变值的防御性复制保证。

## 3. timestamp 毫秒值

项目自有 Java 类型中的时间点统一使用 **Unix timestamp 毫秒值**。Java 类型为
`long` 或 `Long`，单位固定为毫秒，时间基准为 UTC。

- Domain、Command、Query、Service、Handler、DTO、View、事件和测试辅助类型中的
  必填时间点统一使用原始类型 `long`。
- 只有业务上确实存在“尚未发生”或“未知”语义的可空时间才使用 `Long`；`null`
  只表示时间点不存在。
- 禁止使用 `0`、`-1` 或其他魔法数字表示时间不存在。
- 时间点字段使用 `createdAt`、`updatedAt`、`deletedAt`、`startedAt`、
  `completedAt` 等业务名称，不重复添加 `Millis` 后缀。
- 时长、超时和间隔同样使用 `long` 毫秒，但名称必须包含单位，例如
  `timeoutMillis`、`durationMillis`。
- 禁止在项目自有业务类型的字段和公开方法签名中使用 `Instant`、
  `OffsetDateTime`、`LocalDateTime`、`ZonedDateTime`、`Date` 或 `Timestamp`。

### timestamp 获取规则

- 一般业务操作的当前 timestamp 由服务器调用边界通过统一的 `TimeUtil.now()` 读取一次，
  并以 `long` 显式传入领域行为，保证同一次业务操作时间一致且测试可控。业务代码不得
  直接调用 `System.currentTimeMillis()`、`Instant.now()` 或其他底层时间 API。
- ADR 0067 的 `BaseDomain` 普通创建是明确例外：创建入口只接收可信 Session，
  创建时间由统一的 `TimeUtil` 在基类构造路径中取得；恢复入口仍显式沿用持久化时间。
- 领域对象不得自行读取客户端时间，也不得接受调用方提交的审计 timestamp 作为
  服务器事实。
- `State` 记录状态真实发生时间的方式属于 State 领域设计，不属于项目通用时间
  规则，具体见
  [`ADR 0017`](../decisions/0017-centralize-workflow-runtime-state-in-flow-domain.md)。

### 数据库和第三方边界

数据库和第三方框架可以使用自身要求的日期时间类型，但只能存在于生成代码、Entry、
Repository 或专用 Adapter 的局部转换边界。PostgreSQL `timestamptz` 对应的 JOOQ
`OffsetDateTime` 必须与 Unix timestamp 毫秒值转换，不能泄漏到 Core：

```java
static OffsetDateTime toDatabaseTime(long timestamp) {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(timestamp),
        ZoneOffset.UTC
    );
}

static long fromDatabaseTime(OffsetDateTime value) {
    return value.toInstant().toEpochMilli();
}
```

- 时间转换集中在对应 Entry 或稳定的基础设施转换组件中。
- 写入和回读均以毫秒精度为准；外部系统提供更高精度时，进入项目自有 Java 类型
  前统一转换为毫秒。
- Java 使用 timestamp 毫秒值不要求把 PostgreSQL `timestamptz` 改成 `bigint`。

## 4. 复用与小范围重构

### 新增前先检查

新增能力前必须搜索项目中是否已经存在相同或相近的能力。检查范围至少包括：

- 相同领域和相邻 Module 中的类、Interface 与方法。
- 已有公共组件、领域行为和基础设施 Adapter。
- `docs/standards/` 与 `docs/decisions/` 已确定的实现模式和边界。
- 已有测试中能够说明现有能力用法的场景。

不能只比较名称或代码形状，还必须比较业务语义、输入输出、生命周期、事务边界、
变化原因和异常约定。

### 复用优先级

确认语义一致时，按以下顺序处理：

1. 直接使用已经存在的类型或方法。
2. 在不破坏现有职责和调用方的前提下，为现有能力增加必要的通用扩展。
3. 现有能力确实不适用时，才新增类型或方法。

新增实现时，应在任务说明或代码审查中回答“为什么现有能力不能复用”。不得复制
已有实现后只修改名称或少量条件，也不得创建职责重叠的 Service、Repository、
Handler、工具类或领域对象。

业务语义、生命周期、事务边界、变化原因不同，或者复用会造成反向依赖时，应保持
能力独立，不能为了减少代码制造错误抽象。

### 工具类优先与能力补充

- 实现时间、ID、必填值校验、Session/User 提取、字符串处理、集合处理、格式转换等
  通用能力前，必须先搜索当前 Module、可依赖的项目 Module 以及项目已使用的公共库，
  确认是否已经存在对应的 `utils` 工具类或方法。不能只按预想名称搜索，还应搜索底层
  API、相近方法名和现有调用点。
- 已有工具方法能够表达相同语义时必须直接复用，不得在 Service、Handler、Domain、
  Repository、Controller 或其他工具类中重复实现，也不得再包装一层同义方法。例如
  当前 timestamp 使用 `TimeUtil.now()`，技术 ID 使用 `StringUtil.newId()`，Session
  信息通过 `SessionUtil` 提取。
- 找到职责匹配的工具类但缺少所需能力时，必须先判断该能力是否是该工具类自然拥有的
  通用操作。职责一致时必须在已有工具类中补充方法和测试，并让当前调用链使用新增
  方法，不能绕过工具类直接调用底层 API。
- 没有职责匹配的工具类时，必须判断缺少的是工具方法还是一个新的工具类。稳定、无状态、
  与具体业务对象无关且可跨调用点复用的能力，应新增名称和职责明确的 `*Util`；不得
  把互不相关的方法堆入 `CommonUtil`、`Utils`、`Helper` 等无明确边界的类型。
- 包含业务规则、聚合状态变化、事务、数据库访问、远程调用或框架 Adapter 语义的能力
  不属于通用工具方法，应留在拥有该语义的 Domain、Service、Repository 或 Adapter。
  不能为了“使用工具类”而抽走业务所有权或制造反向依赖。
- 新增或扩展工具类时，应保持方法输入输出明确、避免隐藏可变全局状态，并补充覆盖正常
  输入、边界值和失败情况的聚焦测试。只重构当前功能涉及的直接调用点，不顺带清理无关
  Module。
- 任务说明或代码审查应说明搜索过哪些现有工具入口、最终复用了哪个方法；如果补充方法
  或新增工具类，应说明为什么现有能力不足以及新能力应归属该工具类的理由。

### 第三次重复的复用评估

- 第一次出现某项逻辑时，实现当前明确需求，不为未知需求预先抽象。
- 第二次出现相似逻辑时，识别重复点并评估是否可以复用现有能力。
- 第三次出现时必须完成复用评估；确认业务语义、生命周期和变化原因一致时进行
  小范围重构，否则记录保持独立的理由。

保持独立的理由记录在任务说明、评审说明或相关设计文档中，至少说明差异所在以及
为什么抽取公共能力会损害语义或依赖方向。只有涉及公共接口、模块边界、数据模型
或架构选择时才需要 ADR。

即使尚未出现三次，发生以下情况之一时也应立即评估小范围重构：

- 新功能只能通过复制已有实现完成。
- 同一规则需要在多个调用链中同步修改。
- 现有类或方法职责已经重叠，调用方无法明确选择。
- 为复用现有能力必须持续增加与其核心职责无关的条件分支。
- 重复代码可能造成业务规则、事务行为或异常处理不一致。

### 小范围重构边界

- 重构范围只覆盖当前功能涉及的业务链路和直接依赖。
- 重构前补充或确认保护现有行为的测试，重构后运行目标测试和相关回归测试。
- 优先提取稳定业务概念、领域行为或协作 Interface，不只提取表面相似代码。
- 不在一次功能开发中顺带改造无关 Module。
- 重构不能制造反向依赖、循环依赖或无语义的 `common`、`shared`、`support`。
- 改变架构、模块边界、数据模型或公共接口时，应停止按“小范围重构”处理，先确认
  ADR。

## 5. 专题规范联动

- 涉及领域术语、聚合、实体、值对象、领域创建、Factory 或语义所有权时，必须
  阅读 [`domain-object-modeling.md`](domain-object-modeling.md)。
- 涉及项目目录、Core 分包、Executor、Worker 或 Plugin 时，必须阅读
  [`../project-structure.md`](../project-structure.md) 和
  [`../decisions/README.md`](../decisions/README.md) 中对应的架构决策。
- 涉及 JSON 时阅读 [`json.md`](json.md)；手写代码统一使用 `org.paas.json`。
- 涉及 PostgreSQL 表、约束、索引或基线文件时阅读
  [`postgresql-schema.md`](postgresql-schema.md)。
- 涉及 Command、Handler、Session 或写事务时阅读
  [`command-executor.md`](command-executor.md)。
- 涉及 JOOQ、Entry 或持久化映射时阅读 [`jooq.md`](jooq.md)。
- 涉及 UC 测试时阅读 [`uc-testing.md`](uc-testing.md) 和对应 Agent 文档。
