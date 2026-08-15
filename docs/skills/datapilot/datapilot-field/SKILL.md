---
name: datapilot-field
description: |
  cloud-datapilot 字段类型详解：25+ 字段类型配置、系统字段行为表、计算字段（Aviator/Bean）、
  枚举字段、快照字段、自定义表单字段、字段选项、ES/JSONB 索引策略、验证器集成、字段注册扩展。
  Use when the user asks to "字段类型", "添加字段", "field type", "JSONB index",
  "GIN_ARRAY_CONTAINS", "calculate field", "枚举字段", "自定义字段", "字段扩展", "field option".
argument-hint: "[fieldType] [option]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.java"
version: 1.1.0
---

# 字段类型详解

面向 SDK 消费者，覆盖 cloud-datapilot 全部 25+ 字段类型的配置、行为、与扩展方式。

## 1. 字段类型注册表

| 字段常量 (FieldType) | Java 类 | 存储类型 (DataType) | 说明 |
|---------------------|---------|-------------------|------|
| `String` | `StringField` | `STRING` | 字符串字段 |
| `Number` | `NumberField` | 由 `dataType` 决定 | 数字字段 |
| `Boolean` | `BooleanField` | `BOOLEAN` | 布尔字段 |
| `Json` | `JsonField` | `JSON_TEXT` | JSON 对象字段 |
| `JsonB` | `JsonBField` | `JSONB` | PG JSONB 字段 |
| `Array` | `ArrayField` | `ARRAY` | PG 原生数组 |
| `ArrayJson` | `ArrayJsonField` | `ARRAY_JSON_TEXT` | JSON 数组字段 |
| `Enum` | `EnumField` | `INTEGER`/`STRING` | 枚举字段 |
| `TimeUtil` | `TimeField` | `LONG` | 时间戳 (ms) |
| `Date` | `DateField` | `LONG` | 日期字段 (支持格式化截断) |
| `AutoId` | `AutoIdField` | `STRING` | 自动 UUID |
| `CreateAt` | `CreateAtField` | `LONG` | 自动创建时间 |
| `UpdateAt` | `UpdateAtField` | `LONG` | 自动更新时间 |
| `CreatedBy` | `CreateByField` | `JSON_TEXT` | 自动创建人 |
| `UpdatedBy` | `UpdateByField` | `JSON_TEXT` | 自动更新人 |
| `CompanyId` | `CompanyIdField` | `STRING` | 自动公司 ID |
| `Ctx` | `CtxField` | `STRING` | 自动上下文值 |
| `State` | `StateField` | `INTEGER` | 记录状态 (Open/Closed) |
| `Version` | `VersionField` | `INTEGER` | 乐观锁版本号 |
| `Calculate` | `CalculateField` | `VIRTUAL` | 计算字段 |
| `BelongsTo` | `BelongsToField` | — | 多对一关联 |
| `HasOne` | `HasOneField` | — | 一对一关联 |
| `HasMany` | `HasManyField` | — | 一对多关联 |
| `BelongsToMany` | `BelongsToManyField` | — | 多对多关联 |
| `CustomForm` | `CustomFormField` | — | 自定义表单字段 |
| `(default)` | `DefaultField` | 由 option 决定 | 默认兜底 |

---

## 2. 系统字段行为表

| 字段 | 生命周期时机 | 行为描述 |
|------|------------|---------|
| `AutoId` | BeforeCreate (值为 null 时) | 自动生成 UUID (`StringUtil.newId()`) |
| `CreateAt` | BeforeCreate | 自动设为 `System.currentTimeMillis()` |
| `UpdateAt` | BeforeSave (每次) | 自动设为 `System.currentTimeMillis()` |
| `CreatedBy` | BeforeCreate | 从 `context.getPassCtx()` 获取当前用户 JSON |
| `UpdatedBy` | BeforeUpdate | 从 `context.getPassCtx()` 获取当前用户 JSON |
| `CompanyId` | BeforeCreate | 从 `context.getPassCtx().getCompanyId()` 取值 |
| `Ctx` | BeforeCreate | 从 `context.getPassCtx()` 按 `ctxFieldName` 路径取值 |
| `Version` | 绑定 | 注册为乐观锁字段，初始值 0，更新时 +1 |
| `State` | BeforeCreate (值为 null) | 默认为 `RecordState.Open` |
| `Date` | BeforeCreate | 按 `DateFieldFormat` 截断时间戳 |

