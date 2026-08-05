---
name: datapilot-global-search
description: |
  Cross-collection full-text search via DataPilot global search, 全局搜索, 关键字
  full text, alias mapping, searchGlobal. Use when the user asks to "全局搜索",
  "跨集合搜索", "keyword search across indices", "register search alias",
  "addSearchAlias", or works with `/dataPilot/searchGlobal` /
  `/dataPilot/addSearchAlias`. Scope: HTTP + embedded engine entry of
  GlobalSearcherRequest / GlobalSearchResult / AliasMappingRequest only;
  single-collection query belongs to datapilot-crud.
argument-hint: "[keyword] [--indices ...] [--alias field:alias]"
allowed-tools: Read Grep Glob
paths: "src/main/java/org/dataPilot/**"
version: 0.1.0
---

# DataPilot Global Search Skill

跨集合（multi-index）关键字检索与字段别名注册的统一入口。

## When to Use

- "在所有集合里搜 xxx"、"跨表全文搜索"、"global keyword search"
- "注册一个搜索别名，把字段 X 映射成 Y"、"addSearchAlias"
- 需要分页、过滤、排序、function_score 打分的多索引检索
- 编辑 `src/main/java/org/dataPilot/repository/request/GlobalSearcherRequest.java`
  或 `org/dataPilot/dbindex/common/GlobalSearchResult.java` 周边代码时

## HTTP Contract

### 1. POST `/dataPilot/searchGlobal`

**Request body** — `GlobalSearcherRequest`

| Field              | Type                    | 说明                                  |
| ------------------ | ----------------------- | ------------------------------------- |
| `keyword`          | `String`                | 关键字（全文匹配）                    |
| `filter`           | `QueryCondition`        | 附加结构化过滤                        |
| `indices`          | `List<String>`          | 参与搜索的索引/集合名；空=全部        |
| `pageNumber`       | `Integer`               | 页码，0-based                         |
| `pageSize`         | `Integer`               | 每页条数                              |
| `offset`           | `Integer`               | 绝对偏移（与 pageNumber 互斥）        |
| `params`           | `JsonObject`            | 透传给底层引擎的扩展参数              |
| `groupBy`          | `List<String>`          | 聚合字段                              |
| `bucketPageSize`   | `Integer`               | 桶分页大小                            |
| `bucketPageNumber` | `Integer`               | 桶分页页码                            |
| `bucketOffset`     | `Integer`               | 桶绝对偏移                            |
| `sorts`            | `QuerySorts`            | 排序                                  |
| `scoringConfig`    | `DocumentScoringConfig` | ES function_score 打分配置            |

**Response** — `GlobalSearchResult<Object>`

| Field        | Type                   | 说明                          |
| ------------ | ---------------------- | ----------------------------- |
| `totalCount` | `Long`                 | 全部索引命中总数              |
| `pageNumber` | `Integer`              | 回显页码                      |
| `pageSize`   | `Integer`              | 回显每页大小                  |
| `items`      | `List<ItemWrapper<T>>` | 多索引模式：每索引一个 wrap   |
| `data`       | `Object`               | 单索引模式：直接结果集        |

`ItemWrapper<T>`

| Field              | Type     | 说明                |
| ------------------ | -------- | ------------------- |
| `total`            | `long`   | 该索引命中数        |
| `index`            | `String` | 索引/集合名         |
| `sourceType_`      | `String` | 实体类型            |
| `sourceTypeLabel_` | `String` | 类型显示名          |
| `data`             | `Object` | 该索引的记录列表    |

### 2. POST `/dataPilot/addSearchAlias`

**Request body** — `AliasMappingRequest`

| Field        | Type     | 说明                  |
| ------------ | -------- | --------------------- |
| `field`      | `String` | 源字段名              |
| `alias`      | `String` | 检索时使用的别名      |
| `datasource` | `String` | 数据源 key            |
| `collection` | `String` | 集合名                |

**Response**: `JsonObject` —— `{ "ok": true, ... }`

## Examples

### Example 1 — 简单跨集合关键字搜索

```http
POST /dataPilot/searchGlobal
Content-Type: application/json

{
  "keyword": "张三",
  "pageNumber": 0,
  "pageSize": 20
}
```

返回 `GlobalSearchResult.items`，每个集合各占一个 `ItemWrapper`。

