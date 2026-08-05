---
name: datapilot-schema
description: |
  Introspect datasources, collections and fields in cloud-datapilot. Use when the
  user asks to "list datasources", "describe collection", "get table schema",
  "fetch collection options", "查询数据源", "查看表结构", "获取字段元数据",
  "updateCollection", "parse pgrest function", or works with files under
  `src/main/java/org/dataPilot/conf/option/**`, `src/main/java/org/dataPilot/schemaApi/**`,
  `src/main/java/org/dataPilot/manager/command/**`. Scope: metadata/schema introspection
  and collection DDL only; runtime CRUD belongs to datapilot-crud, search index to
  datapilot-rebuild-index.
argument-hint: "[datasource] [collection]"
allowed-tools: Read Grep Glob Bash
paths: "src/main/java/org/dataPilot/{conf/option,schemaApi,manager/command}/**"
version: 0.1.0
---

# DataPilot Schema Introspection

一句话目的：通过 HTTP 或 Java 嵌入方式获取 cloud-datapilot 中 datasource → collection → field 的完整元数据，并支持通过 `updateCollection` 修改表结构或通过 `/pgrest/parseFunction` 从 PostgreSQL 函数反推请求/响应 Schema。

## When to Use

- "List all datasources the engine has registered"
- "Give me the columns of `order.orders`"
- "What's the difference between `/options` and `/fullOptions`?"
- "I need to add a field to a collection via code"
- "Parse this pgrest function into a view collection"
- "列出某数据源的所有表"，"获取表的字段和索引"，"更新某张表的结构"
- 编辑 `src/main/java/org/dataPilot/conf/option/**`、`src/main/java/org/dataPilot/schemaApi/**`、`src/main/java/org/dataPilot/manager/command/**` 下的代码时

## HTTP Endpoints

所有端点均为 `POST`，返回 JSON。下面列出 Body / Response 契约。

### 1. `/dataSourceList`

列出引擎注册的所有数据源。

**Request body:** `{}`（无参数，仍需 POST 空对象）

**Response:** `List<DataSourceOption>`

```json
[
  { "key": "order", "name": "order", "title": "订单库", "type": "DB" },
  { "key": "pgrest", "name": "pgrest", "title": "PG 视图库", "type": "SCHEMA_API" }
]
```

### 2. `/{datasource}/collections`

列出某个数据源下的全部 collection（表/视图/Schema API）。

**Path:** `datasource` = `DataSourceOption.key`

**Request body:** `{}`

**Response:** `List<CollectionOption>`（各子类的并集：`DbCollectionOption`、`SqlCollectionOption`、`ApiCollectionOption`、`SchemaApiCollectionOption`、`PgrestViewCollectionOption`）

### 3. `/{datasource}/{collection}/options`

获取单个 collection 的元数据。**只返回该 collection 自身定义的字段**，不包含从父级/关联继承的字段。

**Request body:** `{}`

**Response:** `CollectionOption`

### 4. `/{datasource}/{collection}/fullOptions`

获取单个 collection 的**完整元数据**，包括从关联、继承关系中合并而来的字段；结果以 `Map<String, CollectionOption>` 形式返回，`key` 为 collection 名称，便于一次拿到主表与相关联的子表。

**Request body:** `{}`

**Response:** `Map<String, CollectionOption>`

### 5. `/updateCollection`

创建/更新/删除 collection 的字段、索引、约束。

**Request body:** `DataPilotCollectionCommand`

```json
{
  "dataSourceKey": "order",
  "collection": "orders",
  "subCommands": [
    { "kind": "CreateField", "field": { "name": "remark", "type": "VARCHAR", "length": 255, "comment": "备注" } },
    { "kind": "UpdateField", "field": { "name": "status", "type": "VARCHAR", "length": 32 } },
    { "kind": "RemoveField", "field": { "name": "legacy_flag" } }
  ]
}
```

**Response:** `JsonObject.Success()`（`{ "success": true }`）

### 6. `/pgrest/parseFunction`

解析 PostgreSQL 函数签名，自动生成 request/response Schema，得到一个可直接注册的 `PgrestViewCollectionOption`。

