# JOOQ 生成类与 Entry 使用规范

## 适用范围

本规范适用于 Flow 项目中所有使用 JOOQ 生成类进行查询、插入、更新、删除和领域
对象持久化的代码。

本规范重点约束：

- JOOQ 生成代码的边界。
- 生成类与 `XxxEntry` 的关系。
- `XxxEntry` 与领域对象的转换。
- `fetch`、`fetchInto`、`fetchOne` 和 `fetchOneInto` 的选择与映射条件。
- `buildInsertMap()`、字段级 `set(...)` 和 `buildUpdateMap()` 的使用场景。

本次确认的硬性规则可以先归纳为：

| 场景 | 统一写法 | 禁止写法 |
| --- | --- | --- |
| 完整表行单条读取 | `fetchOneInto(XxxEntry.class)` | `fetchOne()` 后再转 Entry |
| 完整表行批量读取 | `fetchInto(XxxEntry.class)` | `fetch()` 后逐条转 Entry |
| 标量/聚合读取 | `fetchOneInto(Type.class)` 或显式取值 | 为了映射对象强行构造 Entry |
| 单条完整插入 | `.set(entry.buildInsertMap())` | 逐字段 `.set(...)` |
| 单条完整更新 | `.set(entry.buildUpdateMap())` | 逐字段拼完整对象 |
| 批量插入 | 一个 INSERT，多次 `.values(...)`，一次 `execute()` | 逐条 INSERT、`newRecord()`、`batchInsert` |

读取完整表行时，JOOQ `Record` 不参与 `Entry` 映射；批量写入时可以使用 Entry 的
`toRecord()` 作为一行 `VALUES`。这两个场景必须明确区分。

## 1. JOOQ 生成代码位置

Flow 数据库对应的 JOOQ 生成代码统一位于：

```text
gen/src/main/java/org/flow/gen/flow/
```

Java 根包为：

```java
org.flow.gen.flow
```

主要生成内容：

```text
org/flow/gen/flow/
├── Tables.java       # 所有表的快捷入口
├── Keys.java         # 主键、唯一键和外键
├── Indexes.java      # 索引定义
├── tables/           # XxxTable
├── records/          # XxxRecord
└── pojos/            # XxxObject
```

例如：

```java
import static org.flow.gen.flow.Tables.FLOWS;
import org.flow.gen.flow.pojos.FlowsObject;
```

`org.flow.gen.flow` 下的文件由 JOOQ Generator 生成，禁止直接手工修改。数据库结构
改变后应修改数据库脚本并重新生成代码，不能在生成类中补业务方法。

`gen` 是 JOOQ 生成代码和数据库脚本的源码归属。开发期 Schema 由
`gen/sql/flow/001_create_flow_tables.sql` 完整入口与
`gen/sql/flow/tables/<table_name>.sql` 表级脚本共同定义；文件隔离和表命名遵循
[`postgresql-schema.md`](postgresql-schema.md)。开发或部署人员必须在应用启动前
手工执行完整入口，`core` 和 `server` 不复制迁移资源，也不自动创建或升级 Schema。
基线变化时必须重建开发数据库并重新生成 JOOQ，不为旧 Schema 或旧数据编写升级
脚本。业务代码仍不能依赖 Generator 或 JOOQ Codegen 实现。

Flow 运行时中的 `org.x9.jooq.JOOQ`、JOOQ `Configuration` 和 `DataSource` 必须
使用 `@Named("flow")` 绑定。宿主应用即使同时存在 `default`、`mattermost` 等数据
源，Flow Repository 和事务也不得回退到这些数据源。宿主只配置标准
`datasources.flow.*`；Micronaut Hikari、Micronaut JOOQ 与 PAAS JOOQ 根据具名
DataSource 自动创建同名 Bean；由于 PAAS 默认 Factory 对 Configuration 使用无限定名
注入，Flow 通过 `NamedJooqFactory` 按 DataSource 名称选择同名 Configuration，避免
嵌入宿主提供额外无限定名 Configuration 时产生候选冲突。
`jooq.datasources.flow` 只用于显式覆盖方言或 JOOQ Settings，不是必填配置。

## 2. 生成类提供的快捷能力

当前生成的 `XxxObject` 继承：

```text
XxxObject -> JooqPojo -> JooqObject
```

因此除字段访问方法外，还提供以下数据库快捷能力：

