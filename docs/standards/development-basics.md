# 基础开发规范（开发必读）

## 适用范围

本规范适用于本仓库中的所有生产代码、测试代码和代码生成逻辑。开发者和
AI Agent 在开始编码或审查代码前必须阅读本文件。

本文件规定项目级基础约束。领域、模块或场景规范可以在此基础上增加更严格的
要求，但不得降低或绕过本文件的约束。确需例外时，必须先在
`docs/decisions/` 中记录并确认对应架构决策。

## 1. ID 生成规则

### 统一生成入口

- 系统内部新建实体、聚合、执行实例或其他持久化对象时，技术 ID 统一使用
  `org.paas.common.util.StringUtil.newId()` 生成。
- 技术 ID 的 Java 类型统一为 `String`。
- ID 只在对象首次创建时生成一次。对象重建、持久化回读、复制快照或状态更新
  时必须沿用原 ID，不得重新生成。
- Service、Handler、Domain 和 Repository 之间传递同一个 ID，不得由不同层
  重复生成。

正确示例：

```java
import org.paas.common.util.StringUtil;

String executionId = StringUtil.newId();
```

领域对象必须在自身的静态 `create(...)` 方法中完成首次创建并生成 ID：

```java
public static Execution create(/* 创建参数 */) {
    return new Execution(
        StringUtil.newId()
        // 其他创建参数
    );
}
```

### 禁止写法

项目代码不得自行实现或选择其他技术 ID 生成方式，包括但不限于：

```java
UUID.randomUUID();
System.currentTimeMillis();
new Random().nextLong();
```

也不得对 `StringUtil.newId()` 的结果自行截断、拼接、修改大小写或删除字符。
ID 的具体格式由公共库负责，业务代码不得依赖或重复实现该格式。

### 技术 ID 与业务标识

- `id` 是系统内部技术主键，由 `StringUtil.newId()` 生成。
- `key`、编码、名称等业务标识由业务规则或外部输入确定，不能调用
  `StringUtil.newId()` 代替，也不能代替技术 ID。
- 接收已有对象 ID 的查询、更新、删除和恢复操作，应校验并使用调用方提供的
  ID，不得生成新 ID。
- 数据库自增 ID、第三方 ID 或其他生成策略只有在已确认的 ADR 明确要求时才可
  使用。

### 测试与审查

- 测试应验证新对象的 ID 非空，并验证保存、读取和状态变化过程中 ID 保持不变。
- 除非公共库契约明确规定，不应在业务测试中断言 ID 的长度、分隔符或字符格式。
- 代码审查时发现新增的 `UUID.randomUUID()`、时间戳 ID、自增 ID 或自定义随机
  ID，应判定为不符合本规范。
- 存量代码中不符合本规则的实现属于待迁移项；新代码不得继续复制该写法。
  修改相关存量链路时，应在保证接口、数据和持久化兼容性的前提下完成迁移。

## 2. 领域对象创建规则

- 聚合根、实体和值对象的普通业务创建入口统一定义为类型自身的
  `public static create(...)` 方法。
- 领域对象构造方法必须为 `private` 或 `protected`，不能由 Handler、Service、
  Repository 或其他调用方直接 `new`。
- `create(...)` 必须一次完成参数校验、不变量保护、防御性复制、技术 ID 生成和
  初始状态设置，不能先创建半成品再调用 Setter 或初始化方法补全。
- Handler、Service 或领域编排代码直接调用目标领域类型的 `create(...)`；
  不得新增 `XxxFactory`、`XxxDomainFactory` 等工厂类或工厂接口包装领域对象创建。
- 具体领域子类型分别提供自己的静态 `create(...)`。解析器、类型分派器和
  Adapter 可以负责选择具体类型，但最终必须调用该具体类型的 `create(...)`，
  不能自行复制构造规则。
- 从持久化数据恢复对象不属于普通业务创建，必须使用语义明确的静态
  `rehydrate(...)`，沿用已有 ID、状态和版本，不得调用 `create(...)` 生成新
  身份或触发首次创建规则。