**Request body:** `PgrestViewCollectionOption`（通常只需填入 `dataSourceKey`、`name`（函数名）、`functionSchema`）

**Response:** `PgrestViewCollectionOption`（`requestSchema` / `responseSchema` 字段已回填）

## Java Types Reference

### `CollectionOption`（抽象基类）

位置：`org.dataPilot.conf.option.CollectionOption`

| Field | Type | 说明 |
|---|---|---|
| `id` | `String` | 唯一 ID |
| `name` | `String` | collection 名（大小写敏感） |
| `title` | `String` | 展示标题 |
| `dataSourceKey` | `String` | 父数据源 key |
| `fields` | `List<FieldOption>` | 字段定义 |
| `primaryKeys` | `List<String>` | 主键字段名列表 |
| `type` | `CollectionType` | `NORMAL` / `SQL` / `API` / `SCHEMA_API` / `PGREST_VIEW` |
| `cache` | `Boolean` | 是否启用缓存 |
| `cacheOptions` | `CacheOptions` | 缓存策略 |
| `isTree` | `Boolean` | 树结构（父子） |
| `version` | `Integer` | Schema 版本号 |
| `openLog` | `Boolean` | 是否开启审计日志 |

关键方法：`getKey()` 返回 `"dataSourceKey.name"`；`set(CT option)` 用于合并选项。

### `DataSourceOption`

位置：`org.dataPilot.conf.option.DataSourceOption`

| Field | Type | 说明 |
|---|---|---|
| `key` | `String` | 唯一 ID，HTTP 路径中的 `{datasource}` |
| `name` | `String` | 数据源名 |
| `title` | `String` | 展示标题 |
| `type` | `DataSourceType` | `DB` / `API` / `SCHEMA_API` / `SQL` |

### `SchemaDefinition`

位置：`org.dataPilot.schemaApi.model.SchemaDefinition`

| Field | Type | 说明 |
|---|---|---|
| `name` | `String` | Schema 名 |
| `description` | `String` | 文档描述 |
| `fields` | `List<FieldOption>` | 字段定义（注意：**不是** `SchemaFieldOption`） |

方法：`builder()`、`setFields(...)`、`addField(FieldOption)`、`getField(String name)`。

### `SchemaFieldOption`

位置：`org.dataPilot.schemaApi.model.SchemaFieldOption`

| Field | Type | 说明 |
|---|---|---|
| `fieldOption` | `FieldOption` | 字段定义 |
| `location` | `String` | `QUERY` / `HEADER` / `PATH` / `BODY` |
| `deprecated` | `Boolean` | 是否弃用 |

### `FieldOption`

位置：`org.dataPilot.field.option.FieldOption`

| Field | Type | 说明 |
|---|---|---|
| `dataType` | `DataType` | `BIGINT` / `VARCHAR` / `JSON` 等 |
| `isVirtual` | `Boolean` | 虚拟字段（非 DB 列） |
| `type` | `String` | UI 显示类型 |
| `name` | `String` | 字段名（DB 中通常 snake_case） |
| `title` | `String` | 显示标签 |
| `description` | `String` | 文档 |
| `comment` | `String` | DB COMMENT |
| `length` | `Integer` | VARCHAR 长度 / 精度 |
| `primaryKey` | `Boolean` | 是否主键 |
| `dbIndexOptions` | `DbIndexOptions` | 搜索索引配置 |
| `defaultValue` | `Object` | DB 默认值 |
| `buildIndex` | `boolean` | 是否建搜索索引（默认 true） |
| `targetIndexField` | `List<String>` | 被索引的父字段 |
| `validators` | `List<Validator>` | 校验规则 |

常用方法：`dataTypeEmpty()`、`getIsKeyword()`、`getAnalyzeType()`（`NONE` / `KEYWORD` / `CN_PINYIN` / `ENGLISH` …）、`isNested()`、`getKeywordFields()`。

构造器：
```java
new FieldOption();
new FieldOption(String type, String name, Integer length, String comment);
new FieldOption(String type, String name, String comment);
```

