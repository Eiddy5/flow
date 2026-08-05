---
name: datapilot-model
description: |
  cloud-datapilot 数据模型：Model/JsonModel/CamelJsonModel/MapModel 四种模型类型、
  ListResult 分页结果容器、ListMapResult 分组信息容器、序列化/反序列化、DataPilot 注解 DTO 字段映射、snake_case↔camelCase 自动转换、
  模型快照与变更追踪。Use when the user asks to "Model", "JsonModel", "CamelJsonModel",
  "MapModel", "ListResult", "ListMapResult", "groupInfo", "ModelObjectMapper", "DTO 映射", "模型序列化", "驼峰转换", "model snapshot".
argument-hint: "[modelType] [method]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.java"
version: 1.1.0
---

# 数据模型 (Model)

面向 SDK 消费者，覆盖 cloud-datapilot 全部数据模型类型、序列化方式、驼峰转换与变更追踪。

## 1. 模型类型

所有 CRUD 操作的载体都是 `Model` (继承 `Map<String, Object>`)。

| 类型 | 全限定类名 | 说明 | 适用场景 |
|------|----------|------|---------|
| `Model` | `org.dataPilot.data.Model` | 接口，继承 `Map<String, Object>` | 通用引用 |
| `JsonModel` | `org.dataPilot.data.JsonModel` | 基于 Micronaut `JsonObject` | 后端直接操作 JSON |
| `CamelJsonModel` | `org.dataPilot.data.CamelJsonModel` | 自动 snake_case ↔ camelCase 转换 | **推荐**: 后端 Java 使用 |
| `MapModel` | `org.dataPilot.data.MapModel` | 基于 `LinkedHashMap` | 轻量场景、测试 |

---

## 2. 构造方式

### 2.1 CamelJsonModel (推荐)

```java
import org.dataPilot.data.CamelJsonModel;

// 空模型
Model model = CamelJsonModel.emptyModel();

// 从 Map 构造 (key 使用 camelCase)
Model model = CamelJsonModel.Of(Map.of(
    "customerId", 42,
    "orderNo", "ORD-001",
    "amount", 299.00
));

// 从 JSON 字符串解析 (自动转换 key)
Model model = CamelJsonModel.parse("{\"customer_id\":42,\"order_no\":\"ORD-001\"}");
// → 内部 key: customerId, orderNo

// 从 JSON + DTO 类解析
OrderDTO dto = CamelJsonModel.parse(jsonStr, OrderDTO.class);
```

### 2.2 JsonModel

```java
import org.dataPilot.data.JsonModel;

// 从 JsonObject
Model model = JsonModel.Of(JsonObject.create()
    .put("id", 10086)
    .put("status", "active"));
```

### 2.3 MapModel

```java
import org.dataPilot.data.MapModel;

Model model = MapModel.Of(Map.of("id", 1, "name", "test"));
```

### 2.4 MicModel

```java
// Micronaut 注解方式: 业务 Bean 继承 MicModel
@Table(name = "orders", dataSourceKey = "salesDb")
public class Order extends MicModel {
    // MicModel 实现了 Model 接口
}
```

---

## 3. 类型安全取值

```java
// 基本类型
String name = model.getString("name");          // null 安全
Long id = model.getLong("id");
Integer count = model.getInteger("count");
Double score = model.getDouble("score");
BigDecimal amount = model.getDecimal("amount");
Boolean active = model.getBoolean("active");
Date date = model.getDate("createdAt");

// 泛型取值
<T> T value = model.get("key", SomeClass.class);
<T> T value = model.get("key", defaultValue);

// Map 接口
Object raw = model.get("key");
boolean hasKey = model.containsKey("key");
model.put("status", "active");
```

### 3.1 嵌套取值

```java
// JSON 对象
JsonObject json = model.getJsonObject("metadata");

// 对象列表
List<SomeDTO> list = model.getList("items", SomeDTO.class);

// Map
Map<String, Object> map = model.getMap("address");

// Map 列表
List<Map<String, Object>> maps = model.getMaps("tags");

// JsonObject 列表
List<JsonObject> jsonObjs = model.getJsonObjects("items");
```

### 3.2 关联数据取值

```java
// 关联对象 (BelongsTo / HasOne)
Model customer = model.getModel("customer");
String customerName = model.getString("customer.name");  // 路径访问

// 关联列表 (HasMany / BelongsToMany)
List<Model> items = model.getModelList("items");
```

---

## 4. 序列化与反序列化