- `deploy`、`parse` 等明确的业务转换方法可以保留其领域动词，但不能作为绕过
  目标领域类型 `create(...)` 和构造约束的通用对象工厂。

## 3. Java 类型定义规则

### 统一使用 class

- Java 类型统一使用 `class` 定义，不使用 Java `record`。
- Domain、Command、DTO、请求参数、响应结果、Snapshot、View 和测试辅助类型均
  遵守本规则。
- 不需要被继承的类型应声明为 `final class`。
- 需要不可变语义时，使用 `private final` 字段、构造方法和只读访问方法实现，
  不能因为禁止使用 `record` 而将对象改为可变对象。
- 集合字段应在构造时进行防御性复制，对外返回只读集合。

正确示例：

```java
public final class ExecutionSnapshot {

    private final String id;
    private final ExecutionStatus status;

    public ExecutionSnapshot(String id, ExecutionStatus status) {
        this.id = id;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public ExecutionStatus getStatus() {
        return status;
    }
}
```

### 禁止写法

不得新增以下形式的类型：

```java
public record ExecutionSnapshot(
    String id,
    ExecutionStatus status
) {
}
```

### 兼容与迁移

- 对象需要值相等语义时，应在 `class` 中明确实现 `equals()` 和 `hashCode()`。
- 对象需要可读字符串表示时，应明确实现 `toString()`，不得依赖 `record` 自动
  生成。
- 序列化、反序列化和框架绑定应基于普通 `class` 配置，并通过对应测试验证。
- 存量 `record` 属于待迁移项；新代码不得继续使用。修改相关类型时，应同步检查
  构造调用、访问方法、相等性判断和序列化协议，避免迁移后行为变化。

### 时间统一使用 long

项目自有 Java 类型中的时间点统一使用 Unix Epoch 毫秒值，不使用 Java 日期时间
对象作为字段、方法参数或返回值。

- Domain、Command、Query、Service、Handler、DTO、View、事件和测试辅助类型中的
  必填时间点统一使用原始类型 `long`。
- `long` 时间点的单位固定为毫秒，时区基准固定为 UTC。例如 `createdAt` 的值等于
  从 `1970-01-01T00:00:00Z` 到该时间点经过的毫秒数。
- 只有业务上确实存在“尚未发生”或“未知”语义的可空时间才允许使用 `Long`；
  `null` 只表示时间不存在，其非空值仍是 Epoch 毫秒。禁止使用 `0`、`-1` 或其他
  魔法数字表示时间不存在。
- 时间点字段继续使用 `createdAt`、`updatedAt`、`deletedAt`、`startedAt` 和
  `completedAt` 等业务名称；因为单位已由本规范统一，不重复添加 `Millis` 后缀。
- 时长、超时和间隔也使用 `long` 毫秒，但字段或参数名必须明确包含单位，例如
  `timeoutMillis`、`durationMillis`，不能使用含义不明的 `timeout` 或 `duration`。
- 禁止在项目自有业务类型的字段和公开方法签名中使用 `Instant`、
  `OffsetDateTime`、`LocalDateTime`、`ZonedDateTime`、`Date` 或 `Timestamp`。

正确示例：

```java
public final class ExecutionSnapshot {

    private final long createdAt;
    private final Long completedAt;

    public ExecutionSnapshot(long createdAt, Long completedAt) {
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public long createdAt() {
        return createdAt;
    }

    public Long completedAt() {
        return completedAt;
    }
}
```

数据库和第三方框架可以使用自身要求的日期时间类型，但这些类型只能存在于生成
代码或基础设施适配边界。当前 PostgreSQL `timestamptz` 对应的 JOOQ
`OffsetDateTime` 必须在 Entry 或 Repository 映射处与 Epoch 毫秒 `long` 转换，
不能泄漏到 Core 或其他项目自有业务接口：

```java
static OffsetDateTime toDatabaseTime(long epochMillis) {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(epochMillis),
        ZoneOffset.UTC
    );
}

static long fromDatabaseTime(OffsetDateTime value) {
    return value.toInstant().toEpochMilli();
}
```