### Example 2 — 带过滤 + 分页 + 指定索引

```json
{
  "keyword": "invoice",
  "indices": ["sales_order", "customer"],
  "filter": {
    "op": "AND",
    "children": [
      { "field": "status", "op": "EQ", "value": "ACTIVE" },
      { "field": "amount", "op": "GTE", "value": 1000 }
    ]
  },
  "pageNumber": 2,
  "pageSize": 50,
  "sorts": { "items": [{ "field": "createdAt", "order": "DESC" }] }
}
```

### Example 3 — 先注册别名再查询

```http
POST /dataPilot/addSearchAlias
{
  "datasource": "main",
  "collection": "customer",
  "field": "cust_name_cn",
  "alias": "name"
}
```

```http
POST /dataPilot/searchGlobal
{ "keyword": "name:李雷", "indices": ["customer"] }
```

别名让前端可以用稳定语义名搜索而不依赖物理列名。

### Example 4 — Java 嵌入式调用 DataSourceEngine

```java
// 嵌入式：直接通过 engine 入口，跳过 HTTP
GlobalSearcherRequest req = new GlobalSearcherRequest();
req.keyword = "支付失败";
req.indices = List.of("order", "payment_log");
req.pageNumber = 0;
req.pageSize = 20;

GlobalSearchResult<Object> result = engine.searchGlobal(ctx, req);
for (GlobalSearchResult.ItemWrapper<Object> wrap : result.items) {
    log.info("index={} total={}", wrap.index, wrap.total);
}

// 注册别名
AliasMappingRequest alias = new AliasMappingRequest();
alias.datasource = "main";
alias.collection = "order";
alias.field = "order_no";
alias.alias = "no";
engine.addSearchAlias(ctx, alias);
```

## Ranking & Field Selection

- **底层**：`DataPilotIndex`（默认 Elasticsearch；可切换内存实现）
- **打分**：未传 `scoringConfig` 时走 ES 默认 BM25；传入则使用 `function_score`
  组合（衰减 / 字段权重 / 脚本），在 `DocumentScoringConfig` 中声明
- **检索字段**：所有标记为 `searchable=true` 的列 + 通过 `addSearchAlias`
  注册的别名字段；非 searchable 字段仅能用 `filter` 结构化匹配
- **集合选择**：`indices` 为空时按当前租户可见的全部集合检索；显式传入则
  仅命中列表中的集合
- **聚合**：`groupBy` 触发桶聚合，使用 `bucketPageSize/bucketPageNumber` 翻页

## Workflow

1. 根据本文件确认 `searchGlobal` 请求和返回契约
2. 若涉及别名，先 `/addSearchAlias` 再 `/searchGlobal`
5. 构造 `GlobalSearcherRequest`，调用并解析 `items` 或 `data`

## Checklist

- [ ] 已确认目标索引存在且未在重建中
- [ ] `pageNumber` 与 `offset` 不同时设置
- [ ] 涉及别名时已先调用 `/addSearchAlias`
- [ ] 多租户场景下 ctx 已携带正确租户标识
- [ ] 对 `items` vs `data` 的单/多索引返回形态做了分支处理

## Common Mistakes / Pitfalls

| Mistake                                       | Fix                                                                  |
| --------------------------------------------- | -------------------------------------------------------------------- |
| 别名注册后立即查询查不到                       | 别名仅登记到内存映射，重启后失效；需在启动钩子中重新注册             |
| 假设 `data` 字段始终存在                       | 多索引返回时使用 `items`，单索引才使用 `data`，先判空再取            |
| 索引重建期间搜索为空或不一致                   | 检查 `RebuildOption` 状态，重建未完成时暂停全局搜索或提示用户        |
| 漏传租户上下文 → 跨租户串数据                  | `ctx` 必须由网关注入，禁止用全局 admin ctx 直接调 `searchGlobal`     |
| 用行号或文件路径引用代码                       | 用类名/方法名描述：`GlobalSearcherRequest.keyword`、`engine.searchGlobal` |
| `keyword` 与 `filter` 混用时期望 OR 语义       | `filter` 永远是 AND 叠加在 `keyword` 之上，需要 OR 时改写 `QueryCondition` |
| 桶分页参数与记录分页参数混用                   | `bucket*` 仅在 `groupBy` 非空时生效，否则被忽略                      |
