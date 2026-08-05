---
name: datapilot-annotation
description: |
  cloud-datapilot Micronaut 注解全集：@Table/@Dbindex 集合定义、@StringField/@NumberField 等全部字段注解、
  @BelongsToField/@HasManyField 等关联注解、@DbIndexField 关联 ES 策略、@JsonbIndex JSONB 索引、
  @DBIndexFallbackScan 兜底扫描、@DSPlugin/@DSEventListener 插件事件、
  @EnableGlobalSearch/@Parent/@ApiSchema/@PgFunctionSchema 等注解用法。
  Use when the user asks to "注解", "annotation", "@Table", "@Field", "Micronaut annotation",
  "@DSPlugin", "@DSEventListener", "@DBIndexFallbackScan", "@JsonbIndex", "注解配置 datapilot".
argument-hint: "[annotationName]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.java"
version: 1.1.0
---

# Micronaut 注解全集

面向 SDK 消费者，覆盖 cloud-datapilot 全部 Micronaut 注解的配置方式与使用示例。

## 1. 集合定义注解

### 1.1 @Table

将 Bean 类声明为 DataPilot 集合。

```java
@Table(
    engineKey = "default",          // 引擎标识 (默认 "*")
    dataSourceKey = "salesDb",      // 数据源
    name = "orders",                // 集合名 (表名)
    title = "订单",
    indexName = "orders_index",     // ES 索引名
    primaryKeys = {"id"},           // 主键 (默认 ["id"])
    camelCase = true,               // 是否 camelCase 转换
    fieldComparison = true,         // 是否开启字段比较 (变更追踪)
    openLog = false                 // 是否开启操作日志
)
public class Order extends MicModel {
    // ...
}
```

### 1.2 @Dbindex

集合级索引配置，同时控制启动时 ES 索引自动检查。

```java
@Table(...)
@Dbindex(
    versionControlField = "version",        // 乐观锁版本字段名（文档级）
    mappingVersion = "v1",                  // 索引映射版本号（启动校验用）
    sharding = false,                       // 是否启用分片索引
    shardingType = false                    // 分片类型开关（历史字段）
)
public class Order extends MicModel {
    // ...
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `versionControlField` | `String` | `""` | 文档级乐观锁字段名，用于索引更新冲突控制 |
| `mappingVersion` | `String` | `""` | 索引映射版本号，启动时与 ES `_meta` 比对；为空则不校验 |
| `sharding` | `boolean` | `false` | 是否启用分片索引能力 |
| `shardingType` | `boolean` | `false` | 分片类型开关（历史兼容字段） |

> **启动行为**：标注 `@Dbindex` 的集合在系统重启时自动检查 ES 索引。
> - 索引不存在 → 自动创建（含主索引、分片索引、桶索引）
> - 索引存在但 `mappingVersion` 不匹配 → **warn 告警**（不阻断启动），需手动调用 `/dataPilot/rebuildIndex` 重建
> - 索引存在且版本匹配 → 跳过

---

## 2. 字段注解

### 2.1 基础类型字段

```java
public class Order extends MicModel {

    @StringField(length = 100)
    private String orderNo;

    @NumberField(dataType = NumberDataType.BIG_DECIMAL)
    private BigDecimal amount;

    @NumberField(dataType = NumberDataType.INTEGER)
    private Integer quantity;

    @BooleanField
    private Boolean isVip;
}
```

### 2.2 JSON 字段

```java
@JsonField
private JsonObject metadata;

@JsonBField
private JsonObject attributes;

@JsonBField(
    jsonbIndexStrategy = JsonbIndexStrategy.LEGACY,
    jsonbIndexStrategies = {
        @JsonbIndex(path = "customer.code", strategy = JsonbIndexStrategy.BTREE_PATH),
        @JsonbIndex(path = "items.status", strategy = JsonbIndexStrategy.GIN_ARRAY_CONTAINS)
    }
)
private JsonObject searchablePayload;

@ArrayField(dataType = DataType.INTEGER)
private List<Integer> tagIds;

@ArrayJsonField
private List<JsonObject> lineItems;
```

### 2.3 日期时间字段

```java
@DateField(format = DateFieldFormat.DAY)
private Long orderDate;

