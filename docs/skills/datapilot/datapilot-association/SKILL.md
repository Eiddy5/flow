---
name: datapilot-association
description: |
  cloud-datapilot 关联系统：BelongsTo/HasOne/HasMany/BelongsToMany 四种关联类型配置、
  级联删除行为（CASCADE/SET_NULL/DO_NOTHING/REJECTED）、关联快照模式、
  关联预加载与写入、BelongsTo 分组信息、ES EMBEDDED/LOOKUP/HYBRID 查询策略。Use when the user asks to
  "关联", "association", "BelongsTo", "groupInfo", "association lookup", "HYBRID",
  "HasMany", "BelongsToMany", "级联删除", "关联查询", "关联写入".
argument-hint: "[associationType] [target]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.java"
version: 1.1.0
---

# 关联系统

面向 SDK 消费者，覆盖 cloud-datapilot 全部四种关联类型的配置、查询、写入与删除行为。

## 1. 四种关联类型

| 类型 | 枚举值 | SQL 类比 | 外键位置 |
|------|-------|---------|---------|
| **BelongsTo** | `AssociationType.BelongsTo` | `orders.customer_id → customers.id` | 本表 |
| **HasOne** | `AssociationType.HasOne` | `users.id → profiles.user_id` | 对方表 |
| **HasMany** | `AssociationType.HasMany` | `customers.id → orders.customer_id` | 对方表 |
| **BelongsToMany** | `AssociationType.BelongsToMany` | `students ↔ student_courses ↔ courses` | 桥接表 |

## 2. 删除行为 (AssociationOnDelType)

| 行为 | 说明 |
|------|------|
| `CASCADE` | 级联删除 |
| `SET_NULL` | 设为 null |
| `DO_NOTHING` | 不做操作 |
| `REJECTED` | 拒绝删除 (抛出异常) |

---

## 3. BelongsTo (多对一)

外键在本表，指向父表。

### 3.1 Java API 配置

```java
BelongsToFieldOption option = new BelongsToFieldOption();
option.name = "customer";              // 关联字段名 (在 Model 中访问)
option.target = "customers";           // 目标集合名
option.targetKey = "id";               // 目标表主键 (默认 "id")
option.foreignKey = "customerId";      // 本表外键列
option.targetDataSource = "salesDb";   // 目标数据源 (默认同源)
option.cached = true;                  // 是否缓存关联数据
option.snapshot = false;               // 是否快照存储 (存 JSON 而非 JOIN)
option.onDel = AssociationOnDelType.SET_NULL;
```

### 3.2 Micronaut 注解

```java
public class Order extends MicModel {
    @BelongsToField(
        target = "customers",
        targetKey = "id",
        foreignKey = "customerId",
        cached = true,
        onDel = AssociationOnDelType.SET_NULL
    )
    private Customer customer;
}
```

### 3.3 查询时加载关联

```java
// Java
ListResult<Model> orders = engine.buildQuery(ctx, "salesDb", "orders")
    .findAssociations("customer")   // 预加载 customer 关联
    .list();

// 访问关联数据
Model order = orders.items.get(0);
Model customer = order.getModel("customer");  // 关联的数据
String name = order.getString("customer.name"); // 通过路径访问

// HTTP
curl -X POST '.../orders/list?useCamel=true' \
  -d '{
    "findAssociations": ["customer"],
    "fields": ["id", "amount", "customer.id", "customer.name"]
  }'
```

### 3.4 创建/更新时写入关联

```java
// Java — 在创建时同时写入关联对象
CreateRequest req = new CreateRequest();
req.updateAssociations = Set.of("customer");
req.values = Map.of(
    "amount", 299.00,
    "customer", Map.of(           // 关联对象
        "id", 42,
        "name", "Alice"
    )
);

// 或者只关联已有记录 (仅传外键)
req.values = Map.of(
    "amount", 299.00,
    "customerId", 42              // 直接设置外键
);
```

### 3.5 按 BelongsTo 获取分组信息

```java
List<ListMapResult<Model>> groups = engine
    .buildQuery(ctx, "salesDb", "orders")
    .groupBy("customer")
    .pageSize(0)
    .useEs(false)
    .listGroup(Model.class);
```

每组 `key` 是订单表的 `customerId`，`groupInfo` 是一次批量查询取得的完整 Customer 模型，`items.total` 是订单数量，`items.items` 为空。空外键和已失效外键仍保留计数，此时 `groupInfo=null`。DB 模式只支持一个普通字段或一个 BelongsTo 关联，不支持 HasMany/BelongsToMany 作为摘要分组对象。

---

## 4. HasOne (一对一)

外键在对方表。

### 4.1 Java API 配置