```java
// → JSON
JsonObject json = model.asJson();        // Micronaut JsonObject
String jsonStr = model.toJson();         // JSON 字符串

// → POJO
OrderDTO dto = model.asObject(OrderDTO.class);

// CamelJsonModel 附加操作
((CamelJsonModel) model).append("log", logEntry);  // 追加记录
```

### 4.1 DataPilot 注解 DTO 字段映射

`JsonModel.asObject()` 和 `ModelsComposite.asObjects()` 会通过 `ModelObjectMapper` 归一化字段名。映射只作用于 `@Table` 类，或普通类中带 DataPilot `@Field`/元注解的字段、getter、setter；显式 `name` 优先：

```java
public class OrderDTO {
    @StringField(name = "order_no", length = 64)
    private String orderNo; // order_no -> orderNo

    @StringField(name = "buyer_code", length = 64)
    private String customerCode; // buyer_code -> customerCode
}

OrderDTO order = model.asObject(OrderDTO.class);
List<OrderDTO> orders = new ModelsComposite<>(models).asObjects(OrderDTO.class);
```

映射会检查字段、getter 和 setter，并包含父类属性。无注解的普通 POJO 不会自动改名；目标类本身实现 `Model` 时也不做归一化。如果响应中已经存在目标属性名，它不会被源字段覆盖。

---

## 5. 驼峰转换详解 (CamelJsonModel)

### 5.1 转换规则

```
Java camelCase  ←→  数据库 snake_case
────────────────────────────────────────
customerId      ↔   customer_id
orderNo         ↔   order_no
createdAt       ↔   created_at
isActive        ↔   is_active
```

### 5.2 HTTP 中的 useCamel

```bash
# 携带 ?useCamel=true 时，后端自动用 CamelJsonModel 处理
POST /dataPilot/salesDb/orders/list?useCamel=true
-d '{"filter":{"fields":{"customerId":{"$eq":42}}}}'
# 内部自动将 customerId → customer_id 再执行 SQL 查询
# 响应也自动转回 camelCase
```

### 5.3 Java 中创建 CamelJsonModel factory

```java
// 自定义 Model 类型转换
ModelFactory<CamelJsonModel> factory = CamelJsonModel.create(originalModel);
```

---

## 6. ListResult (分页结果容器)

### 6.1 结构

```java
public class ListResult<T> {
    public List<T> items;    // 当前页数据
    public long total;       // 总记录数 (分页查询时)

    // 判空
    public boolean isEmpty() { return items == null || items.isEmpty(); }
}
```

### 6.2 构造

```java
// 静态工厂
ListResult<Model> r1 = ListResult.Of();
ListResult<Model> r2 = ListResult.Of(items);
ListResult<Model> r3 = ListResult.Of(items, total);
ListResult<Model> r4 = ListResult.Of(singleItem);  // 自动包装为列表
```

### 6.3 HTTP 响应格式

```json
{
  "items": [
    { "id": 1, "name": "Alice" },
    { "id": 2, "name": "Bob" }
  ],
  "total": 137
}
```

### 6.4 使用示例

```java
ListResult<Model> result = query.list();

if (!result.isEmpty()) {
    for (Model item : result.items) {
        System.out.println(item.getString("name"));
    }
    System.out.println("共 " + result.total + " 条");
}
```

### 6.5 ListMapResult（分组结果）

```java
public class ListMapResult<T> {
    public String key;          // 稳定分组键；关联分组时通常是源外键
    public Object groupInfo;    // 普通字段值，或 BelongsTo 目标模型
    public ListResult<T> items; // 组内数据和 total
}
```

DB 摘要查询使用 `pageSize(0)` 时，`items.items` 为空、`items.total` 保留组内总量。空外键和悬空关联的 `groupInfo` 为 `null`，但分组及计数仍保留。

---

## 7. 模型快照与变更追踪

```java
// 拍快照 (记录当前状态)
Model snapshot = model.snapshot();

// 检查字段是否变更
boolean changed = model.changed("status");

// 获取原始值
Model origin = model.getOrigin();

// 是否更新过
boolean updated = model.isUpdated();
```

**使用场景**: AfterUpdate 事件中对比新旧值。

```java
@OnDbEvent(DbEventType.AfterUpdate)
public void onUpdate(EngineContext<?, ?> ctx, Model model) {
    Model origin = model.getOrigin();
    if (origin != null) {
        String oldStatus = origin.getString("status");
        String newStatus = model.getString("status");
        // 对比变更
    }
}
```

---

## 8. ModelFactory

在类型转换时使用：

```java
// 将一种 Model 转为另一种
ModelFactory<T> factory = sourceModel -> {
    // 从 sourceModel 构建目标类型
    return targetType.emptyModel(); // ...
};

Model target = sourceModel.create(factory);
```