| 方法 | 用途 |
| --- | --- |
| `table()` | 返回对象对应的 JOOQ Table |
| `primaryKeyNames()` | 返回数据库主键字段名 |
| `primaryKeyValues()` | 返回当前对象的主键值 |
| `toMap()` | 按数据库字段名构造 Map，并处理 JSON、枚举等类型 |
| `toRecord()` | 构造对应的 JOOQ Record |
| `buildInsertMap()` | 构造完整对象插入使用的非空字段 Map |
| `buildUpdateMap()` | 构造完整对象更新使用的非主键、非空字段 Map |
| `get(Field)` | 按 JOOQ Field 读取转换后的字段值 |

具体数据库 Adapter（包括 Repository）应优先使用这些生成能力，不得为每张表重复
实现反射、字段名转换、JSON 转换或主键过滤。

### `buildInsertMap()` 的当前行为

- 使用生成对象的 `toMap()`。
- 保留所有非空字段，包括主键。
- 跳过值为 `null` 的字段，使数据库默认值可以生效。
- 结果可以直接传给 JOOQ 的 `.set(Map<String, Object>)`。

### `buildUpdateMap()` 的当前行为

- 使用生成对象的 `toMap()`。
- 自动排除主键字段。
- 自动排除值为 `null` 的字段。
- 结果用于全对象更新或 `onDuplicateKeyUpdate`。

因为 `buildUpdateMap()` 会忽略 `null`，需要把数据库字段明确更新为 `NULL` 时，
必须使用字段级 `set(TABLE.FIELD, null)`，不能使用全对象更新 Map。

## 3. 必须通过 XxxEntry 使用生成对象

业务数据库 Adapter 不得直接把生成的 `XxxObject` 当作项目持久化模型使用。每一张
被业务使用的表都必须建立一个语义清晰的 `XxxEntry`，并继承对应生成对象。

例如 `flows` 表生成了 `FlowsObject`，项目中应建立：

```java
public final class FlowEntry extends FlowsObject {
}
```

命名关系：

```text
FlowsObject        -> FlowEntry
ExecutionsObject   -> ExecutionEntry
TaskRunsObject     -> TaskRunEntry
```

`XxxObject` 的名称来自数据库表，`XxxEntry` 的名称应表达项目中的持久化语义，不
要求机械保留表名的单复数形式。

### 禁止直接使用

以下代码不得出现在具体数据库 Adapter 之外的业务代码中：

```java
FlowsObject object = new FlowsObject();
ExecutionsRecord record = new ExecutionsRecord();
```

Core Domain、Service、Handler、Controller 和 Command 不得直接依赖：

```text
org.flow.gen.flow.*
```

生成的 Table、Field 和 Keys 只能出现在具体数据库 Adapter 实现及必要的数据库
映射测试中；生成的 `XxxObject` 只能作为 `entries` 子包中 `XxxEntry` 的父类使用。
数据库 Adapter 的数据读写结果必须经过 Entry，不能绕过 Entry 直接返回或保存
`XxxObject`。

## 4. Entry 目录

`XxxEntry` 必须放在它所属的具体数据库 Adapter 实现下面的 `entries` 子包中。

推荐结构：

```text
core/src/main/java/org/cses/flow/infrastructure/
└── repositories/
    └── flows/
        ├── FlowRepositoryImpl.java
        ├── entries/
        │   ├── FlowEntry.java
        │   └── FlowTaskEntry.java
        └── codec/
            ├── StateCodec.java
            └── TaskPropertiesCodec.java
```

`codec` 只在 Entry 确实需要序列化或专用字段转换时创建，并且必须与 `entries`
平级。具体使用规则见第 6 节。

其他业务模块使用相同结构：

```text
infrastructure/repositories/executions/entries/
```

非 Repository 的数据库 Adapter 同样把 Entry 放在自身实现下，例如：

```text
infrastructure/queues/entries/
```

Default Dispatch Queue 的 `JsonFactory`、`Class<T>` 和 DSL 排除规则由
[`ADR 0047`](../decisions/0047-implement-default-dispatch-queue.md) 定义，本文只约束
其 Queue Message 生成对象仍必须通过上述 `entries` 中的 Entry 使用。数据库 Queue
Adapter 的 Event payload 统一映射到 `queues`，使用
`queue_type + queue_name` 作为传输类别与逻辑 Queue 的查询边界；不得为 Dispatch 或
Broadcast 复制专属消息载荷表。未来某种消费方式需要游标、确认或保留状态时，可以在
对应 Adapter 下建立独立状态 Entry，但消息载荷仍归统一 Entry。

Entry 不放在：

```text
core/domains/
core/services/
core/repositories/                 # 此处只放 Repository 接口
extensions/
gen/src/main/java/org/flow/gen/    # 此处只放生成代码
```

一个 Entry 只服务于其具体持久化实现。不同数据库技术的字段结构或转换语义不同时，
各自维护自己的 Entry，不能让 Core 依赖一个跨数据库的持久化对象。

