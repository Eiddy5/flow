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
        └── postgres/
            ├── FlowPostgresRepository.java
            └── entries/
                ├── FlowEntry.java
                └── FlowTaskEntry.java
```

其他业务模块使用相同结构：

```text
infrastructure/repositories/executions/postgres/entries/
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
  -> XxxEntry.fromDomain(domain)
  -> buildInsertMap() / buildUpdateMap()
  -> JOOQ
  -> Database
```

查询时执行反向转换：

```text
Database
  -> JOOQ fetchInto(XxxEntry.class)
  -> XxxEntry.toDomain()
  -> Domain
```

不得把 Entry 从 Repository 返回给 Service、Handler 或 Controller，也不得在 Entry
中实现 Flow 发布、Execution 推进、TaskRun 完成等领域行为。

## 6. 转换和扩展方法放在 Entry

生成代码不能手工修改。以下方法应放在对应的 `XxxEntry` 中：

- `fromDomain(...)`、`of(...)` 等 Domain 到 Entry 的转换。
- `toDomain()`、`toXxx()` 等 Entry 到 Domain 的转换。
- JSON、枚举、时间或数据库专属类型的转换。
- 同一张表特有的持久化字段组合方法。
- 只服务于该 Repository 的读取辅助方法。

Entry 和专用 Codec 中的 JSON/JSONB 转换必须同时遵守
[`json.md`](json.md)。一般字段统一使用 PAAS JSON；ADR 0026 定义的
`FlowTaskEntry` 插件 properties 例外只能调用集中 `JacksonMapper` 的公开转换
方法，Entry 仍不得直接使用 Jackson `ObjectMapper` 或注册 Module。

例如，`FlowEntry.fromDomain(...)` 把完整 `Flow` 状态转换为生成对象字段，
`FlowEntry.toDomain()` 再使用 `Flow.rehydrate(...)` 恢复同一领域事实。转换
必须覆盖重建所需的全部字段，不能为了简化映射构造只有部分状态的领域对象。

`toDomain` 必须使用领域对象已经确认的静态 `rehydrate(...)` 入口。持久化恢复
不是普通业务创建，不得调用 `create(...)`，也不得通过 Factory、反射或直接修改
私有字段绕过领域约束。如果领域对象尚无必要的重建入口，应先补充领域持久化
契约。

项目自有 Java 类型的时间点统一为 Unix timestamp 毫秒值 `long/Long`，完整规则
见 [`project-development.md`](project-development.md)。PostgreSQL
`timestamptz` 对应的 `OffsetDateTime` 只允许出现在生成代码和 Entry/数据库 Adapter
转换边界；Entry 写入时使用 `Instant.ofEpochMilli(...)` 转换，重建时使用
`toInstant().toEpochMilli()`，不得把日期时间对象返回给 Core。

Entry 可以复用生成父类已有的 `toMap()`、`table()`、`buildInsertMap()` 和
`buildUpdateMap()`，不得重复实现同名通用能力。只有生成能力无法满足已确认的
数据库语义时才允许新增专用方法。

## 7. 完整对象插入

完整对象首次入库时：

1. 领域对象转换为 Entry。
2. 调用 Entry 继承的 `buildInsertMap()`。
3. 将结果整体传给 JOOQ。

正确示例：

```java
ExecutionEntry entry = ExecutionEntry.fromDomain(execution);

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

## 8. 更新

更新前必须先判断这是“字段级更新”还是“完整对象更新”。

### 字段级更新

只修改一个或少量明确字段时，直接使用 JOOQ 的字段级
`set(TABLE.FIELD, value)`：

```java
dsl.update(FLOWS)
    .set(FLOWS.SOURCE, source)
    .set(FLOWS.UPDATED_AT, updatedAt)
    .set(FLOWS.LOCK_VERSION, nextLockVersion)
    .where(FLOWS.COMPANY_ID.eq(companyId))
    .and(FLOWS.ID.eq(flowId))
    .and(FLOWS.DRAFT.isTrue())
    .and(FLOWS.LOCK_VERSION.eq(expectedLockVersion))
    .execute();
```

字段级更新必须：

- 只包含本次命令明确允许修改的字段。
- 使用生成的 Table Field，不手写数据库字段名。
- 带上完整业务身份、租户和并发条件。
- 需要清空字段时显式调用 `.set(TABLE.FIELD, null)`。