@DateField(format = DateFieldFormat.MONTH)
private Long reportMonth;

@TimeField
private Long timestamp;
```

### 2.4 系统字段

```java
@CreateAtField
private Long createdAt;

@UpdateAtField
private Long updatedAt;

@CreateByField
private JsonObject createdBy;

@UpdateByField
private JsonObject updatedBy;

@CompanyIdField
private String companyId;

@VersionField
private Integer version;

@StateField
private Integer state;

@CtxField(ctxFieldName = "deptId")
private String deptId;
```

### 2.5 计算字段

```java
@CalculateField(
    expression = "amount * quantity",
    isVirtual = true
)
private BigDecimal totalPrice;

@CalculateField(
    expression = "discount > 0 ? amount * (1 - discount) : amount",
    isVirtual = true
)
private BigDecimal actualAmount;
```

### 2.6 枚举字段

```java
@EnumField(enumClass = OrderStatus.class)
private Integer statusCode;      // 存 ordinal

@EnumField(enumClass = OrderStatus.class, isNumber = false)
private String statusText;       // 存字符串
```

### 2.7 快照字段

```java
@SnapshotField(
    include = {"status", "amount", "updatedAt"},
    exclude = {"internalNote"}
)
private JsonObject snapshotData;
```

---

## 3. 关联注解

```java
// 多对一 (外键在本表)
@BelongsToField(
    target = "customers",
    targetKey = "id",
    foreignKey = "customerId",
    cached = true,
    snapshot = false,
    onDel = AssociationOnDelType.SET_NULL
)
private Customer customer;

// 一对一 (外键在对方表)
@HasOneField(
    target = "profiles",
    sourceKey = "id",
    foreignKey = "userId",
    onDel = AssociationOnDelType.CASCADE
)
private Profile profile;

// 一对多 (外键在对方表)
@HasManyField(
    target = "orders",
    sourceKey = "id",
    foreignKey = "customerId",
    onDel = AssociationOnDelType.CASCADE
)
private List<Order> orders;

// 多对多 (桥接表)
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
```

---

## 4. ES 索引注解

```java
// 字段级索引配置
@DbIndexField(
    nested = true,
    keywordFields = {"id", "customerId"},
    assIndexFields = {"categoryId"},
    analyzerType = AnalyzerType.IK_SMART,
    subAnalyzerType = {AnalyzerType.PINYIN},
    copyTo = true,
    copyToTarget = "fullText"
)
private String productName;

// BelongsTo 关联的 ES 嵌入 + lookup 策略
@BelongsToField(target = "customers", targetKey = "id", foreignKey = "customerId")
@DbIndexField(
    associationEsMode = AssociationEsMode.HYBRID,
    assSearchFields = {"name", "code"},
    assEmbedFields = {"id", "name"},
    assMaxLookupIds = 5000,
    assKeywordSearch = true
)
private Customer customer;

// 忽略某字段
@DbIndexIgnore
private String internalNote;
```

`associationEsMode` 可选 `AUTO / EMBEDDED / LOOKUP / HYBRID / DISABLED`。lookup 当前只支持单跳 BelongsTo，目标 collection 必须启用 ES。`assMaxLookupDepth` 目前只是预留参数，planner 尚未读取。

### 4.1 DBIndex 兜底扫描注解

```java
@Table(name = "orders", dataSourceKey = "sales")
@Dbindex
@DBIndexFallbackScan(
    enabled = true,
    strategy = DBIndexFallbackScanStrategy.AUTO,
    intervalSeconds = 300
)
public class Order extends MicModel {
    @UpdateAtField
    private Long updatedAt;
}
```

该注解只声明单表开关、策略和默认间隔；模块总开关、AUTO 上下限、overlap 和 safety delay 在 `datapilot.es.fallback-scan.*` 中配置。完整说明见 `datapilot-index-fallback-scan` skill。

---

## 5. 插件与事件注解

```java
// 声明引擎插件
@DSPlugin(engineKey = "default")
public class MyPlugin implements EnginePlugin<Context<User>, User> {
    @Override
    public Set<String> getScope() { return Set.of("*"); }