## 5. Entry 不是领域对象

`XxxEntry` 是直接与数据库交互的持久化对象，不是领域对象。

两者职责不同：

| 类型 | 职责 |
| --- | --- |
| Domain | 业务身份、状态、不变量和领域行为 |
| Entry | 数据库字段、JOOQ Map、Record 以及 Domain 与数据库之间的转换 |

领域对象的状态变化必须先通过领域方法完成。Repository 保存时，再把完整领域状态
转换为 Entry：

```text
Domain
  -> XxxEntry.from(domain)
  -> buildInsertMap() / buildUpdateMap()
  -> JOOQ
  -> Database
```

查询时执行反向转换：

```text
Database
  -> JOOQ fetchInto(XxxEntry.class)
  -> entry.to()
  -> Domain
```

不得把 Entry 从 Repository 返回给 Service、Handler 或 Controller，也不得在 Entry
中实现 Flow 发布、Execution 推进、TaskRun 完成等领域行为。

## 6. Entry 与 Domain 的转换接口

生成代码不能手工修改。需要与 Domain 双向转换的 `XxxEntry` 只提供 `from(...)` 和
`to(...)` 两类转换方法：

- `from(...)` 从 Domain 形成 Entry。
- `to(...)` 从当前 Entry 形成 Domain。

不得使用 `fromDomain(...)`、`toDomain(...)`、`toEntry(...)`、`toXxx(...)` 或
`of(...)` 建立同义转换入口。Repository 只调用 Entry 的 `from(...)` 和 `to(...)`，
不能复制字段转换逻辑。

### `from(...)`

`from(...)` 是 `XxxEntry` 的静态方法，返回一个完整的 Entry：

```java
public static ExecutionEntry from(Execution execution) {
    // Domain -> Entry
}
```

需要所属聚合、父节点或持久化顺序等额外事实时，将这些事实作为普通转换参数传入：

```java
public static FlowTaskEntry from(
    String companyId,
    String flowKey,
    long flowVersion,
    Task task,
    String parentId,
    int order
) {
    // Domain + 持久化结构事实 -> Entry
}
```

`from(...)` 只转换 Domain 已经形成的状态以及写入所需的持久化结构事实，不能执行
领域状态迁移。

### `to(...)`

`to(...)` 是当前 `XxxEntry` 的实例方法，返回对应 Domain：

```java
public Execution to() {
    // Entry -> Domain
}
```

恢复聚合需要 Repository 已经读取和恢复的子领域对象时，通过参数接收这些对象：

```java
public Execution to(List<TaskRun> taskRuns) {
    // Entry + 已恢复的子领域对象 -> Domain
}
```

数据库恢复属于数据库 Adapter。`to(...)` 负责当前 Entry 的字段转换，Repository
负责查询、排序和关联装配。Domain 已有确认的 `rehydrate(...)` 时，`to(...)` 可以
把转换后的纯领域事实交给该入口；不得把 Entry、JOOQ 或数据库专属类型传入 Domain，
也不得调用会重新生成身份或初始状态的普通创建入口。

### 转换场景统一使用重载

同一个 Entry 需要支持多种转换情况时，必须重载 `from(...)` 或 `to(...)`。不同情况
仍然表达同一个转换动作，不能通过增加方法名前后缀、布尔开关或可空序列化参数建立
平行入口：

```java
public static XxxEntry from(Domain domain) { ... }

public static XxxEntry from(
    String ownerId,
    Domain domain,
    int order
) { ... }

public Domain to() { ... }

public Domain to(List<ChildDomain> children) { ... }
```

### 序列化转换统一放在同级 `codec`

`from(...)` 和 `to(...)` 的参数只能表达 Domain、子领域对象或持久化结构事实，不能
接收 `JacksonMapper`、`ObjectMapper`、`JsonFactory`、序列化器或序列化策略。

确实需要序列化、反序列化或数据库专属字段转换时，在 `entries` 的同级建立
`codec` 子包。一个 Codec 只负责一种具体类型或字段的稳定转换，并提供静态方法；
序列化工具、配置和转换过程全部封装在方法内部：

```java
public class StateCodec {

    public static JsonObject encode(State state) {
        // State -> 数据库存储类型
    }

    public static State decode(JsonObject value) {
        // 数据库存储类型 -> State
    }
}
```

Entry 只静态调用对应 Codec：

```java
entry.state = StateCodec.encode(execution.state());
State state = StateCodec.decode(this.state);
```

Codec 不执行 SQL、不查询关联 Entry、不装配聚合，也不实现领域行为。不同字段不能
堆入 `EntryCodec`、`JsonHelper` 或其他职责不明确的通用类。