时间转换必须集中在对应 Entry 或稳定的基础设施转换组件中。写入和回读都以毫秒
精度为准；数据库或第三方系统提供更高精度时，进入项目自有 Java 类型前统一转换
为毫秒。领域对象仍不得自行读取系统时钟，当前时间由调用链在边界获取一次并以
`long` 显式传入，以保证同一次业务操作时间一致且测试可控。

存量 `Instant`、`OffsetDateTime` 等业务字段和方法签名属于待迁移项。修改相关
业务链路时，必须同步迁移调用方、Entry 转换、序列化契约和测试；基础设施中的
JOOQ 生成字段不因此改为 `long`，数据库列也不因此改为 `bigint`。

## 4. 复用优先与小范围重构规则

### 新增前先检查

进行功能开发或业务开发时，在新增类、接口或方法前，必须先搜索项目中是否已经
存在相同或相近的能力。检查范围至少包括：

- 相同领域和相邻模块中的类、接口与方法。
- 已有公共组件、工具类、领域行为和基础设施适配器。
- `docs/standards/` 与 `docs/decisions/` 中已经确定的实现模式和模块边界。
- 已有测试中能够说明现有能力使用方式的场景。

不能只根据类名或方法名判断是否可复用，还应比较其业务语义、输入输出、生命周期、
事务边界和异常约定。

### 复用优先级

确认语义一致时，按以下顺序处理：

1. 直接使用已经存在的类、接口或方法。
2. 在不破坏现有职责和调用方的前提下，为现有能力增加必要的通用扩展。
3. 现有能力确实不适用时，才新增类、接口或方法。

新增实现时，应在代码或任务说明中能够回答“为什么现有能力不能复用”。不得复制
已有实现后仅修改名称或少量条件，也不得创建职责重叠的 Service、Repository、
Handler、工具类或领域对象。

复用不是强行共用。业务语义不同、生命周期不同、事务边界不同或复用会造成反向
依赖时，应保持能力独立，不能为了减少类或方法数量制造错误抽象。

### 重构触发规则

默认遵循“三次法则”：

- 第一次出现某项逻辑时，实现当前明确需求，不为未知需求预先抽象。
- 第二次出现相似逻辑时，识别重复点并评估是否可以复用现有能力。
- 第三次出现相同业务语义或结构性重复时，必须先进行一次小范围重构，再继续叠加
  新实现。

即使尚未出现三次，发生以下情况之一时也应触发小范围重构：

- 新功能只能通过复制已有实现完成。
- 同一规则需要在多个调用链中同步修改。
- 现有类或方法职责已经重叠，调用方无法明确选择。
- 为复用现有能力必须不断增加与其核心职责无关的条件分支。
- 重复代码已经可能造成业务规则、事务行为或异常处理不一致。

### 小范围重构边界

- 重构范围只覆盖当前功能涉及的业务链路和直接依赖。
- 重构前补充或确认能够保护现有行为的测试，重构后运行目标测试和相关回归测试。
- 优先提取稳定的业务概念、领域行为或通用协作接口，不只提取表面相似的代码片段。
- 不在一次功能开发中顺带改造无关模块。
- 如果重构会改变架构、模块边界、数据模型或公共接口，应停止按“小范围重构”
  处理，先在 `docs/decisions/` 中记录并确认对应决策。

## 5. Core 业务模块分包规则

### 目录结构

`server/src/main/java/org/cses/flow/core` 下的每一个技术职责目录，必须继续按照
业务模块建立子目录，不能将不同业务的类直接平铺在技术职责目录下。
本规则适用于 `commands`、`domains`、`exceptions`、`handlers`、`queries`、
`repositories`、`services` 以及后续新增的业务技术目录。
`serializers` 是经 ADR 0013 确认的格式能力目录，必须保持扁平，不适用业务
分包规则。

例如 Flow 业务的 Command 必须放在：

```text
core/
  commands/
    flows/
      CreateFlowCommand.java
```