调用 `entry.setState(...)` 只会修改内存中的 Entry，并不会执行数据库更新；真正的
字段级更新必须通过 JOOQ Update 的 `.set(TABLE.FIELD, value)` 完成。

### 完整对象更新

需要将一个完整领域对象当前状态写回数据库时，先转换为 Entry，再使用
`buildUpdateMap()`：

```java
ExecutionEntry entry = ExecutionEntry.fromDomain(execution);

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
ExecutionEntry entry = ExecutionEntry.fromDomain(execution);

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

不带 `Into` 的方法保留 JOOQ Record，Repository 可以在查询后执行自己的映射和
组合逻辑，也可以使用接收映射函数的重载，把自定义转换集中在 Entry：

```java
ExecutionEntry entry = dsl.selectFrom(EXECUTIONS)
    .where(EXECUTIONS.COMPANY_ID.eq(companyId))
    .and(EXECUTIONS.ID.eq(executionId))
    .fetchOne(ExecutionEntry::fromRecord);

List<TaskRunEntry> taskRuns = dsl.selectFrom(TASK_RUNS)
    .where(TASK_RUNS.EXECUTION_ID.eq(executionId))
    .orderBy(TASK_RUNS.CREATED_AT)
    .fetch(TaskRunEntry::fromRecord);
```

以下情况应使用不带 `Into` 的方法和显式转换：

- 查询包含多表 Join、聚合、计算字段或同名字段，需要自行决定组合语义。
- 数据库类型与对象字段之间存在 JSON、枚举、时间等专用转换。
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
ExecutionEntry entry = dsl.selectFrom(EXECUTIONS)
    .where(EXECUTIONS.COMPANY_ID.eq(companyId))
    .and(EXECUTIONS.ID.eq(executionId))
    .fetchOneInto(ExecutionEntry.class);
```

如果查询字段名称、别名、类型或转换逻辑与 Entry 不完全对应，应改用
`fetch(...)`、`fetchOne(...)` 或它们接收映射函数的重载，显式完成转换，不能依赖
未验证的自动映射。

### 映射后重建领域对象

无论使用自定义 Record 映射还是 `Into` 直接映射，Repository 都必须在返回前调用
Entry 的领域转换方法：

```java
return entry == null
    ? Optional.empty()
    : Optional.of(entry.toDomain(/* 关联数据 */));
```

聚合包含子记录时，由 Repository 读取所需的多个 Entry，再统一调用领域重建入口。
例如 Execution 与有序 TaskRun 应完整加载后再重建 Execution，不能把数据库
Entry 列表暴露给 Core 调用方。

## 11. 事务、租户与并发条件

- Repository 使用调用链传入的 `DSLContext`，不能自行创建新事务。
- 查询、更新和删除必须带上 companyId 等租户边界。
- 全对象 Map 只负责构造待写字段，不负责生成 `where` 条件。
- 更新必须显式指定主键、业务身份和需要的 lockVersion/revision 条件。
- `buildUpdateMap()` 自动排除主键，不代表更新语句可以省略主键条件。
- 更新条数不符合预期时，应按业务协议处理不存在或并发冲突，不能静默忽略。

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
- 禁止在没有 `where`、租户或并发条件的情况下执行 update/delete。

## 13. 开发与审查清单

新增或修改 JOOQ 数据库 Adapter（包括 Repository）时逐项检查：

1. 是否使用 `org.flow.gen.flow` 下的当前生成类。
2. 是否在具体数据库 Adapter 实现的 `entries` 子包建立了 `XxxEntry`。
3. Entry 是否继承正确的 `XxxObject`。
4. Domain 与 Entry 的双向转换是否集中在 Entry。
5. Core 是否完全不知道 JOOQ 生成类型和 Entry。
6. 完整插入是否使用 `buildInsertMap()`。
7. 字段级更新是否使用 JOOQ 字段级 `set(...)`。
8. 完整对象更新是否使用 `buildUpdateMap()`。
9. 查询方法是否根据结果基数正确选择了 `fetch` 或 `fetchOne`。
10. 使用 `Into` 时，查询字段名称、别名、类型和目标对象可写属性是否对应。
11. 是否正确处理 `null`、数据库默认值和生成字段。
12. 查询、更新和删除是否包含租户、主键及并发条件。
13. 是否复用了传入的 `DSLContext` 和已有事务。
14. 是否为转换、Map 内容、查询重建和保存行为补充了对应测试。
15. JSON/JSONB 转换是否遵守 `json.md` 并统一使用 PAAS JSON。
