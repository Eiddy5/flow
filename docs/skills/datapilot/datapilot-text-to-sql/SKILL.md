---
name: datapilot-text-to-sql
description: |
  text to sql, natural language, NL2SQL, 自然语言, SQL 生成, SqlGenerator,
  datapilot, question, query from text. Use when the user asks to "把自然语言转 SQL",
  "natural language to SQL", "NL2SQL", "ask question to database",
  "generate SQL from prompt", or works with files under
  src/main/java/org/dataPilot/textToSql/**. Scope: SqlGenerator + ParsedQuery
  契约与 schema context 准备；不涉及 SQL 执行（执行交给 datapilot-crud 或 jOOQ）。
argument-hint: "[natural-language-question]"
allowed-tools: Read Grep Glob
paths: "src/main/java/org/dataPilot/textToSql/**"
version: 0.1.0
---

# DataPilot Text-to-SQL

将自然语言问题转换为可执行 SQL，依赖 `SqlGenerator` 与 schema 元数据。

## When to Use

- "把这句话转成 SQL：本月销售额前十的客户"
- "natural language to SQL over the orders collection"
- "用自然语言查询 datapilot collection"
- 编辑路径 `src/main/java/org/dataPilot/textToSql/**` 下的文件时

## API Surface

**Package**: `org.dataPilot.textToSql`

### SqlGenerator (FQN: `org.dataPilot.textToSql.SqlGenerator`)

```java
public class SqlGenerator {
    // 构造：必须传入目标 collection 元数据
    public SqlGenerator(Collection<?> collection);

    // 生成 SQL（最简形态）
    public String generateSQL(String naturalLanguageQuery);

    // 生成 SQL 并附加上下文（多表 / 复杂场景）
    public String generateSQL(String naturalLanguageQuery,
                              Map<String, Object> context);

    // 结构化解析，返回 SQL + 字段 + 聚合 + 参数
    public ParsedQuery parse(String naturalLanguageQuery);
}
```

### ParsedQuery

```java
public class ParsedQuery {
    public String sql;                       // 生成的 SQL 文本
    public List<String> usedFields;          // 引用到的字段名
    public List<String> usedAggregations;    // 聚合函数列表，例如 COUNT/SUM
    public Map<String, Object> parameters;   // 绑定参数（防注入）
}
```

### Schema Context Input (`SchemaContext`)

调用方必须提供 collection 元数据，否则生成器无法识别字段与主键：

```java
public interface SchemaContext {
    Collection<?> getCollection(String name);  // 目标 collection
    List<FieldOption> getFields();             // 全部字段定义（名称 + 类型 + 注释）
    String getPrimaryKey();                    // 主键字段名
}
```

**消费方准备 schema context 的步骤**：

1. 通过 `DataSourceService.engine` 拿到 `Collection<?>`（参考 `datapilot-crud` skill）。
2. 从 `CollectionOption.fields` 收集 `FieldOption`，确保 `name / type / comment` 完整。
3. 主键来自 `CollectionOption.primaryKey`。
4. 多表场景：在 `Map<String, Object> context` 中放入 `relations`、`joinHints`、`dialect` 等键，供 generator 消费。

## Workflow

1. 选择并注入业务侧 `SqlGenerator` 实现
2. 准备 `Collection<?>` + 字段元数据（必填）
3. 调用 `parse(...)` 拿 `ParsedQuery`，校验 `usedFields` 是否全部存在于 schema
6. 将 `sql + parameters` 透传给执行层（见 Integration 段）

## Examples

### 例 1：单表 NL → SQL

**Input question**：`"查询所有状态为 active 的用户邮箱"`

**Schema context**（collection `users`）：

```text
fields: [id BIGINT pk, email VARCHAR, status VARCHAR, created_at TIMESTAMP]
primaryKey: id
```

```java
Collection<?> users = service.engine.getCollection("users");
SqlGenerator gen = new SqlGenerator(users);
ParsedQuery pq = gen.parse("查询所有状态为 active 的用户邮箱");
// pq.sql        = "SELECT email FROM users WHERE status = :status"
// pq.usedFields = [email, status]
// pq.parameters = {status=active}
```

### 例 2：多表 join NL → SQL

**Input question**：`"列出最近 7 天每个客户的订单数及客户名称"`

**Schema context**：

```text
collections: customers(id, name), orders(id, customer_id, created_at, amount)
relations:   orders.customer_id -> customers.id
```

```java
Map<String, Object> ctx = Map.of(
    "relations", List.of("orders.customer_id=customers.id"),
    "dialect",   "postgres"
);
SqlGenerator gen = new SqlGenerator(orders);
String sql = gen.generateSQL(
    "列出最近 7 天每个客户的订单数及客户名称", ctx);
// SELECT c.name, COUNT(o.id) AS order_count
// FROM orders o JOIN customers c ON o.customer_id = c.id
// WHERE o.created_at >= NOW() - INTERVAL '7 days'
// GROUP BY c.name
```

### 例 3：过滤 + 聚合

**Input question**：`"统计每个分类下价格大于 100 的商品平均价"`

**Schema context**（collection `products`）：`id, name, category, price`

```java
ParsedQuery pq = new SqlGenerator(products)
    .parse("统计每个分类下价格大于 100 的商品平均价");
// pq.sql              = "SELECT category, AVG(price) AS avg_price
//                       FROM products WHERE price > :minPrice
//                       GROUP BY category"
// pq.usedAggregations = [AVG]
// pq.parameters       = {minPrice=100}
```

## Integration

### 回灌 datapilot-crud（list with raw SQL）

```java
ParsedQuery pq = new SqlGenerator(collection).parse(question);
ListRequest req = new ListRequest();
req.rawSql     = pq.sql;
req.parameters = pq.parameters;
List<?> rows = service.list(ctx, req);   // 走 datapilot-crud list 通道
```

### jOOQ 直接执行

```java
ParsedQuery pq = new SqlGenerator(collection).parse(question);
Result<?> result = dsl.fetch(pq.sql,
    pq.parameters.values().toArray());
```

> 任何执行前必须先 **校验 + 参数化**，详见 Pitfalls。

## Checklist

- [ ] `SqlGenerator` 实现已注入
- [ ] `Collection<?>` 与 `FieldOption` 列表完整传入
- [ ] 主键、字段类型、注释均已提供（提升生成质量）
- [ ] 多表场景已在 `context` 内声明 `relations` 与 `dialect`
- [ ] 对 `ParsedQuery.usedFields` 做白名单校验
- [ ] 使用 `parameters` 绑定，禁止字符串拼接
- [ ] 执行前对 SQL 做语法 / 权限 / 行级安全校验

## Pitfalls

| Mistake | Fix |
|---|---|
| Schema 不全（缺字段/类型/注释） | 补齐 `FieldOption`，注释直接影响 NL 理解准确率 |
| 未声明 SQL dialect | 在 `context` 中显式传 `dialect=postgres/mysql/...` |
| 多表查询未给 relations | `context.relations` 必须列出 join 键，否则会误生成笛卡尔积 |
| 直接拼接 `pq.sql` 执行 | 使用 `pq.parameters` 绑定参数，杜绝注入 |
| 信任生成结果直接落库 | 先 `EXPLAIN` 或 dry-run，校验字段白名单与权限 |
| 问题歧义（"最近"/"热门"） | 在 prompt 内补充时间窗口、排序口径，或在 `context` 中给默认值 |
| 按行号引用代码 | 使用 `SqlGenerator` / `ParsedQuery` 等符号名 |