---

## 3. 字符串字段 (StringField)

```java
// Java API
FieldOption opt = new FieldOption();
opt.name = "orderNo";
opt.type = FieldType.String;
opt.title = "订单号";
opt.length = 50;
opt.description = "唯一订单编号";
opt.defaultValue = "";
opt.primaryKey = false;

// Micronaut 注解
@StringField(length = 50)
private String orderNo;
```

---

## 4. 数字字段 (NumberField)

```java
// Java API — 通过 DataType 控制精度
FieldOption opt = new FieldOption();
opt.name = "amount";
opt.type = FieldType.Number;
opt.dataType = DataType.BIG_DECIMAL;  // INTEGER, LONG, DOUBLE, BIG_DECIMAL 等

// Micronaut 注解
@NumberField(dataType = NumberDataType.BIG_DECIMAL)
private BigDecimal amount;

@NumberField(dataType = NumberDataType.INTEGER)
private Integer quantity;
```

NumberDataType 枚举: `INTEGER`, `LONG`, `FLOAT`, `DOUBLE`, `BIG_DECIMAL`, `SHORT`

---

## 5. JSON 字段

### 5.1 JsonField (JSON_TEXT)

```java
// Java API
FieldOption opt = new FieldOption();
opt.name = "metadata";
opt.type = FieldType.Json;

// Micronaut 注解
@JsonField
private JsonObject metadata;
```

### 5.2 JsonBField (PostgreSQL JSONB)

```java
// Java API
FieldOption opt = new FieldOption();
opt.name = "attributes";
opt.type = FieldType.JsonB;

// Micronaut 注解
@JsonBField
private JsonObject attributes;
```

### 5.3 JSONB 查询索引策略

`JsonbIndexStrategy` 决定 jOOQ parser 为 JSONB 路径生成哪种 PostgreSQL SQL：

| 策略 | SQL 形态 | 适用索引 |
|---|---|---|
| `LEGACY` | 兼容对象/数组路径，必要时使用 `jsonb_array_elements` | 未声明或历史查询 |
| `BTREE_PATH` | 标量 `-> / ->>` 路径比较 | 表达式 BTREE 索引 |
| `GIN_OBJECT_CONTAINS` | JSON 对象 `@>` containment | 对象 GIN 索引 |
| `GIN_ARRAY_CONTAINS` | JSON 数组 `@>` containment | 数组 GIN 索引 |

Java 配置支持全字段默认策略和路径覆盖：

```java
JsonBFieldOption payload = JsonBFieldOption.builder()
    .name("payload")
    .jsonbIndexStrategy(JsonbIndexStrategy.LEGACY)
    .jsonbIndexStrategies(Map.of(
        "customer.code", JsonbIndexStrategy.BTREE_PATH,
        "items.status", JsonbIndexStrategy.GIN_ARRAY_CONTAINS
    ))
    .build();
```

Micronaut：

```java
@JsonBField(
    jsonbIndexStrategy = JsonbIndexStrategy.LEGACY,
    jsonbIndexStrategies = {
        @JsonbIndex(path = "customer.code", strategy = JsonbIndexStrategy.BTREE_PATH),
        @JsonbIndex(path = "items.status", strategy = JsonbIndexStrategy.GIN_ARRAY_CONTAINS)
    }
)
private JsonObject payload;
```

路径键使用 JSONB 字段内部的点路径，不包含根字段名。策略只改变查询 SQL，数据库中的实际 BTREE/GIN 索引必须按同一结构创建，否则不会获得预期性能。

### 5.4 数组字段

```java
// PostgreSQL 原生数组
@ArrayField(dataType = DataType.INTEGER)
private List<Integer> tagIds;

// JSON 数组
@ArrayJsonField
private List<JsonObject> items;
```

---

## 6. 枚举字段 (EnumField)

### 6.1 数字枚举 (存 ordinal)

