---
name: datapilot-filter
description: |
  构建 QueryCondition 过滤表达式并 translate 到 jooq / sql / mongo / elasticsearch / cqengine / expression
  六种 parser 多方言输出，包含字段级 KEYWORD 搜索和 JSONB 索引策略感知。Use when the user asks to
  "build filter", "where clause", "query condition", "field keyword search", "JSONB index strategy",
  "translate filter to mongo/es/sql", or 过滤 条件. Scope: org.dataPilot.db.search.filter 下的 QueryCondition
  与 QueryParserType，不涉及 engine CRUD 本身。
argument-hint: "[filter-shape]"
allowed-tools: Read Grep Glob
paths: "src/main/java/org/dataPilot/db/search/filter/**"
version: 0.2.0
---

# DataPilot Filter: QueryCondition 与多方言 Parser

<!-- 一句话目的：把结构化过滤条件写成一次、翻译为六种存储方言 -->

## When to Use

- "怎么构造 AND/OR 嵌套的 where 条件"
- "同一份 filter 怎么给 Mongo / ES / jOOQ 用"
- "QueryCondition 支持 BETWEEN / IN / LIKE 吗"
- 编辑路径 `src/main/java/org/dataPilot/db/search/filter/**` 下的文件时

## Core Types

- 条件模型：`org.dataPilot.db.search.filter.QueryCondition`
- 字段表达式：`org.dataPilot.db.search.filter.FieldExpression`
- 运算符枚举：`org.dataPilot.db.search.filter.FilterOperator`
- 解析器注册：`org.dataPilot.db.search.filter.parser.QueryParserType`
- 解析器基类：`org.dataPilot.db.search.filter.parser.AbstractQueryParser<T>`

## QueryCondition Builder API

### 工厂方法

```java
// 静态入口
public static QueryCondition Empty();
public static QueryCondition empty();
public static FieldExpression field(String name);
public static FieldExpression field(TableField<?, ?> field);
public static QueryCondition Filter(String field, Object value, FilterOperator operator);
public static QueryCondition Filter(TableField<?, ?> field, Object value, FilterOperator operator);
public static QueryCondition And(List<QueryCondition> and);
public static QueryCondition And(QueryCondition... and);
public static QueryCondition Or(List<QueryCondition> or);
public static QueryCondition Or(QueryCondition... or);
```

### FieldExpression 比较与匹配

```java
// 值比较
public QueryCondition keyword(Object value);
public QueryCondition eq(Object value);
public QueryCondition ne(Object value);
public QueryCondition gt(Object value);
public QueryCondition gte(Object value);
public QueryCondition lt(Object value);
public QueryCondition lte(Object value);

// 字段到字段比较
public QueryCondition eqField(String otherField);
public QueryCondition eqField(TableField<?, ?> otherField);
public QueryCondition neField(String otherField);
public QueryCondition neField(TableField<?, ?> otherField);
public QueryCondition gtField(String otherField);
public QueryCondition gtField(TableField<?, ?> otherField);
public QueryCondition gteField(String otherField);
public QueryCondition gteField(TableField<?, ?> otherField);
public QueryCondition ltField(String otherField);
public QueryCondition ltField(TableField<?, ?> otherField);
public QueryCondition lteField(String otherField);
public QueryCondition lteField(TableField<?, ?> otherField);

// 字符串匹配
public QueryCondition like(Object value);
public QueryCondition notLike(Object value);
public QueryCondition likeStart(Object value);
public QueryCondition likeEnd(Object value);
public QueryCondition notLikeStart(Object value);
public QueryCondition notLikeEnd(Object value);

// 集合成员
public QueryCondition in(Collection<?> values);
public QueryCondition notIn(Collection<?> values);
public QueryCondition in(Object... values);
public QueryCondition notIn(Object... values);

// 区间与存在性
public QueryCondition between(Object start, Object end);
public QueryCondition notBetween(Object start, Object end);
public QueryCondition isNull();
public QueryCondition isNotNull();
public QueryCondition exists(String path);

// 位运算，返回 ComputedExpression，可继续追加比较
public ComputedExpression bitAnd(Object value);
public ComputedExpression bitAndField(String otherField);
public ComputedExpression bitAndField(TableField<?, ?> otherField);
public ComputedExpression bitOr(Object value);
public ComputedExpression bitOrField(String otherField);
public ComputedExpression bitOrField(TableField<?, ?> otherField);
public ComputedExpression bitXor(Object value);
public ComputedExpression bitXorField(String otherField);
public ComputedExpression bitXorField(TableField<?, ?> otherField);
```

