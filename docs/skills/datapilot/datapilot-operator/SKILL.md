---
name: datapilot-operator
description: |
  cloud-datapilot 请求运算符：NumberOperator 数字自增/自减、ArrayOperator PG数组操作、
  JsonArrayOperator JSONB数组操作、SetNullOperator 设NULL、HTTP ops DSL。
  Use when the user asks to "运算符", "operator", "自增", "数组追加", "setNull",
  "NumberOperator", "ArrayOperator", "JsonArrayOperator", "字段操作".
argument-hint: "[operatorType] [field]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.java"
version: 1.0.0
---

# 请求运算符 (Operators)

在 Update/Save 请求中通过 `operators` 字段对字段值执行特殊操作（自增、数组追加、设 NULL 等）。

## 1. NumberOperator (数字自增/自减)

### 1.1 用法

```java
// Java — 自增: amount = amount + 10
UpdateRequest req = new UpdateRequest();
req.filter = QueryCondition.Eq("id", 10086);
req.operators = Map.of("amount", NumberOperator.Add(10));

// 自减: stock = stock - 5
req.operators = Map.of("stock", NumberOperator.Sub(5));

// 链式 API (DataUpdater)
engine.buildUpdater(ctx, "salesDb", "orders")
      .eq("id", 10086)
      .set("retryCount", NumberOperator.Add(1))
      .set("stock", NumberOperator.Sub(5))
      .update(Map.of("status", "PROCESSING"));
```

### 1.2 HTTP JSON 格式

```json
{
  "filter": { "fields": { "id": { "$eq": 10086 } } },
  "values": { "retryCount": 0 },
  "operators": {
    "retryCount": { "type": "add", "number": 1 }
  }
}
```

### 1.3 HTTP 原始 DSL

```json
{
  "filter": { "fields": { "id": { "$eq": 10086 } } },
  "ops": {
    "amount": "$add:10",
    "stock": "$sub:5"
  }
}
```

---

## 2. SetNullOperator (设为 NULL)

### 2.1 用法

```java
// Java
req.operators = Map.of("deletedAt", SetNullOperator.Of());

engine.buildUpdater(ctx, "salesDb", "orders")
      .eq("id", 10086)
      .set("deletedAt", SetNullOperator.Of())
      .update(Map.of());
```

### 2.2 HTTP JSON

```json
{
  "filter": { "fields": { "id": { "$eq": 10086 } } },
  "operators": {
    "deletedAt": { "type": "setNull" }
  }
}
```

### 2.3 HTTP 原始 DSL

```json
{
  "ops": {
    "deletedAt": "$setNull"
  }
}
```

---

## 3. ArrayOperator (PostgreSQL 原生数组操作)

### 3.1 操作类型

| 操作 | 常量 | 说明 |
|------|------|------|
| 追加单个元素 | `ADD_ITEM` | `array_append(col, value)` |
| 追加单个元素（仅缺失时） | `ADD_ITEM_IF_MISSING` | `array_position(...) is null` 时追加 |
| 追加多个元素 | `ADD_ITEMS` | `array_cat(col, ARRAY[...])` |
| 移除指定索引 | `REMOVE_INDEX` | 按索引移除 |
| 移除指定值 | `REMOVE_VALUE` | 按值移除 |
| 清空 | `CLEAR` | 重置为空数组 |

### 3.2 Java 用法

```java
import org.dataPilot.db.search.filter.operater.ArrayOperator;

Map<String, AbstractOperator> ops = Map.of(
    "tags", ArrayOperator.addItem("newTag"),              // 追加一个
    "tags", ArrayOperator.addItemIfMissing("newTag"),     // 不存在时追加
    "tags", ArrayOperator.addItems(List.of("A", "B")),    // 追加多个
    "tags", ArrayOperator.removeIndex(2),                 // 移除索引 2
    "tags", ArrayOperator.removeValue("oldTag"),           // 移除值
    "tags", ArrayOperator.clear()                         // 清空
);

UpdateRequest req = new UpdateRequest();
req.operators = ops;

// DataUpdater 链式
engine.buildUpdater(ctx, "salesDb", "posts")
      .eq("id", 10086)
      .set("tags", ArrayOperator.addItem("important"))
      .update(Map.of());
```

### 3.3 HTTP JSON

```json
{
  "filter": { "fields": { "id": { "$eq": 10086 } } },
  "operators": {
    "tags": { "type": "addItemIfMissing", "item": "important" }
  }
}
```