```java
HasOneFieldOption option = new HasOneFieldOption();
option.name = "profile";
option.sourceKey = "id";           // 本表主键
option.foreignKey = "userId";      // 对方表外键
option.target = "profiles";        // 目标集合
option.targetDataSource = "mainDb";
option.onDel = AssociationOnDelType.CASCADE;
```

### 4.2 Micronaut 注解

```java
public class User extends MicModel {
    @HasOneField(
        target = "profiles",
        sourceKey = "id",
        foreignKey = "userId",
        onDel = AssociationOnDelType.CASCADE
    )
    private Profile profile;
}
```

---

## 5. HasMany (一对多)

外键在对方表。

### 5.1 Java API 配置

```java
HasManyFieldOption option = new HasManyFieldOption();
option.name = "orders";
option.sourceKey = "id";           // 本表主键
option.foreignKey = "customerId";  // 对方表外键
option.target = "orders";          // 目标集合
option.onDel = AssociationOnDelType.CASCADE;
```

### 5.2 Micronaut 注解

```java
public class Customer extends MicModel {
    @HasManyField(
        target = "orders",
        sourceKey = "id",
        foreignKey = "customerId",
        onDel = AssociationOnDelType.CASCADE
    )
    private List<Order> orders;
}
```

### 5.3 查询时加载子集合

```java
// Java — 加载客户及其订单
ListResult<Model> customers = engine.buildQuery(ctx, "salesDb", "customers")
    .findAssociations("orders")
    .pageSize(20)
    .list();

// HTTP — 带子关联过滤
{
  "findAssociations": ["orders"],
  "withChildren": ["activeItems"]  // 指定加载子集合的子关联
}
```

---

## 6. BelongsToMany (多对多)

通过桥接表关联。

### 6.1 Java API 配置

```java
BelongsToManyFieldOption option = new BelongsToManyFieldOption();
option.name = "courses";
option.bridge = "studentCourses";      // 桥接表名
option.sourceKey = "id";               // 本表主键
option.sourceFk = "studentId";         // 桥接表 → 本表
option.targetFk = "courseId";          // 桥接表 → 目标表
option.targetKey = "id";               // 目标表主键
option.target = "courses";             // 目标集合
option.bridgeDataSource = "schoolDb";  // 桥接表数据源 (可不同)
option.targetDataSource = "schoolDb";  // 目标表数据源 (可不同)

// 数组字段模式：不要再设置 bridge / bridgeDataSource
BelongsToManyFieldOption arrayOption = new BelongsToManyFieldOption();
arrayOption.name = "courses";
arrayOption.sourceKey = "id";
arrayOption.sourceFk = "studentId";
arrayOption.targetFk = "courseId";
arrayOption.targetKey = "id";
arrayOption.target = "courses";
arrayOption.targetDataSource = "schoolDb";
arrayOption.bridgeArrayField = "courseIds"; // 源表数组字段，元素值等于 targetKey
```

### 6.2 Micronaut 注解

```java
public class Student extends MicModel {
    @BelongsToManyField(
        target = "courses",
        bridge = "studentCourses",
        sourceFk = "studentId",
        targetFk = "courseId",
        targetKey = "id",
        bridgeDataSource = "schoolDb",
        targetDataSource = "schoolDb"
    )
    private List<Course> courses;
}
```

数组字段模式下可改为：

```java
@BelongsToManyArrayField(
    target = "courses",
    sourceKey = "id",
    targetKey = "id",
    bridgeArrayField = "courseIds",
    sourceFk = "studentId",
    targetFk = "courseId",
    targetDataSource = "schoolDb"
)
private List<Course> courses;
```

### 6.3 查询示例

```java
// Java — 加载学生及其课程
Model student = engine.buildQuery(ctx, "schoolDb", "students")
    .filter(QueryCondition.Eq("id", 1))
    .findAssociations("courses")
    .single();

List<Model> courses = student.getList("courses", Model.class);

// HTTP
curl -X POST '.../students/get?useCamel=true' \
  -d '{
    "filter": { "fields": { "id": { "$eq": 1 } } },
    "findAssociations": ["courses"]
  }'
```

---

## 7. 关联 ES 存储与查询策略

`AssociationEsMode` 控制关联字段在源 collection 的 ES 查询方式：

| 模式 | 行为 |
|---|---|
| `AUTO` | 关联字段 `buildIndex=true` 时等价 `EMBEDDED`，否则 `DISABLED` |
| `EMBEDDED` | 将目标对象字段嵌入源索引，查询直接命中源文档 |
| `LOOKUP` | 先查询目标 collection 的 ES，得到目标 ID，再改写为源外键 terms 查询 |
| `HYBRID` | 同时保留嵌入字段和 lookup 能力 |
| `DISABLED` | 不为该关联提供 ES 关联查询 |

Java Builder：