### QueryCondition 逻辑组合

```java
// 在已有条件上追加字段
public FieldExpression and(String fieldName);
public FieldExpression and(TableField<?, ?> fieldName);
public FieldExpression or(String fieldName);
public FieldExpression or(TableField<?, ?> fieldName);
public FieldExpression addField(String name);
public FieldExpression addField(TableField<?, ?> field);

// 多条件组合器
public QueryCondition and(List<QueryCondition> and);
public QueryCondition and(QueryCondition... conditions);
public QueryCondition or(List<QueryCondition> or);
public QueryCondition or(QueryCondition... conditions);

// 嵌套 block：or() / and() 开块，end() 收块
public QueryCondition or();
public QueryCondition and();
public QueryCondition end();

// 主键快捷方式
public void setPkValue(Object pkValue);
public void setPkValues(List<Object> pkValues);
public void setPkMap(Map<String, Object> pkMap);
public void setPkMaps(List<Map<String, Object>> pkMaps);
```

### 内省与拷贝

```java
public Map<String, Map<FilterOperator, Object>> getFields();
public List<QueryCondition> getAnd();
public List<QueryCondition> getOr();
public List<QueryExpression> getExpressions();
public boolean isEmpty();
public boolean hasPk();
public boolean containOperator(FilterOperator operator);
public QueryCondition deepCopy();
```

### FilterOperator 枚举

`EQ NE GT GTE LT LTE IN NOT_IN LIKE NOT_LIKE LIKE_START NOT_LIKE_START LIKE_END
NOT_LIKE_END BETWEEN NOT_BETWEEN IS_NULL IS_NOT_NULL EXISTS KEYWORD` 等，供
`Filter(field, value, operator)` 直接使用。

## Multi-Dialect Parsers

所有 parser 实现 `QueryParserStrategy<T>`，核心签名：

```java
public T parse(DbCollection<?> collection, QueryCondition request);
```

| Parser | FQN | 输出类型 |
|---|---|---|
| JooqParser | `org.dataPilot.db.search.filter.parser.JooqParser` | `org.jooq.Condition` |
| SQLParser | `org.dataPilot.db.search.filter.parser.SQLParser` | `String`（原生 SQL） |
| MongoParser | `org.dataPilot.db.search.filter.parser.MongoParser` | `org.bson.conversions.Bson` |
| ElasticSearchParser | `org.dataPilot.db.search.filter.parser.ElasticSearchParser` | `co.elastic.clients.elasticsearch._types.query_dsl.Query` |
| CQEngineParser | `org.dataPilot.db.search.filter.parser.CQEngineParser` | `com.googlecode.cqengine.query.Query<?>` |
| ExpressionParser | `org.dataPilot.db.search.filter.parser.ExpressionParser` | `String`（Aviator 表达式） |

注册表 `QueryParserType` 提供无参单例：

```java
QueryParserStrategy<org.jooq.Condition> JOOQ          = QueryParserType.JOOQ;
QueryParserStrategy<String>              SQL           = QueryParserType.SQL;
QueryParserStrategy<org.bson.conversions.Bson> MONGO   = QueryParserType.MONGO;
QueryParserStrategy<com.googlecode.cqengine.query.Query<?>> CQENGINE = QueryParserType.CQENGINE;
QueryParserStrategy<String>              EXPRESSION    = QueryParserType.EXPRESSION;
QueryParserStrategy<co.elastic.clients.elasticsearch._types.query_dsl.Query> ES = QueryParserType.ELASTICSEARCH;
```

