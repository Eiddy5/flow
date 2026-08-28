# ADR 0015：Java 时间统一使用 Unix timestamp 毫秒值

## 状态

Accepted（PostgreSQL 时间列保留 `timestamptz` 的条款由
[`ADR 0075`](0075-use-bigint-time-and-application-validation.md) 取代）

## 背景

Flow 当前在领域对象中使用 `Instant`，PostgreSQL 使用 `timestamptz`，JOOQ 生成
类型使用 `OffsetDateTime`。如果日期时间类型继续跨越 Domain、Command、
Handler、DTO 和 Repository 边界，调用方必须反复处理类型转换、时区和序列化
差异，也难以形成统一的 Java 接口约定。

项目需要统一自有 Java 类型的时间表示，同时保留 PostgreSQL 原生时间能力和
JOOQ 生成代码边界。

## 备选方案

### 方案一：所有 Java 层统一使用 Instant

时间语义明确，但会让 `java.time` 类型进入所有业务接口，并与要求使用 `long`
的项目 Java 类型约定冲突。

### 方案二：Java 和 PostgreSQL 都使用 bigint 毫秒

端到端类型一致，但会放弃 PostgreSQL `timestamptz` 的原生查询、约束和运算能力，
并产生不必要的数据库迁移。

### 方案三：业务 Java 使用 long，数据库边界负责转换

项目自有 Java 业务类型使用 Unix timestamp（Epoch）毫秒值 `long`，PostgreSQL
继续使用
`timestamptz`，JOOQ 生成类型继续使用 `OffsetDateTime`，由 Entry 或 Repository
在基础设施边界集中转换。

## 决策

采用方案三：

- 项目自有 Java 业务类型中的必填时间点统一使用原始类型 `long`。
- 时间点统一表示 UTC Unix timestamp 毫秒值。
- 只有确有“尚未发生”或“未知”语义的时间才使用可空 `Long`，不得用魔法数字
  表示不存在。
- 时长、超时和间隔使用 `long` 毫秒，名称必须包含 `Millis` 单位。
- `Instant`、`OffsetDateTime` 等日期时间类型只允许存在于 JOOQ 生成代码、
  Entry、Repository 或第三方适配代码的局部转换边界，不能进入 Core 或其他
  项目自有业务接口。
- PostgreSQL 时间列继续使用 `timestamptz`，不迁移为 `bigint`。
- Entry 或稳定的基础设施转换组件统一负责 `long` 与数据库时间类型的双向转换，
  项目业务时间精度固定为毫秒。
- 当前时间在调用边界获取一次并以 `long` 显式传入；领域对象不直接读取系统时钟。

本决策取代 ADR 0014 中“Java Entry 统一转换为 `Instant`”的时间表示条款。
ADR 0014 关于 Flow 来源、Reversion 和 PostgreSQL `timestamptz` 的其他决策继续
有效。

## 理由

- 所有项目自有 Java 接口使用同一种数值类型和单位，减少跨层类型转换。
- Unix timestamp 毫秒值不携带本地时区，能够稳定表达同一个绝对时间点。
- 保留 `timestamptz`，无需为 Java 类型约定改变数据库 Schema。
- 把日期时间对象限制在基础设施边界，避免数据库和框架类型泄漏到 Core。
- 固定毫秒精度后，序列化、比较和测试断言使用同一尺度。

## 后果

- 存量领域对象、Command、Handler、DTO 和测试中的 `Instant` 等时间类型需要按
  业务链路逐步迁移为 `long`。
- Repository Entry 必须补充并验证 Epoch 毫秒与 `OffsetDateTime` 的双向转换。
- 数据库中高于毫秒的精度进入业务 Java 类型时会被截断，往返测试必须按毫秒
  精度断言。
- 可空时间迁移为 `Long` 时，必须同时验证对应状态和审计字段的一致性。
- 具体编码规则以
  [`development.md`](../standards/development.md)
  为准。