```java
// Java API
EnumOption enumOpt = new EnumOption();
enumOpt.name = "statusCode";
enumOpt.title = "订单状态";
enumOpt.values = new LinkedHashMap<>();
enumOpt.values.put("PENDING", "待处理");
enumOpt.values.put("APPROVED", "已通过");
enumOpt.values.put("REJECTED", "已拒绝");
enumOpt.isNumber = true;  // 存 0,1,2 而非字符串

// 读取时自动生成双字段:
// statusCode = 0          (原始存储值)
// statusCode_ = "待处理"  (显示文本)

// Micronaut 注解
@EnumField(enumClass = OrderStatus.class)
private Integer status;

// 或者关联 Java enum
enum OrderStatus {
    PENDING, APPROVED, REJECTED
}
```

### 6.2 字符串枚举 (存字符串值)

```java
EnumOption enumOpt = new EnumOption();
enumOpt.isNumber = false;  // 存字符串 "PENDING", "APPROVED" 等

@EnumField(enumClass = OrderStatus.class, isNumber = false)
private String status;
```

---

## 7. 日期时间字段 (DateField)

### 7.1 格式化截断

```java
// Java API
FieldOption opt = new FieldOption();
opt.name = "orderDate";
opt.type = FieldType.Date;

// 通过子类型控制截断精度
DateField dateField = new DateField();
// 内部: BeforeCreate 时按 DateFieldFormat 截断时间戳

// Micronaut 注解
@DateField(format = DateFieldFormat.DAY)       // 截断到天
@DateField(format = DateFieldFormat.MONTH)      // 截断到月
@DateField(format = DateFieldFormat.YEAR)       // 截断到年
@DateField(format = DateFieldFormat.HOUR)       // 截断到小时
@DateField(format = DateFieldFormat.MILLISECOND) // 不截断
private Long orderDate;
```

DateFieldFormat 枚举: `YEAR`, `MONTH`, `DAY`, `HOUR`, `MINUTE`, `SECOND`, `MILLISECOND`

### 7.2 TimeField (纯时间戳)

```java
@TimeField
private Long timestamp;  // 不做任何截断
```

---

## 8. 计算字段 (CalculateField)

### 8.1 模式一: Aviator 表达式

```java
// Java API
CalculateFieldOption calcOpt = new CalculateFieldOption();
calcOpt.name = "fullName";
calcOpt.expression = "firstName + ' ' + lastName";
calcOpt.isVirtual = true;  // 虚拟字段不持久化

// model 中的所有字段值自动成为变量，在 AfterFetch 时求值

// Micronaut 注解
@CalculateField(expression = "amount * quantity", isVirtual = true)
private BigDecimal totalPrice;

@CalculateField(expression = "discount > 0 ? amount * discount : amount")
private BigDecimal actualAmount;
```

### 8.2 模式二: Bean 注入

```java
// 实现 CalculateFunction 接口
@Component("myCalculator")
public class MyCalculator implements CalculateFunction<String> {
    @Override
    public String calculate(Model model) {
        String firstName = model.getString("firstName");
        String lastName = model.getString("lastName");
        return firstName + " " + lastName;
    }
}

// 配置中引用 Bean 名
CalculateFieldOption calcOpt = new CalculateFieldOption();
calcOpt.name = "fullName";
calcOpt.calculateBeanName = "myCalculator";
calcOpt.isVirtual = true;
```

---

## 9. 快照字段 (SnapshotField)

在 BeforeCreate 和 AfterFetch 时自动记录/加载字段快照：

```java
// Java API
SnapshotFieldOption snapshotOpt = new SnapshotFieldOption();
snapshotOpt.name = "snapshotData";
snapshotOpt.include = List.of("status", "amount", "updatedAt");
snapshotOpt.exclude = List.of("internalNote");

// BeforeCreate: 将 include 中的字段值快照保存到 snapshotData
// AfterFetch: 从 snapshotData 恢复字段值

// Micronaut 注解
@SnapshotField(include = {"status", "amount"}, exclude = {"internalNote"})
private JsonObject snapshotData;
```

---

## 10. 自定义表单字段 (CustomFormField)

模拟 HasMany 关联到自定义字段表 (集合名以 `custom` 结尾)：