Entry 和 Codec 中的 JSON/JSONB 转换必须同时遵守 [`json.md`](json.md)。一般字段
统一使用 PAAS JSON；ADR 0026 确认的特殊序列化方式也必须封装在对应 Codec 内部，
不能作为 `from(...)` 或 `to(...)` 的参数传入。

转换必须覆盖恢复 Domain 所需的全部字段，不能为了简化映射构造只有部分状态的
领域对象。

项目自有 Java 类型的时间点统一为 Unix timestamp 毫秒值 `long/Long`，完整规则
见 [`development.md`](development.md)。Flow PostgreSQL Schema 的时间点、
时长、超时和间隔同样使用 `bigint` 毫秒值，JOOQ 生成类型和 Entry 不得为项目自有
时间字段引入 `OffsetDateTime` 或其他原生日期时间类型。

Entry 可以复用生成父类已有的 `toMap()`、`table()`、`buildInsertMap()` 和
`buildUpdateMap()`，不得重复实现同名通用能力。只有生成能力无法满足已确认的
数据库语义时才允许新增专用方法。

### 领域审计字段归领域所有权

创建人、创建时间、更新人、更新时间、删除人和删除时间等审计事实，必须先由领域
对象产生并由 `XxxEntry.from(...)` 原样映射。PostgreSQL Adapter 只负责写入、
读取和查询这些已经存在于领域对象中的字段，不得从 `DSLContext` 的配置上下文读取
当前 Session，不得在数据库 Adapter 中调用当前时间或拼装操作者 JSON，也不得建立
通用的 `PostgresAudit` 辅助类。

表结构只保留领域确实拥有的审计字段。当前 Flow 的完整审计状态属于 Flow 领域，仍
由 `FlowEntry` 持久化；Execution 只持久化 `BaseDomain` 提供的 `creator` 与
`createdAt`；TaskRun 不拥有独立审计时间或操作者字段，因此 `task_runs` 不保留这类
数据库字段。Queue 的 `created_at` 仅用于数据库消息投递顺序，属于 Queue Adapter 的
技术字段，不是领域审计。

数据库默认值、生成列或索引不得悄悄生成、复制或改变领域审计事实。若领域对象没有
对应字段，数据库也不应为它新增审计列；需要数据库并发、行锁或排序的字段，仍可由
具体数据库 Adapter 按基础设施协议处理。

## 6.1 Repository 私有实现的组织

Repository 的公开方法是 Core Repository Interface 在数据库 Adapter 上的实现；私有
方法是该 Adapter 的内部实现，不应把某一个领域分支直接当作方法的主要抽象。私有
方法名优先表达数据库动作、聚合装配或数据读取阶段，避免把草稿、正式版本、审计或
某个具体生命周期路径硬编码到方法名中。数据库实体与领域实体的转换名称和实现不
属于 Repository 私有方法。

同一个动作在不同阶段需要不同参数时，优先使用方法重载。重载必须满足“概念相同、
上下文不同”的条件，不能仅为了减少字符把不同语义的操作合并为同名方法：

| 内部概念 | 推荐的重载形态 | 两个阶段的区别 |
| --- | --- | --- |
| 结果恢复 | `restore(dsl, entry)` / `restore(entry)` | 前者按需要读取关联数据，后者使用已完整的 Entry |
| 保存 | `save(dsl, domain)` / `insert(dsl, entry)` | 由实体 ID 区分已有行更新和新行插入；聚合子记录只在需要时随新行写入 |
| 插入 | `insert(dsl, domain)` / `insert(dsl, entry)` | 前者协调聚合及子记录，后者只写入一行 |
| 聚合装配 | `restore(dsl, entry)` 或直接调用 `entry.to(children)` | Repository 查询关联数据，Entry 执行领域重建 |
| 子集合读取或写入 | `readChildren(...)` / `readChildren(entries, ...)`，以及 `writeChildren(...)` 的对应重载 | 数据库查询阶段与内存递归或批量 `VALUES` 阶段 |

例如，聚合插入可以由 `insert(dsl, Flow)` 调用 Entry 完成领域到 Entry 的转换并协调子集合，
再由 `insert(dsl, FlowEntry)` 负责单行 JOOQ 写入；两个方法都是“插入”，但参数分别
表示聚合编排阶段和数据库行阶段。任务树则可以由 `readTasks(DSLContext, ...)`
读取数据库，再由同名的 `readTasks(List<FlowTaskEntry>, ...)` 在内存中递归恢复。
这种组织方式把变化集中在一个动作下，调用方无需知道当前是草稿还是正式版本。

私有方法组织遵循以下规则：