对应的包名为：

```java
package org.cses.flow.core.commands.flows;
```

不同技术职责下应使用一致的业务模块名称：

```text
core/
  commands/
    flows/
    executions/
  handlers/
    flows/
    executions/
  domains/
    flows/
    executions/
  queries/
    flows/
    executions/
  repositories/
    flows/
    executions/
  services/
    flows/
    executions/
```

### 分包规则

- 业务模块目录使用小写英文复数命名，例如 `flows`、`executions` 和
  `externaltasks`。
- 同一条业务链路在不同技术职责目录下必须使用相同的业务模块名。例如
  `commands/flows/CreateFlowCommand` 对应
  `handlers/flows/CreateFlowHandler`。
- Java `package` 必须与文件目录完全一致，不得通过不一致的包声明绕过目录规则。
- 新增业务类前必须先确认所属业务模块；不能为了方便直接放在任意 Core 技术职责
  根目录。
- 跨业务模块的基础类型也必须放入含义明确的子模块，例如 `shared` 或
  `support`，不得平铺在技术职责目录根部。
- `shared` 和 `support` 不能成为无法归类代码的收容目录。只有被多个业务模块
  稳定复用且不属于任一业务模块的类型才允许放入其中。

### 模块边界

- 业务类优先依赖本模块内的类型。
- 跨业务模块协作应通过明确的 Service、Repository 接口或稳定领域能力完成，
  不得直接依赖其他模块的内部实现。
- 新建业务模块前必须执行本规范的“复用优先”检查，避免为已有业务概念创建同义
  模块。
- 一个功能同时涉及多个业务模块时，按各个类实际承担的业务职责分别归档，不能把
  整条调用链全部放入发起方模块。

### 存量代码迁移

- 当前直接平铺在 Core 技术职责目录下的业务类属于待迁移项，新代码不得继续沿用
  平铺结构。
- `src/test/java` 下的测试目录必须镜像 `src/main/java` 的模块和包结构，不能为
  Core 测试建立与生产代码不对应的独立目录体系。
- 修改存量业务链路时，应以能够独立编译和验证的最小完整链路为单位迁移相关类，
  并同步更新包声明、导入、组件扫描和测试。
- 不得只移动单个类而留下职责相同、模块名称不一致的半迁移结构。

### Serialization 目录例外

- `core/serializers` 只保存与业务类型解耦的序列化、反序列化和格式解析能力。
- 该目录不建立 `flows`、`tasks`、`shared` 等子目录。
- `YamlParser` 可以依赖具体 YAML 库，但不能依赖 Flow、Task、Command、
  Repository 或具体 Task 扩展。
- Flow 或 Task 字段解释、领域身份生成和领域对象创建不得进入
  `core/serializers`。

## 6. Executor 与 Worker 顶层包边界

- `executor` 和 `worker` 是
  `server/src/main/java/org/cses/flow/` 下与 `core` 平级的顶层包，不是 Core
  的技术职责子目录。
- `ExecutorContext`、`ExecutorService` 和 `NextTask` 统一放在 `executor`；
  Core 中不得重新建立 `executors` 或放置 Executor 状态机实现。
- `WorkerContext`、`WorkerDispatcher`、`WorkerTask`、`WorkerTaskHandler`、
  `WorkerTaskOutcome` 和 `WorkerTaskResult` 统一放在 `worker`；Core 中不得重新
  建立 `workers` 或复制 Worker 协议。
- `core/handlers/executions/ExecutionHandler` 负责当前命令事务内的 Repository
  保存和运行生命周期协调，并委托顶层 Executor 与 Worker，不迁入任一运行组件。
- AUTO、PAUSE 等具体 Task 类型和 `WorkerTaskHandler` 实现继续放在
  `extensions`；顶层 Executor 与 Worker 调度器只能依赖扩展协议，不能依赖具体
  扩展实现。
- Java `package` 必须与上述目录一致。移动运行组件时必须同步更新生产代码、
  测试、Micronaut Bean 装配和架构约束测试。