```java
DbCollectionOption.builder()
    .belongsTo(field -> field
        .setName("customer")
        .setTarget("customers")
        .setTargetKey("id")
        .setForeignKey("customerId")
        .associationEsMode(AssociationEsMode.HYBRID)
        .assSearchFields("name", "code")
        .assEmbedFields("id", "name")
        .assMaxLookupIds(5000)
        .assKeywordSearch(true))
    .build();
```

Micronaut 注解：

```java
@BelongsToField(target = "customers", targetKey = "id", foreignKey = "customerId")
@DbIndexField(
    associationEsMode = AssociationEsMode.HYBRID,
    assSearchFields = {"name", "code"},
    assEmbedFields = {"id", "name"},
    assMaxLookupIds = 5000,
    assKeywordSearch = true
)
private Customer customer;
```

约束：

- 当前 lookup 规划只支持 BelongsTo，并要求目标 collection 已启用 ES 索引。
- `assSearchFields` 限制允许 lookup 的目标字段；为空表示不限制。
- `assEmbedFields` 只控制嵌入 payload，目标主键会自动加入。
- 当前 planner 只执行单跳 lookup。`assMaxLookupDepth` API 是预留字段，当前运行时不读取；`assMaxLookupIds` 未设置时默认上限为 10000。
- `assKeywordSearch=false` 只关闭该关联参与 keyword lookup，不影响显式字段过滤。

---

## 8. 关联快照模式

设置 `snapshot = true` 后，关联数据以 JSON 文本存储在数据库字段中，而非运行时 JOIN：

```java
BelongsToFieldOption option = new BelongsToFieldOption();
option.name = "customer";
option.snapshot = true;

// 存储时: 将 customer 序列化为 JSON 存入 customer 字段
// 读取时: 从 customer 字段直接反序列化 (无需 JOIN)
```

**适用场景**: 关联数据不常变化、需要避免 JOIN 开销。

**注意**: 快照模式下，关联数据不会实时反映源记录变更，需要手动刷新。

---

## 9. 关联预加载控制

### 9.1 查询参数

```java
FindRequest req = new FindRequest();

// 预加载的关联名
req.findAssociations = Set.of("customer", "items");

// 是否包含父表字段
req.withParent = true;

// 指定子关联 (如订单的 items 的 tags)
req.withChildren = List.of("items.tags");
```

`EngineConfiguration.useDirectAssociationLoadingExecutor` 默认为 `true`，关联树会在当前执行线程内完成加载。只有显式设为 `false` 且 `FindRequest.isAsync=true` 时才使用配置的异步 executor；调整前应确认事务和连接上下文允许跨线程使用。

### 9.2 关联路径解析

QueryCondition 中带点号的字段会自动提取关联名：

```java
// 条件: customer.name = "Alice"
QueryCondition.Eq("customer.name", "Alice")
// → 自动检测到 "customer" 是关联字段
// → getAssociations() 返回 Set.of("customer")
```

---

## 10. 关联写入控制

```java
// 创建时控制哪些关联被写入
CreateRequest req = new CreateRequest();
req.updateAssociations = Set.of("customer", "items");  // 白名单
req.defaultAssocUpdateEnabled = true;   // 自动更新全部关联

// 更新时同理
UpdateRequest req = new UpdateRequest();
req.updateAssociations = Set.of("items");
```

---

## 11. 关联验证

```java
// 在 DataSourceEngine.start() 中自动执行
if (validateAssociationsOnStartup) {
    associationValidator.validateAll();
}
```

验证内容:
1. **循环关联检测**: DFS 检测关联图循环
2. **深度检查**: 警告超过最大深度 (默认 5) 的关联链
3. **孤儿关联**: 预留检测 (未实现)

---

## 12. 常见陷阱

| 陷阱 | 正确做法 |
|------|---------|
| 关联未声明 | 在 FieldOption 或 Micronaut 注解中显式声明关联字段 |
| BelongsToMany 忘记桥接表 | 必须设置 `bridge`, `sourceFk`, `targetFk` |
| 跨数据源关联 | 设置 `targetDataSource` 和 `bridgeDataSource` |
| 删除未设 onDel | 根据业务选择 CASCADE/SET_NULL/REJECTED |
| 快照数据过期 | 快照模式下关联不自动刷新，需手动处理 |
| 循环关联 | 关联图深度 > 5 时启动会警告 |
| LOOKUP 目标未建索引 | 目标 collection 必须启用 ES，否则无法改写关联条件 |
| LOOKUP 返回 ID 太多 | 用 `assMaxLookupIds` 设置业务上限，避免超大 terms 查询 |

---

## 13. 全部枚举汇总

```java
// 关联类型
AssociationType: BelongsTo, HasOne, BelongsToMany, HasMany

// 删除行为
AssociationOnDelType: CASCADE, SET_NULL, DO_NOTHING, REJECTED

// ES 关联策略
AssociationEsMode: AUTO, EMBEDDED, LOOKUP, HYBRID, DISABLED
```