1. 首先按 `find`、`insert`、`update`、`delete`、`restore`、`read` 和 `write` 等
   技术动作分组；状态差异只反映领域已经准备好的字段，不在 Repository 中实现领域
   状态迁移或生命周期判断。领域转换方法不属于 Repository 的私有动作。
2. 同一动作的重载由高层编排逐步调用低层实现，避免在 `insert`、`update` 或查询
   的多个分支中重复构造 Entry、租户条件、子集合写入和异常转换。
3. 一次性且没有复用价值的查询不要为了“看起来通用”额外包成私有方法；查询语义
   已经清晰时可以直接写在公开方法中。只有条件或映射确实复用，才抽取查询辅助方法。
4. 方法名不要使用 `insertDraft`、`insertReversion`、`updateDeployedAudit`、
   `restoreChildren` 这类把当前业务状态或历史命名带入实现动作的名称。状态差异应
   由领域对象先完成，Repository 只通过通用持久化动作写入结果。
5. 重载不能隐藏领域规则，也不能生成审计事实。领域不变量由领域对象负责；Repository
   只协调持久化协议、租户条件、子记录一致性、数据库冲突和对象重建。
6. Repository 不定义数据库实体与领域实体的转换方法。`from(...)` 和 `to(...)`
   只能位于对应的 `XxxEntry`；Repository 的 `restore` 只负责关联数据装配并调用
   Entry。
7. 私有方法不是新的跨 Repository 工具接口。只有至少两个 Adapter 具有相同且稳定
   的技术语义时，才考虑抽取共享基础设施；否则保持在当前 Adapter 内，保证修改和
   验证的局部性。

审查 Repository 时，应能从私有方法名称看出“正在执行哪种技术动作”，而不需要先
了解某个历史生命周期名称。若同一个概念出现多个带状态后缀的方法，应优先检查它们
是否可以合并为一组重载。

## 7. 完整对象插入

完整对象首次入库时：

1. 领域对象转换为 Entry。
2. 调用 Entry 继承的 `buildInsertMap()`。
3. 将结果整体传给 JOOQ。

正确示例：

```java
ExecutionEntry entry = ExecutionEntry.from(execution);

dsl.insertInto(EXECUTIONS)
    .set(entry.buildInsertMap())
    .execute();
```

禁止把完整对象拆成大量字段级 `set(...)`：

```java
dsl.insertInto(EXECUTIONS)
    .set(EXECUTIONS.ID, entry.id)
    .set(EXECUTIONS.COMPANY_ID, entry.companyId)
    .set(EXECUTIONS.FLOW_KEY, entry.flowKey)
    .set(EXECUTIONS.FLOW_VERSION, entry.flowVersion)
    .set(EXECUTIONS.STATE, entry.state)
    .execute();
```

字段级拼装容易遗漏新增字段、重复类型转换，并绕过生成对象已经提供的统一 Map
构建能力。

## 7.1 批量插入必须拼接 `VALUES`

批量保存必须构造一条 INSERT，并通过多次 `.values(...)` 拼接所有行，最后只执行
一次。完整 Entry 可以通过 `toRecord()` 作为一行值传入；需要保留数据库默认值的
字段时，应在 `columns(...)` 中省略该字段，并显式拼出其余值。

当前项目使用的 JOOQ 版本中，不能把 `buildInsertMap()` 直接作为一个参数传给
`.values(...)`。这种调用会把 Map 当成单个值，最终导致“值数量与字段数量不一致”。
批量完整行使用 `toRecord()`；需要省略默认值字段或只写入部分字段时，使用明确的
`columns(...)` 和展开后的值参数。

正确示例：

```java
InsertValuesStepN<TaskRunsRecord> values = dsl
    .insertInto(TASK_RUNS)
    .columns();
for (TaskRunEntry entry : entries) {
    values.values(entry.toRecord());
}
values.execute();
```

带数据库默认值的批量插入：

```java
var values = dsl
    .insertInto(QUEUES)
    .columns(QUEUES.ID, QUEUES.QUEUE_TYPE, QUEUES.QUEUE_NAME,
        QUEUES.EVENT_KEY, QUEUES.PAYLOAD);
for (QueueMessageEntry entry : entries) {
    values.values(
        entry.getId(), entry.getQueueType(), entry.getQueueName(),
        entry.getEventKey(), entry.getPayload());
}
values.execute();
```

禁止在批量保存中逐条调用 `insertInto(...).execute()`、使用
`newRecord().set(...)` 拼接，或使用 `batchInsert`/Record 批处理替代 `VALUES`。读取
表行仍必须遵守第 10 节的 `fetchOneInto(...)`/`fetchInto(...)` 直映射规则。

## 8. 更新

更新前必须先判断这是“字段级更新”还是“完整对象更新”。

### 字段级更新