### 字段级 KEYWORD 与全局 KEYWORD

```java
// 全局搜索：使用 search_text / search_keyword
QueryCondition.field("keyword").keyword("science fiction");

// 单字段搜索：使用 name 文本字段和 name.keyword 精确字段
QueryCondition.field("name").keyword("science fiction");
```

字段级 KEYWORD 会组合 exact、prefix、match phrase 和文本匹配，不再强制落到全局 `search_text/search_keyword`。`FilterContext.hasKeyword()` 按 `KEYWORD` operator 检测，因此任意字段上的 keyword 条件都能触发动态 `useEs(filter -> filter.hasKeyword())`。

### JSONB 索引策略感知

JooqParser 会读取 `JsonBFieldOption` 的全局或路径级 `JsonbIndexStrategy`：

- `LEGACY` 保持兼容对象/数组路径 SQL。
- `BTREE_PATH` 生成标量 `-> / ->>` 路径比较。
- `GIN_OBJECT_CONTAINS` 和 `GIN_ARRAY_CONTAINS` 尽可能生成 `@>` containment。

```java
QueryCondition condition = QueryCondition.field("payload.items.status")
    .in(List.of("open", "hold"));
```

若 `items.status` 配置为 `GIN_ARRAY_CONTAINS`，上述条件会生成可匹配对应 GIN containment 索引的 SQL。策略配置见 `datapilot-field` skill。

## Workflow

1. 确认目标 collection 的字段类型、关联元数据和 JSONB 索引策略
2. 用 `QueryCondition.field(...)` 构造入口条件，按需用 `and/or` 组合
3. 若要下发给 engine：`engine.buildQuery(collection).filter(condition)`
4. 若要自己翻译到具体方言：`QueryParserType.XXX.parse(collection, condition)`
5. 对超出目标方言能力的算子（见 Pitfalls），改写 condition 或拆分执行

## Example 1 — 构造一个复杂条件

```java
// 业务：查找活跃且 (年龄 18~60 且邮箱 gmail) 或 VIP 的用户，排除被软删除
QueryCondition cond = QueryCondition.field("status").eq("ACTIVE")
    .and(QueryCondition.field("deletedAt").isNull())
    .and(
        QueryCondition.Or(
            QueryCondition.And(
                QueryCondition.field("age").between(18, 60),
                QueryCondition.field("email").likeEnd("@gmail.com")
            ),
            QueryCondition.field("tags").in("VIP", "PREMIUM")
        )
    );
```

## Example 2 — 同一 condition 翻译到六种方言

```java
DbCollection<?> users = engine.collection("users");

// jOOQ Condition，可拼进 DSLContext
org.jooq.Condition jq   = QueryParserType.JOOQ.parse(users, cond);

// 原生 SQL 片段，直接拼 WHERE
String sql              = QueryParserType.SQL.parse(users, cond);

// Mongo Bson filter，喂给 MongoCollection.find
org.bson.conversions.Bson mongo = QueryParserType.MONGO.parse(users, cond);

// Elasticsearch Query DSL
co.elastic.clients.elasticsearch._types.query_dsl.Query es =
    QueryParserType.ELASTICSEARCH.parse(users, cond);

// CQEngine 内存查询
com.googlecode.cqengine.query.Query<?> cq =
    QueryParserType.CQENGINE.parse(users, cond);

// Aviator 表达式字符串
String expr             = QueryParserType.EXPRESSION.parse(users, cond);
```

典型产物示意（格式随版本变化，仅用于对比结构）：