    @Override
    public void beforeLoad(DataSourceEngine<Context<User>, User> engine) { }

    @Override
    public void load(DataSourceEngine<Context<User>, User> engine) { }
}

// 声明事件监听器
@DSEventListener(engineKey = "default")
public class MyEventListener {

    @OnDbEvent(DbEventType.AfterCreate)
    public void onCreated(EngineContext<?, ?> ctx, Model model) { }

    @OnDbEvent(DbEventType.BeforeDelete)
    public void onDeleting(EngineContext<?, ?> ctx, Model model) { }
}

// 多事件声明 (方法级)
@DbEvent(type = DbEventType.AfterUpdate, collections = {"orders", "customers"})
@DbEvent(type = DbEventType.AfterDelete, collections = {"orders"})
public void handleChanges(DbEvent<Model> event) { }
```

---

## 6. 全局搜索与数据变更注解

```java
// 注册全局搜索源
@EnableGlobalSearch
public class MySearchSource implements GlobalSearchSource<Session<User>, User> {
    @Override public String getIndexName() { return "my_index"; }
    @Override public SourceType getSourceType() { return new SourceType("my_type", "我的数据"); }
    @Override public Query buildQuery(Session<User> ctx, JsonObject params) { ... }
    @Override public QueryCondition buildQueryCondition(Session<User> ctx, JsonObject params) { ... }
    @Override public Object transformResult(Session<User> ctx, SearchResult<JsonModel> result, JsonObject params) { ... }
}

// 数据变更拦截器
@DSDataChangeRecipientInterceptor(
    engineKey = "default",
    collections = {"sensitive_data"}
)
public class MyInterceptor implements DataChangeRecipientInterceptor {
    @Override public boolean supports(DbCollection<?> collection, String topic, String instanceId) { ... }
    @Override public RecipientDecision intercept(DataChangeDispatchContext context) { ... }
    @Override public int order() { return 10; }
}
```

---

## 7. Schema API 与 PgREST 注解

```java
// 将 Controller 暴露为 API 集合
@ApiSchema(name = "userApi", title = "用户API", dataSourceKey = "external.api")
@Controller("/api/users")
public class UserApiController {

    @ApiSchemaMetadata(
        operationName = "query",
        operationType = "QUERY",
        httpMethod = "POST",
        path = "/search"
    )
    @Post("/search")
    public ListResult<UserDTO> search(@Body SearchRequest req) {
        // 实现...
    }
}

// PostgreSQL 函数集合
@PgFunctionSchema(
    functionName = "calculate_discount",
    functionSignature = "calculate_discount(customer_id int, amount numeric)",
    dataSourceKey = "salesDb",
    collectionName = "discount_calculator",
    description = "计算折扣"
)
public class DiscountCalculator { }
```

---

## 8. 父表继承

```java
// 声明父表实体
@Table(name = "entities", dataSourceKey = "default")
public class BaseEntity extends MicModel { ... }

// 子表继承 (单表继承)
@Parent(
    table = BaseEntity.class,
    dataSourceKey = "default",
    typeProperty = "entityType",
    typeValue = "order"
)
public class Order extends BaseEntity { ... }
```

---

## 9. WebSocket 监听器

```java
@WebsocketListener(
    condition = "status == 'active'",
    type = SubscribeType.FILTER
)
public void onDataChange(DataChangeMessage msg) { }
```

---

## 10. 注解处理流程

```
1. 应用启动 → Micronaut Bean 扫描
2. @Table/@ApiSchema/@PgFunctionSchema 注解的 Bean 被收集
3. DataSourceEngine.start() 遍历所有注册的 Bean
4. 根据字段注解自动生成 FieldOption
5. 根据 @Dbindex/@DbIndexField 生成 ES 索引配置
6. DataPilotIndex.start() 调用 ensureIndexMappingsOnStartup()
7. 对 @Dbindex 注解的集合执行启动索引检查（缺失创建/版本告警）
8. @DSPlugin 注册插件
9. @DSEventListener 注册事件监听器
10. @EnableGlobalSearch 注册全局搜索源
```