只修改一个或少量明确字段时，直接使用 JOOQ 的字段级
`set(TABLE.FIELD, value)`：

```java
dsl.update(FLOWS)
    .set(FLOWS.SOURCE, source)
    .set(FLOWS.UPDATED_AT, updatedAt)
    .where(FLOWS.COMPANY_ID.eq(companyId))
    .and(FLOWS.ID.eq(flowId))
    .and(FLOWS.DRAFT.isTrue())
    .execute();
```

字段级更新必须：

- 只包含本次命令明确允许修改的字段。
- 使用生成的 Table Field，不手写数据库字段名。
- 带上完整业务身份和租户条件；持久化协议明确要求行锁或 CAS 时，同时带上对应的
  技术并发条件。
- 需要清空字段时显式调用 `.set(TABLE.FIELD, null)`。

调用 `entry.setState(...)` 只会修改内存中的 Entry，并不会执行数据库更新；真正的
字段级更新必须通过 JOOQ Update 的 `.set(TABLE.FIELD, value)` 完成。

### 完整对象更新

需要将一个完整领域对象当前状态写回数据库时，先转换为 Entry，再使用
`buildUpdateMap()`：

```java
ExecutionEntry entry = ExecutionEntry.from(execution);

dsl.update(EXECUTIONS)
    .set(entry.buildUpdateMap())
    .where(EXECUTIONS.COMPANY_ID.eq(entry.companyId))
    .and(EXECUTIONS.ID.eq(entry.id))
    .execute();
```

禁止在完整对象更新中逐个调用字段级 `set(...)`。新增数据库字段后，Entry 转换和
生成 Map 是唯一需要维护的字段边界。

`buildUpdateMap()` 不包含主键，也不包含值为 `null` 的字段。以下情况不能直接使用
它代替专用更新：

- 本次更新需要把某个字段设为 `NULL`。
- 只允许修改白名单中的少量字段。
- 更新需要数据库表达式，例如计数器自增。
- 更新需要基于旧版本做 compare-and-set。

这些情况应使用字段级 `set(...)` 和明确的 `where` 条件。

## 9. Upsert

确实需要完整对象 Upsert 时，插入与更新部分分别使用对应 Map：

```java
ExecutionEntry entry = ExecutionEntry.from(execution);

dsl.insertInto(EXECUTIONS)
    .set(entry.buildInsertMap())
    .onDuplicateKeyUpdate()
    .set(entry.buildUpdateMap())
    .execute();
```

是否允许 Upsert 由具体业务的生命周期和并发规则决定。不能为了减少代码默认把
所有 `save` 实现成 Upsert；需要区分首次创建、合法更新和冲突的聚合必须使用明确
的 insert/update/CAS 协议。

## 10. 查询结果、对象映射与领域重建

### 按结果数量和映射方式选择查询方法

查询方法先按业务预期的结果数量选择 `fetch` 或 `fetchOne`，再按是否需要 JOOQ
直接映射对象选择是否使用 `Into`：

| 方法 | 预期结果数量 | 返回形式 | 适用场景 |
| --- | --- | --- | --- |
| `fetch()` | 零到多条 | JOOQ `Result` / `Record` | 查询后还要读取字段、组合多表结果或执行自定义转换 |
| `fetchInto(Xxx.class)` | 零到多条 | 映射后的对象列表 | 每条查询记录都能直接、完整地映射为同一种对象 |
| `fetchOne()` | 零或一条 | 单个 `Record`；无记录时为 `null` | 单条查询结果仍需执行自定义读取或转换 |
| `fetchOneInto(Xxx.class)` | 零或一条 | 单个映射对象；无记录时为 `null` | 单条查询记录可以直接、完整地映射为目标对象 |

`fetchOne()` 和 `fetchOneInto(...)` 表达的是“至多一条”，不是“任取第一条”。实际
返回多条记录时 JOOQ 会报错，因此查询条件必须有唯一键、主键或其他明确的单结果
保证。可能合法返回多条记录时必须使用 `fetch`。

完整表行映射到 Entry 时，必须直接使用 `fetchOneInto(XxxEntry.class)` 或
`fetchInto(XxxEntry.class)`。不得先取得 JOOQ `Record`，再通过
`XxxEntry.fromRecord(...)` 或 `Record.into(XxxRecord.class)` 转成 Entry。这样可以让
查询结果的目标类型在 Repository 代码中明确表达，并避免为每张表重复维护 Record
到 Entry 的字段拷贝。