```text
SQL         : status = 'ACTIVE' AND deletedAt IS NULL AND ((age BETWEEN 18 AND 60 AND email LIKE '%@gmail.com') OR tags IN ('VIP','PREMIUM'))
Mongo       : { $and: [ {status:'ACTIVE'}, {deletedAt:null}, { $or: [ {$and:[{age:{$gte:18,$lte:60}},{email:{$regex:'@gmail.com$'}}]}, {tags:{$in:['VIP','PREMIUM']}} ] } ] }
ES          : bool{ must:[ term(status,ACTIVE), bool{ must_not: exists(deletedAt)}, bool{ should:[ bool{ must:[ range(age,18,60), wildcard(email,*@gmail.com) ]}, terms(tags,[VIP,PREMIUM]) ]} ]}
CQEngine    : and(equal(status,ACTIVE), isNull(deletedAt), or(and(between(age,18,60), endsWith(email,@gmail.com)), in(tags,VIP,PREMIUM)))
Expression  : status=='ACTIVE' && deletedAt==nil && ((age>=18 && age<=60 && string.endsWith(email,'@gmail.com')) || include(['VIP','PREMIUM'],tags))
```

## Example 3 — 字段到字段比较 + 位运算

```java
// 订单表：paidAmount >= totalAmount 且权限位包含 READ(1) | WRITE(2)
QueryCondition cond = QueryCondition.field("paidAmount").gteField("totalAmount")
    .and(QueryCondition.field("permissions").bitAnd(3).eq(3));
```

## Example 4 — 主键快捷 + EXISTS

```java
// 按主键批量查，并要求 profile.address 存在
QueryCondition cond = QueryCondition.Empty();
cond.setPkValues(List.of(101L, 102L, 103L));
cond.and(QueryCondition.field("profile").exists("address"));
```

## Example 5 — 在 Engine 中使用 vs 手工翻译

```java
// 推荐：交给 engine，内部会根据 collection 的存储类型自动选 parser
engine.buildQuery(users)
      .filter(cond)
      .list();

// 仅在需要原生客户端时手工翻译
String rawSql = QueryParserType.SQL.parse(users, cond);
jdbcTemplate.query("SELECT * FROM users WHERE " + rawSql, rowMapper);
```

## Checklist

- [ ] 入口一律 `QueryCondition.field(...)` 或 `QueryCondition.Filter(...)`
- [ ] AND/OR 嵌套使用 `And(...)`, `Or(...)` 工厂或 `or()/end()` 块
- [ ] 目标存储能力已核对（见 Common Mistakes）
- [ ] engine 可用时优先 `buildQuery().filter()`，而非手工 `parse`
- [ ] 对空过滤使用 `QueryCondition.Empty()` 而非 `null`

## Common Mistakes

| Mistake | Fix |
|---|---|
| 传 `null` 当空过滤 | 使用 `QueryCondition.Empty()`，`isEmpty()` 判断 |
| 在 ES / Mongo 上用 `likeStart` 期望跟 SQL 完全一致 | ES 走 wildcard/regex，大小写与分词策略不同，必要时改 `keyword` 子字段 |
| 用 `eq(null)` 表达空值判断 | 使用 `isNull()` / `isNotNull()`，各 parser 才能映射到对应方言 |
| 把 jOOQ `TableField` 与字符串字段混用 | 同一 condition 内保持一致；engine 不走 jOOQ 的数据源上 `TableField` 无效 |
| 期望 CQEngine 支持全部 `LIKE` 变体 | CQEngine 仅支持前缀/后缀/包含匹配，复杂正则应退回 SQL/Mongo |
| 认为 `ExpressionParser` 输出可直接喂 SQL | 其输出是 Aviator 表达式，面向内存求值，不是 SQL |
| 字段名含点号被误当作嵌套路径 | Mongo/ES 视 `a.b` 为嵌套；SQL/jOOQ 需用 `TableField` 或手动转义 |
| 位运算后忘记补比较 | `bitAnd(x)` 返回 `ComputedExpression`，需再 `.eq(y)` 才是 `QueryCondition` |