### `DataPilotCollectionCommand`

位置：`org.dataPilot.manager.command.DataPilotCollectionCommand`

Collection DDL 的命令封装。包含子命令：`CreateFieldCommand` / `UpdateFieldCommand` / `RemoveFieldCommand` 等。

```java
public class DataPilotCollectionCommand {
    public String dataSourceKey;      // 目标数据源
    public String collection;         // 目标 collection
    public List<SubCommand> subCommands;  // 具体变更
    public void execute_(EngineContext<C, U> context);
}
```

通过 HTTP 提交到 `/updateCollection`，或在 Java 层直接 `dataSourceService.updateCollection(ctx, command)`。

### `PgrestViewCollectionOption`

位置：`org.dataPilot.conf.option`（继承自 `CollectionOption`，`type = PGREST_VIEW`）

描述由 PostgreSQL 函数驱动的虚拟视图 collection：

| Field | Type | 说明 |
|---|---|---|
| `dataSourceKey` | `String` | 目标 PG 数据源 key |
| `name` | `String` | **函数名**，作为 collection 名称 |
| `functionSchema` | `String` | PG schema（如 `public`） |
| `requestSchema` | `SchemaDefinition` | 请求参数 Schema（`/pgrest/parseFunction` 会回填） |
| `responseSchema` | `SchemaDefinition` | 返回结果 Schema（`/pgrest/parseFunction` 会回填） |
| `fields` | `List<FieldOption>` | 继承自 `CollectionOption`，通常由 responseSchema 推导 |

## Java Embedded Usage

当作为嵌入引擎使用（非 HTTP）时，所有元数据均可通过 `DataSourceEngine` 与 `DataSourceService` 获取。

### 通过 `DataSourceService` 走与 HTTP 一致的路径

```java
@Inject
Map<String, DataSourceService<?, ?>> serviceMap;

DataSourceService<Ctx, User> service = (DataSourceService<Ctx, User>) serviceMap.get("default");

// 对应 /dataSourceList
List<DataSourceOption> datasources = service.dataSourceList(ctx);

// 对应 /{datasource}/collections
List<CollectionOption> collections = service.getDataSourceCollections(ctx, "order");

// 对应 /{datasource}/{collection}/options
CollectionOption one = service.collectionOptions(ctx, "order", "orders");

// 对应 /{datasource}/{collection}/fullOptions
Map<String, CollectionOption> full = service.collectionFullOptions(ctx, "order", "orders");

// 对应 /updateCollection
DataPilotCollectionCommand cmd = new DataPilotCollectionCommand();
cmd.dataSourceKey = "order";
cmd.collection = "orders";
// ... 填充 subCommands
service.updateCollection(ctx, cmd);
```

### 通过 `DataSourceEngine.datasource(key)` 直接遍历

当需要**绕开 service 层**，直接枚举某个数据源下的 collection（例如在启动脚本、运维工具或自定义 Introspector 内）：

```java
DataSourceEngine<Ctx, User> engine = service.engine;

// 获取单个数据源
DataSource ds = engine.datasource("order");

// 遍历所有已注册数据源
for (DataSource source : engine.getDataSources()) {
    for (CollectionOption option : source.getCollections()) {
        System.out.println(option.getKey() + " -> " + option.fields.size() + " fields");
        for (FieldOption field : option.fields) {
            System.out.println("  " + field.name + " : " + field.dataType);
        }
    }
}
```

对于 Schema API 场景，可实现 `org.dataPilot.schemaApi.contract.DataSourceIntrospector` / `CollectionIntrospector` / `FieldIntrospector` 来提供自定义的元数据来源，引擎会在构建 `SchemaApiCollectionOption` 时调用它们。

## Examples

### 示例 1：列出所有数据源

```bash
curl -s -X POST http://localhost:8080/dataSourceList \
  -H 'Content-Type: application/json' \
  -d '{}'
```

```json
[
  { "key": "order",  "name": "order",  "title": "订单库", "type": "DB" },
  { "key": "pgrest", "name": "pgrest", "title": "PG 视图库", "type": "SCHEMA_API" }
]
```