```java
ExecutionEntry entry = dsl.select()
    .from(EXECUTIONS)
    .where(EXECUTIONS.COMPANY_ID.eq(companyId))
    .and(EXECUTIONS.ID.eq(executionId))
    .fetchOneInto(ExecutionEntry.class);

List<TaskRunEntry> taskRuns = dsl.select()
    .from(TASK_RUNS)
    .where(TASK_RUNS.EXECUTION_ID.eq(executionId))
    .fetchInto(TaskRunEntry.class);
```

完整表行的字段类型转换由统一的 JOOQ `ConverterProvider` 和
`RecordMapperProvider` 负责。Flow 使用生成扩展提供的 JSONB/JSON 与
`JsonObject`/`JsonObjects` 转换，因此 Repository 不应在查询字段上追加
`Field.convertFrom(...)`，也不应在 Entry 中维护一套查询专用字段数组。这样生产
JOOQ 配置和直接 `fetchInto` 的语义保持一致；Entry 只负责领域对象与持久化对象的
写入组装和读取后的领域重建。

测试中如果绕过 Micronaut 直接用 JDBC 创建 `DSLContext`，也必须通过项目的 JOOQ
扩展配置（例如 `FlowJooqTestConfiguration.configure(...)`）增强配置，不能使用裸的
`DSL.using(connection, POSTGRES)` 代替生产 JOOQ 配置。否则 JSONB 到
`JsonObject`/`JsonObjects` 的转换行为会与生产不一致。

只有标量查询、聚合查询或确实需要自行组合的多表查询可以保留 JOOQ `Record`：

```java
long count = dsl.selectCount()
    .from(EXECUTIONS)
    .where(EXECUTIONS.COMPANY_ID.eq(companyId))
    .fetchOne(0, long.class);
```

以下情况可以使用不带 `Into` 的方法和显式读取：

- 查询包含多表 Join、聚合、计算字段或同名字段，需要自行决定组合语义。
- 查询结果需要组合成一个聚合，不能由单条记录直接表达。
- 需要根据某个字段执行条件分支，或需要区分字段缺失与字段值为 `null`。

### `Into` 直接映射的前提

带 `Into` 的方法会让 JOOQ 直接把查询结果映射为目标对象。只有同时满足以下条件
时才允许使用：

- 目标类型能够被 JOOQ 实例化，并提供与查询字段对应的可写属性，例如公共字段或
  Setter；项目中的 Entry 通常通过生成对象继承这些能力。
- `select` 返回的每个字段名或别名都能对应到目标对象的属性名。计算字段、重命名
  字段以及 Join 后的字段必须使用与目标属性对应的 `as(...)` 别名。
- 查询字段类型与目标属性类型一致，或存在已确认且经过测试的 JOOQ 类型转换。
- 领域重建所需的目标属性都包含在查询字段中；未被查询到的属性会保留为
  `null` 或 Java 默认值，不能据此构造不完整领域对象。

同表完整字段查询且 Entry 与生成对象字段一致时，可以直接映射：

```java
ExecutionEntry entry = dsl.select()
    .from(EXECUTIONS)
    .where(EXECUTIONS.COMPANY_ID.eq(companyId))
    .and(EXECUTIONS.ID.eq(executionId))
    .fetchOneInto(ExecutionEntry.class);
```

如果查询字段名称、别名、类型或转换逻辑与 Entry 不完全对应，应在查询中使用明确
的字段别名，或把它视为投影/聚合查询自行组装；不能为了绕过统一 JOOQ 类型映射，
在 Entry 中增加查询专用字段数组和 `Field.convertFrom(...)`，也不能退回到
`Record.into(...)` 或 `XxxEntry.fromRecord(...)`。

### 映射后重建领域对象

无论使用 `Into` 直接映射还是标量/聚合查询的自定义读取，Repository 都必须在返回
前调用 Entry 的领域转换方法：

```java
return entry == null
    ? Optional.empty()
    : Optional.of(entry.to(/* 关联数据 */));
```

聚合包含子记录时，由 Repository 读取所需的多个 Entry，再统一调用领域重建入口。
例如 Execution 与有序 TaskRun 应完整加载后再重建 Execution，不能把数据库
Entry 列表暴露给 Core 调用方。

## 11. 事务、租户与并发条件

- Repository 使用调用链传入的 `DSLContext`，不能自行创建新事务。
- 查询、更新和删除必须带上 companyId 等租户边界。
- 全对象 Map 只负责构造待写字段，不负责生成 `where` 条件。
- 领域确认的业务唯一键必须由 PostgreSQL 主键或唯一索引保护；首次插入发生竞争时，
  Repository 将数据库唯一冲突转换为稳定的持久化冲突，Domain 不承担竞争检测。
- 更新必须显式指定租户、主键或已确认的业务身份。若业务还要求发现同一已有行的
  并发覆盖，由 Repository 根据 ADR 选择行锁、CAS 或事务隔离实现。