```java
// Java API
CustomFormFieldOption customOpt = new CustomFormFieldOption();
customOpt.name = "customFields";
customOpt.target = "orders_custom";  // 目标集合 (自定义表单字段表)
// sourceKey 默认 "id"
// foreignKey 默认 "dataId"

// Micronaut 注解
@CustomFormField(target = "orders_custom")
private List<JsonObject> customFields;
```

内部逻辑：
- 保存时: 将 customFields 写入 `orders_custom` 集合 (每条记录关联本表 id)
- 读取时: 自动加载关联的 custom 数据

---

## 11. 字段选项 (FieldOption) 公共字段

```java
public class FieldOption {
    public DataType dataType;               // 存储类型
    public Boolean isVirtual;               // 是否虚拟字段 (不持久化)
    public String type;                     // FieldType 常量值
    public String name;                     // 字段名 (数据库列名)
    public String title;                    // 显示标题
    public String description;              // 描述
    public String comment;                  // 注释
    public Integer length;                  // 长度 (String 类型)
    public Boolean primaryKey;              // 是否主键
    public DbIndexOptions dbIndexOptions;   // ES 索引配置
    public Object defaultValue;             // 默认值
    public boolean buildIndex;              // 是否建立 ES 索引
    public List<String> targetIndexField;   // 索引目标字段
    public List<Validator> validators;      // 验证器列表
    public String ctxFieldName;             // CtxField 的取值路径
}
```

---

## 12. ES 索引配置 (DbIndexOptions)

每个字段可独立配置 ES 索引行为：

```java
// Java API
DbIndexOptions idxOpt = new DbIndexOptions();
idxOpt.nested = true;                         // ES nested 类型
idxOpt.keywordFields = List.of("raw", "exact"); // keyword 子字段
idxOpt.assIndexFields = List.of("categoryId");  // 关联索引字段
idxOpt.analyzerType = AnalyzerType.IK_SMART;    // 分词器
idxOpt.subAnalyzerType = List.of(AnalyzerType.PINYIN); // 子分词器
idxOpt.copyTo = true;                           // copy_to
idxOpt.copyToTarget = "fullText";               // copy_to 目标

// Micronaut 注解
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

// 忽略某字段不索引
@DbIndexIgnore
private String internalNote;
```

---

## 13. 验证器集成

在字段定义中配置验证器：

```java
FieldOption opt = new FieldOption();
opt.name = "email";
opt.type = FieldType.String;

// 必填
opt.validators.add(new RequiredValidator("email", "邮箱不能为空"));
// 长度限制
opt.validators.add(new LengthValidator("name", 1, 100));
// 正则
opt.validators.add(new RegexpValidator("code", "^[A-Z]{2}\\d{6}$"));
// 邮箱格式
opt.validators.add(new EmailValidator("email"));
```

---

## 14. 字段注册

### 14.1 注册自定义字段类型

```java
// 引擎启动时注册
DataSourceEngine<Context<User>, User> engine = ...;

// 注册自定义字段类型
engine.registerField("myCustomType", new MyCustomField());

// engine 内部: registerFieldTypes() 注册全部 25+ 内置类型
```

### 14.2 通过 FieldOption 添加字段到集合

```java
DbCollectionOption colOpt = new DbCollectionOption();
colOpt.name = "orders";

// 定义多个字段
FieldOption idField = new FieldOption();
idField.name = "id";
idField.type = FieldType.AutoId;
idField.primaryKey = true;

FieldOption nameField = new FieldOption();
nameField.name = "customerName";
nameField.type = FieldType.String;
nameField.length = 100;
nameField.title = "客户名称";

FieldOption amountField = new FieldOption();
amountField.name = "amount";
amountField.type = FieldType.Number;
amountField.dataType = DataType.BIG_DECIMAL;

colOpt.fields = List.of(idField, nameField, amountField);
```

---

## 15. 全部 DataType 枚举

```java
STRING, BOOLEAN, INTEGER, LONG, DOUBLE, FLOAT, SHORT, BIG_DECIMAL,
JSON_TEXT, JSON, JSONB, ARRAY_JSON_TEXT, ARRAY, TIME, DATE,
VIRTUAL, CLOB, EMPTY
```

## 16. 全部 DateFieldFormat 枚举

```java
YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, MILLISECOND
```