### 示例 2：列出 `order` 数据源的所有 collection

```bash
curl -s -X POST http://localhost:8080/order/collections \
  -H 'Content-Type: application/json' \
  -d '{}'
```

### 示例 3：获取单个 collection 的最小元数据

```bash
curl -s -X POST http://localhost:8080/order/orders/options \
  -H 'Content-Type: application/json' \
  -d '{}'
```

只包含 `orders` 自身定义的字段；关联表的字段不会出现。

### 示例 4：获取包含继承/关联的完整元数据

```bash
curl -s -X POST http://localhost:8080/order/orders/fullOptions \
  -H 'Content-Type: application/json' \
  -d '{}'
```

返回 `Map<String, CollectionOption>`，key 为 collection 名。主表 `orders` 下的字段已合并其父级/关联字段。

### 示例 5：通过 `/updateCollection` 向表追加字段

```bash
curl -s -X POST http://localhost:8080/updateCollection \
  -H 'Content-Type: application/json' \
  -d '{
    "dataSourceKey": "order",
    "collection": "orders",
    "subCommands": [
      {
        "kind": "CreateField",
        "field": {
          "name": "remark",
          "type": "VARCHAR",
          "length": 255,
          "comment": "订单备注"
        }
      }
    ]
  }'
```

返回 `{ "success": true }` 表示执行成功。

### 示例 6：解析 PG 函数生成 request/response Schema

```bash
curl -s -X POST http://localhost:8080/pgrest/parseFunction \
  -H 'Content-Type: application/json' \
  -d '{
    "dataSourceKey": "pgrest",
    "functionSchema": "public",
    "name": "search_orders"
  }'
```

响应会回填 `requestSchema` 与 `responseSchema`，可直接作为 `PgrestViewCollectionOption` 注册到引擎。

## Workflow

1. 根据本文件确认 `collectionOptions` / `collectionFullOptions` / `updateCollection` 契约
2. 按需求选择 `/options`（最小）或 `/fullOptions`（包含继承），或在 Java 层使用 `engine.datasource(key)` 直接遍历
3. DDL 变更统一通过 `DataPilotCollectionCommand` + `/updateCollection`，**不要**直接绕过写 SQL

## Checklist

- [ ] 已确认 datasource / collection 标识和目标元数据接口
- [ ] 区分 `/options` 与 `/fullOptions` 语义
- [ ] `{datasource}` 使用 `DataSourceOption.key`；`{collection}` 使用 `CollectionOption.name`（大小写敏感）
- [ ] `updateCollection` 通过 `DataPilotCollectionCommand` 提交，不手写 DDL
- [ ] Pgrest 函数先 `parseFunction` 得到 Schema，再注册
- [ ] Java 嵌入路径优先使用 `DataSourceService`，仅在必要时用 `engine.datasource(key)` 直接遍历
- [ ] 引用均使用类名/方法名，而非行号

## Common Mistakes

| Mistake | Fix |
|---|---|
| 用 `/options` 期望得到关联/继承字段 | 改用 `/fullOptions`，后者返回 `Map<String, CollectionOption>` 并合并继承字段 |
| 大小写不敏感地传 `{collection}` | `CollectionOption.name` 大小写敏感，必须与注册时一致 |
| 直接写 SQL 修改表结构 | 通过 `DataPilotCollectionCommand` + `/updateCollection` 才能同步到索引、缓存、Schema 版本 |
| 把 `SchemaDefinition.fields` 当作 `List<SchemaFieldOption>` | 它是 `List<FieldOption>`；`SchemaFieldOption` 仅出现在显式的请求位置描述中 |
| 以为 `/pgrest/parseFunction` 会自动注册 collection | 它只回填 `requestSchema`/`responseSchema`，仍需显式保存/注册到数据源 |
| Pgrest 函数重名但 schema 不同时只传 `name` | 必须同时传 `functionSchema`，否则解析结果不确定 |
| HTTP body 留空为 `null` | 所有 `POST` 端点需要至少 `{}`，否则框架反序列化失败 |