---

## 4. JsonArrayOperator (JSONB 数组操作)

### 4.1 操作类型

| 操作 | 常量 | 说明 |
|------|------|------|
| 追加元素 | `ADD_ITEM` | 追加一个 JSON 元素 |
| 追加多个 | `ADD_ITEMS` | 追加多个 JSON 元素 |
| 移除索引 | `REMOVE_INDEX` | 按索引移除 |
| 替换索引 | `REPLACE_INDEX` | 按索引替换 |
| 按条件删除 | `REMOVE_WHERE` | 按 key-value 匹配删除 |
| 清空 | `CLEAR` | 重置为空数组 |

### 4.2 Java 用法

```java
import org.dataPilot.db.search.filter.operater.JsonArrayOperator;

Map<String, AbstractOperator> ops = Map.of(
    "items", JsonArrayOperator.addItem(Map.of("name", "newItem", "qty", 1)),
    "items", JsonArrayOperator.addItems(List.of(
        Map.of("name", "A"), Map.of("name", "B")
    )),
    "items", JsonArrayOperator.removeIndex(3),
    "items", JsonArrayOperator.replaceIndex(0, Map.of("name", "updated")),
    "items", JsonArrayOperator.removeWhere("status", "deleted"),
    "items", JsonArrayOperator.clear()
);

// DataUpdater 链式
engine.buildUpdater(ctx, "salesDb", "orders")
      .eq("id", 10086)
      .set("lineItems", JsonArrayOperator.addItem(
          Map.of("productId", 42, "quantity", 2, "price", 99.00)))
      .update(Map.of());
```

### 4.3 HTTP JSON

```json
{
  "filter": { "fields": { "id": { "$eq": 10086 } } },
  "operators": {
    "lineItems": {
      "type": "addItem",
      "item": { "productId": 42, "quantity": 2, "price": 99.00 }
    }
  }
}
```

### 4.4 按条件删除

```json
{
  "operators": {
    "lineItems": {
      "type": "removeWhere",
      "key": "productId",
      "value": 42
    }
  }
}
```

---

## 5. batchSave 逐行运算符

```java
// 批量 upsert 时，每行使用不同的运算符
BatchSaveRequest req = new BatchSaveRequest();
req.batchValues = List.of(
    Map.of("id", 10086, "amount", 100),
    Map.of("id", 10087, "amount", 200)
);
req.perRecordOperators = List.of(
    Map.of("amount", NumberOperator.Add(5)),    // 第 1 行: +5
    Map.of("amount", NumberOperator.Sub(10))    // 第 2 行: -10
);

// 或者使用 DataUpdater
engine.buildUpdater(ctx, "salesDb", "orders")
      .perRecordOperators(List.of(
          Map.of("amount", NumberOperator.Add(5)),
          Map.of("amount", NumberOperator.Sub(10))
      ))
      .batchSave(batchValues);
```

---

## 6. 运算符组合示例

```java
// 同时使用多个运算符
UpdateRequest req = new UpdateRequest();
req.filter = QueryCondition.Eq("id", 10086);
req.values = Map.of("status", "PROCESSED");
req.operators = Map.of(
    "retryCount", NumberOperator.Add(1),               // 重试次数 +1
    "tags", ArrayOperator.addItem("processed"),         // 追加标签
    "logEntries", JsonArrayOperator.addItem(            // 追加日志 JSON
        Map.of("action", "process", "time", System.currentTimeMillis())
    )
);

// HTTP 形式
{
  "filter": { "fields": { "id": { "$eq": 10086 } } },
  "values": { "status": "PROCESSED" },
  "operators": {
    "retryCount": { "type": "add", "number": 1 },
    "tags": { "type": "addItem", "item": "processed" },
    "logEntries": {
      "type": "addItem",
      "item": { "action": "process", "time": 1711234567890 }
    }
  }
}
```

---

## 7. 运算符类型枚举汇总

```java
// NumberOperator 内部类型
NumberOperatorType: ADD, SUB

// ArrayOperator 内部类型
ArrayOperatorType: ADD_ITEM, ADD_ITEMS, REMOVE_INDEX, REMOVE_VALUE, CLEAR

// JsonArrayOperator 内部类型
JsonArrayOperatorType: ADD_ITEM, ADD_ITEMS, REMOVE_INDEX, REPLACE_INDEX, REMOVE_WHERE, CLEAR
```