- `lockVersion` 等纯技术并发字段只属于 Schema、Entry 和 Repository 更新条件，不
  进入 Domain，也不能与业务版本混用。
- `buildUpdateMap()` 自动排除主键，不代表更新语句可以省略主键条件。
- 使用 CAS 时更新条数必须符合预期；不符合时按持久化协议处理不存在或并发冲突，
  不能静默忽略。

事务边界见
[`command-executor.md`](command-executor.md)，领域与 Repository 边界见
[`domain-object-modeling.md`](domain-object-modeling.md) 及
[`../decisions/README.md`](../decisions/README.md) 中对应的聚合决策。

## 12. 禁止事项

- 禁止手工修改 `org.flow.gen.flow` 下的生成代码。
- 禁止让 Domain、Service、Handler、Command 或 Controller 依赖生成类。
- 禁止直接使用 `XxxObject` 替代 `XxxEntry`。
- 禁止把 Entry 当作领域对象或 API DTO。
- 禁止把领域状态转换散落在 Repository 的多个查询和保存方法中。
- 禁止完整对象插入时逐字段调用 `set(...)`。
- 禁止完整对象更新时逐字段调用 `set(...)`。
- 禁止字段级更新时为了方便构造一个不完整 Entry 并调用
  `buildUpdateMap()`。
- 禁止依赖 `buildUpdateMap()` 把字段更新为 `NULL`。
- 禁止在未确认查询字段名称、别名、类型和目标可写属性对应关系时使用
  `fetchInto(...)` 或 `fetchOneInto(...)`。
- 禁止用 `fetchOne()` 或 `fetchOneInto(...)` 从可能返回多条记录的查询中任取
  第一条。
- 禁止在没有 `where` 和租户条件的情况下执行 update/delete；持久化协议已确认并发
  条件时也不得遗漏该条件。

## 13. 开发与审查清单

新增或修改 JOOQ 数据库 Adapter（包括 Repository）时逐项检查：

1. 是否使用 `org.flow.gen.flow` 下的当前生成类。
2. 是否在具体数据库 Adapter 实现的 `entries` 子包建立了 `XxxEntry`。
3. Entry 是否继承正确的 `XxxObject`。
4. Domain 与 Entry 的双向转换是否集中在 Entry，并且只提供 `from(...)` 和
   `to(...)` 两类方法。
5. Core 是否完全不知道 JOOQ 生成类型和 Entry。
6. 完整插入是否使用 `buildInsertMap()`。
7. 字段级更新是否使用 JOOQ 字段级 `set(...)`。
8. 完整对象更新是否使用 `buildUpdateMap()`。
9. 查询方法是否根据结果基数正确选择了 `fetch` 或 `fetchOne`。
10. 使用 `Into` 时，查询字段名称、别名、类型和目标对象可写属性是否对应。
11. 是否正确处理 `null`、数据库默认值和生成字段。
12. 审计事实是否全部来自领域对象，Adapter 是否没有生成当前时间、操作者或删除
    状态。
13. 批量保存是否使用一条 INSERT、多次 `.values(...)` 和一次 `execute()`。
14. 是否没有在批量保存中使用 `values(Map)`、`newRecord()`、`batchInsert` 或逐条
    `execute()`。
15. 查询、更新和删除是否包含租户及主键/业务身份；已确认的并发协议是否完全由
    Schema、Entry 和 Repository 实现。
16. 是否复用了传入的 `DSLContext` 和已有事务。
17. 是否为转换、Map 内容、查询重建和保存行为补充了对应测试。
18. JSON/JSONB 转换是否遵守 `json.md` 并统一使用 PAAS JSON。
19. 是否没有在 Repository 或数据库中实现 Flow 的版本递增、删除发布限制、正式定义
    不可变或审计状态变化等领域规则。
20. Repository 私有方法是否按技术动作命名，而不是按草稿、版本或审计分支命名。
21. 相同概念的不同阶段是否通过合理重载复用，是否避免了重复的 Entry 转换和 JOOQ
    构造。
22. 是否没有为一次性查询添加无复用价值的包装方法，也没有把私有方法误变成跨
    Repository 的通用工具接口。
23. Repository 是否没有定义数据库实体与领域实体的转换方法，转换是否全部位于
    对应的 `XxxEntry`。
24. 同一个 Entry 的不同转换情况是否使用 `from(...)` 或 `to(...)` 重载，而没有
    增加同义方法名、布尔开关或可空序列化参数。
25. 序列化和专用字段转换是否位于与 `entries` 平级的 `codec` 中，Entry 是否只调用
    Codec 的静态方法，并且没有把序列化方式暴露为转换参数。
