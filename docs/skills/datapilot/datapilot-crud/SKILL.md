---
name: datapilot-crud
description: |
  Single-collection CRUD with cloud-datapilot: query, get, list, count, create, update, delete, save,
  batchCreate and batchSave via HTTP REST or Java engine embedding, plus Java-only listGroup/countGroup summaries
  through buildQuery and EngineExecutor. Use when the user asks to "read/write a datapilot collection",
  "call /dataPilot/{datasource}/{collection}/*", "embed cloud-datapilot engine in Java", or integrate the
  cloud-datapilot SDK jar without source access.
argument-hint: "[datasource] [collection]"
allowed-tools: Read
paths: "**/*.java"
version: 1.1.0
---

# cloud-datapilot 单集合 CRUD 接入手册

本技能面向仅持有 `cloud-datapilot` jar 的消费者，提供 HTTP REST 与 Java 引擎嵌入两种方式下的单集合增删改查契约。`listGroup/countGroup` 分组摘要当前仅由 Java engine API 提供，没有对应 HTTP controller 端点。

## When to Use

- 集成方只拥有 `cloud-datapilot` 的二进制 jar，需要读写某个 `datasource.collection`
- 通过 HTTP 调用 `/dataPilot/{datasource}/{collection}/{get|list|count|create|batchCreate|update|delete|save|batchSave}`
- 在 Java 服务内通过 `DataSourceEngine.buildQuery / buildCreator / buildUpdater / buildDeleter` 嵌入式使用，并需要用 `EngineExecutor` 管理事务
- 需要在一个事务窗口（windowId）内完成 list → update → delete 之类的组合操作
- 需要识别并处理 `DataLockExistException / VersionConflictException / MetaChangeException`
- 需要先取分组键、分组信息与总量，再由前端对每个分组独立分页

## Maven Dependency

```xml
<dependency>
  <groupId>org.x9.cloud</groupId>
  <artifactId>cloud-datapilot</artifactId>
  <version>2.0.47</version>
</dependency>
```

构建要求：

- Java 11+，编译需加 `-parameters` 保留参数名（Micronaut 注入依赖）
- Micronaut 运行时（`io.micronaut:micronaut-core`、`micronaut-inject`）
- 传递依赖：`org.jooq:jooq`、`com.googlecode.aviator:aviator`、`org.liquibase:liquibase-core:4.28.0`、`io.trino:trino-jdbc:465`、`org.x9.cloud:paas-*`、`org.x9.cloud:cloud-jooq`

## HTTP Reference

基础路径 `/dataPilot`；所有端点为 `POST`，运行在虚拟线程（`@ExecuteOn(TaskExecutors.VIRTUAL)`），接收鉴权头 `@UserContext("上下文")` 解析出的 `Context<?>`。所有路径使用 `{datasource}`（对应 `DataSourceOption.key`）和 `{collection}`（对应 `CollectionOption.name`）。

| 方法 | 路径 | 请求 DTO | 查询参数 | 响应 | 说明 |
|---|---|---|---|---|---|
| POST | `/dataPilot/{datasource}/{collection}/get` | `GetRequest` | `serviceKey`, `useCamel` | `Model` | 按 filter / 主键取单条 |
| POST | `/dataPilot/{datasource}/{collection}/list` | `FindRequest` | `serviceKey`, `useCamel` | `ListResult<Model>` | 分页列表 |
| POST | `/dataPilot/{datasource}/{collection}/count` | `CountRequest` | `serviceKey` | `Long` | 条件计数 |
| POST | `/dataPilot/{datasource}/{collection}/create` | `CreateRequest` | `useCamel` | `Model` | 单条插入 |
| POST | `/dataPilot/{datasource}/{collection}/batchCreate` | `CreateRequest` | `serviceKey`, `useCamel` | `List<Model>` | 批量插入（单事务） |
| POST | `/dataPilot/{datasource}/{collection}/update` | `UpdateRequest` | `useCamel` | `Model` | 按 filter / 主键更新 |
| POST | `/dataPilot/{datasource}/{collection}/delete` | `DeleteRequest` | — | `{"success":true}` | 按 filter / 主键删除 |
| POST | `/dataPilot/{datasource}/{collection}/save` | `SaveRequest` | `serviceKey`, `useCamel` | `Model` | 单条 upsert |
| POST | `/dataPilot/{datasource}/{collection}/batchSave` | `BatchSaveRequest` | `serviceKey`, `useCamel` | `List<Model>` | 批量 upsert |

窗口事务辅助端点（见末尾 End-to-End 场景）：

| 路径 | 查询参数 | 响应 |
|---|---|---|
| `/dataPilot/{datasource}/{collection}/acquireWindowId` | — | `{"windowId":"..."}` |
| `/dataPilot/{datasource}/{collection}/executeWindow` | `windowId` | `ExecuteWindowResult` |
| `/dataPilot/{datasource}/{collection}/clearWindow` | `windowId` | `{"success":true}` |
| `/dataPilot/{datasource}/{collection}/revokeOperate` | `windowId`, `logId` | `{"success":true}` |

## Java Engine Reference

### 入口类

| 类 FQN | 作用 |
|---|---|
| `org.dataPilot.DataSourceEngine<C extends Context<U>, U extends User>` | 引擎入口，提供 `buildQuery / buildCreator / buildUpdater / buildDeleter / useExecutor` |
| `org.dataPilot.manager.service.DataSourceService<C, U>` | 注入点 `Map<String, DataSourceService<?, ?>> serviceMap`，其 `engine` 字段持有 `DataSourceEngine` |
| `org.dataPilot.handler.EngineExecutor<C, U, T>` | 事务模板：`execute(Command) / executeResult(CommandResult)` |
| `org.dataPilot.handler.CommonExecutor<C, U>` | `EngineExecutor` 默认实现，由 `engine.useExecutor(ctx)` 返回 |
| `org.dataPilot.handler.DataQuery<C, U, SELF>` | 查询构建器 |
| `org.dataPilot.handler.DataCreator<C, U>` | 插入构建器 |
| `org.dataPilot.handler.DataUpdater<C, U, SELF>` | 更新构建器 |
| `org.dataPilot.handler.DataDeleter<C, U, SELF>` | 删除构建器 |
| `org.dataPilot.common.EngineContext<C, U>` | `Command` 回调中的运行期上下文 |
| `org.dataPilot.db.search.filter.QueryCondition` | 过滤条件构造 |
| `org.dataPilot.data.Model` | `extends Map<String, Object>`，所有读写结果的载体 |
| `org.dataPilot.common.ListResult<T>` | 分页结果容器 |

### 关键方法签名

```java
// org.dataPilot.DataSourceEngine
public CommonExecutor<C, U> useExecutor(C ctx);
public DataQuery<C, U, ?>  buildQuery  (C context, String datasourceKey, String collectionName);
public DataCreator<C, U>   buildCreator(C context, String datasourceKey, String collectionName);
public DataUpdater<C, U,?> buildUpdater(C context, String datasourceKey, String collectionName);
public DataDeleter<C, U,?> buildDeleter(C context, String datasourceKey, String collectionName);

// org.dataPilot.handler.EngineExecutor
@FunctionalInterface
public interface Command<C extends Context<U>, U extends User> {
    void execute(EngineContext<C, U> context);
}
@FunctionalInterface
public interface CommandResult<R, C extends Context<U>, U extends User> {
    R execute(EngineContext<C, U> context);
}
public void        execute(Command<C, U> fn);
public <R> R       executeResult(CommandResult<R, C, U> fn);
public T           logMeta(JsonObject logMeta);
public T           dsl(org.jooq.DSLContext dsl);

// 事务流程（由 executeResult 保证）：clear → EngineContext → start →
//                       execute(fn) → commit（成功）/ rollback（异常）→ close
```

### Builder 执行方法

```java
// DataQuery
public <T> T              single(Class<T> clazz);                 // 单条
public ListResult<Model>  list();                                 // 分页列表
public Long               count();                                // 计数
public DataQuery<C,U,?>   filter(QueryCondition filter);
public DataQuery<C,U,?>   fields(java.util.List<String> fields);
public DataQuery<C,U,?>   sorts(QuerySorts sorts);
public DataQuery<C,U,?>   pageSize(Integer size);
public DataQuery<C,U,?>   pageNumber(Integer page);
public DataQuery<C,U,?>   offset(Integer off);

// DataCreator
public Model                 create(java.util.Map<String,Object> values);
public <T extends Model> T   create(T model);
public java.util.List<Model> batchCreate(java.util.List<java.util.Map<String,Object>> rows); // 通过 getBatchValues 驱动
public DataCreator<C,U>      white(java.util.Set<String> white);
public DataCreator<C,U>      black(java.util.Set<String> black);
public DataCreator<C,U>      updateAssociations(java.util.Set<String> assoc);

// DataUpdater
public void                  update(java.util.Map<String,Object> values);
public DataUpdater<C,U,?>    filter(QueryCondition filter);

// DataDeleter
public void                  delete();
public DataDeleter<C,U,?>    confirmDelete(boolean flag);         // 必须为 true 才会真正执行
public DataDeleter<C,U,?>    filter(QueryCondition filter);
```

## Request DTO Field Tables

### FindRequest（`org.dataPilot.repository.request.FindRequest` extends `FindOptions`）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `useEs` | `Boolean` | `true` | 优先走 Elasticsearch |
| `websocket` | `Boolean` | `false` | 是否通过 WebSocket 流式返回 |
| `filter` | `QueryCondition` | `Empty` | WHERE |
| `sorts` | `QuerySorts` | — | ORDER BY |
| `bucketSorts` | `QuerySorts` | — | 桶排序 |
| `findAssociations` | `Set<String>` | — | 预加载的关联名 |
| `fields` | `List<String>` | — | SELECT 白名单 |
| `excludeFields` | `List<String>` | — | SELECT 黑名单 |
| `offset` | `Integer` | — | 偏移 |
| `pageNumber` | `Integer` | — | 页码，0 基 |
| `pageSize` | `Integer` | — | 每页 |
| `groupBy` | `List<String>` | — | GROUP BY；DB 摘要模式只允许一个普通字段或 BelongsTo 关联名 |
| `withParent` | `Boolean` | `true` | 含父表字段 |
| `withChildren` | `List<String>` | — | 指定子关联 |
| `bucketOffset` / `bucketPageNumber` / `bucketPageSize` | `Integer` | — | 桶分页 |
| `cache` | `Boolean` | `false` | 走缓存层 |
| `isAsync` | `Boolean` | `false` | 非阻塞 |
| `needTotalCount` | `Boolean` | `true` | 是否返回 total |
| `isDistinct` | `boolean` | `false` | SELECT DISTINCT |
| `extraConfig` | `Map<String,Object>` | — | 自定义 parser 配置 |

### GetRequest（`org.dataPilot.repository.request.GetRequest` extends `FindOptions`）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `useEs` | `Boolean` | `true` | 其余字段继承自 `FindOptions` |

### CountRequest（`org.dataPilot.repository.request.CountRequest` extends `FindOptions`）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `useEs` | `Boolean` | `true` | 其余字段继承自 `FindOptions` |

### CreateRequest（`org.dataPilot.repository.request.CreateRequest` implements `GuardRequest`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `values` | `Map<String,Object>` | 单条插入值 |
| `batchValues` | `List<Map<String,Object>>` | 批量插入（`/batchCreate` 使用） |
| `whiteColumns` | `Set<String>` | 允许写入的列；`null` = 全部 |
| `blackColumns` | `Set<String>` | 禁止写入的列 |
| `updateAssociations` | `Set<String>` | 需要写入的关联名 |
| `defaultAssocUpdateEnabled` | `boolean` | 自动写入所有关联 |
| `logMeta` | `JsonObject` | 审计元数据 |

### UpdateRequest（extends `UpdateOptions` implements `GuardRequest`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `filter` | `QueryCondition` | WHERE（继承自 `UpdateOptions`） |
| `values` | `Map<String,Object>` | SET 子句（继承自 `UpdateOptions`） |
| `whiteColumns` / `blackColumns` | `Set<String>` | 列白/黑名单 |
| `updateAssociations` | `Set<String>` | 需同步更新的关联 |
| `defaultAssocUpdateEnabled` | `boolean` | 自动更新全部关联 |
| `operators` | `Map<String, AbstractOperator>` | 字段级运算符（如自增、追加） |
| `logMeta` | `JsonObject` | 审计元数据 |

### DeleteRequest（extends `DeleteOptions`）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `filter` | `QueryCondition` | — | WHERE |
| `deleteConfirm` | `boolean` | `false` | 必须显式置 `true` 才会真正删除 |
| `logMeta` | `JsonObject` | — | 审计元数据 |

### SaveRequest（implements `GuardRequest`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `values` | `Map<String,Object>` | Upsert 记录 |
| `whiteColumns` / `blackColumns` | `Set<String>` | 列白/黑名单 |
| `updateAssociations` | `Set<String>` | 需 upsert 的关联 |
| `defaultAssocUpdateEnabled` | `boolean` | 自动处理全部关联 |
| `logMeta` | `JsonObject` | 审计元数据 |

### BatchSaveRequest（implements `GuardRequest`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `batchValues` | `List<Map<String,Object>>` | 多条 upsert |
| `white` | `Set<String>` | 允许列（`getWhite()`） |
| `black` | `Set<String>` | 禁止列（`getBlack()`） |
| `updateAssociations` | `Set<String>` | 关联 |
| `defaultUpdateAssociationsEnabled` | `boolean` | 自动关联 |
| `logMeta` | `JsonObject` | 审计元数据 |

## Response Types

- **`org.dataPilot.data.Model`**：`extends Map<String,Object>`，提供 `getString / getLong / getInteger / getDouble / getDecimal / getDate / getBoolean / get(key, Class) / asObject(Class) / asJson / toJson / isUpdated / changed(name) / getOrigin / snapshot / validate`
- **默认实现 `org.dataPilot.db.model.CamelJsonModel`**：静态工厂 `CamelJsonModel.emptyModel() / Of(Map) / parse(String) / parse(String, Class) / parse(JsonObject)`；内部自动做 snake_case ↔ camelCase 互转
- **`org.dataPilot.common.ListResult<T>`**：字段 `public List<T> items` / `public long total`；静态工厂 `ListResult.Of() / Of(List, Long) / Of(List) / Of(T)`；`isEmpty()`
- **`org.dataPilot.common.ListMapResult<T>`**：分组结果，字段 `key / groupInfo / items`；`key` 是稳定分组键，`groupInfo` 是标量值或 BelongsTo 目标模型，`items` 是该组数据及总量
- **`/delete`** 返回 `JsonObject.Success()` 形如 `{"success":true}`
- **`/count`** 直接返回原生 `Long`
- **`ExecuteWindowResult`**（`org.dataPilot.manager.model.ExecuteWindowResult`）：`windowId / totalLogs / message / logsIds / user / lastExecutedTime`

### DB 分组摘要与独立分页

当只需要每个分组的信息和总量时，通过 Java engine 使用 `pageSize(0)`：

```java
List<ListMapResult<OrderDto>> groups = engine
    .buildQuery(ctx, "salesDb", "orders")
    .groupBy("category")       // BelongsTo 关联名
    .pageSize(0)
    .useEs(false)
    .listGroup(OrderDto.class);
```

响应示例：

```json
[
  {
    "key": "cat-1",
    "groupInfo": {"id": "cat-1", "name": "Science Fiction"},
    "items": {"items": [], "total": 86}
  }
]
```

规则：

- 普通字段分组时，`groupInfo` 等于字段值。
- `groupBy("category")` 命中 BelongsTo 时，DB 按源表外键聚合，再用一条批量查询加载完整分类模型，不产生 N+1。
- 空外键返回 `key=null, groupInfo=null`；悬空外键保留 `key` 和计数，但 `groupInfo=null`。
- DB 摘要模式的 `items.items` 固定为空，`items.total` 是该组总量。
- `countGroup()` 只返回 `Map<String, Long>`，不会加载关联模型。
- `useEs=true` 仍遵守既有 DB/ES 路由；无 filter、主键条件或显式 `useEs(false)` 会走 DB。
- DB 的非零 `pageSize` 分组成员查询保持原行为；第二阶段分页应改为普通 list 查询。

前端选择一个分组后，使用返回的 `key` 对源外键独立分页：

```java
ListResult<Model> page = engine
    .buildQuery(ctx, "salesDb", "orders")
    .filter(QueryCondition.Eq("category_id", groups.getFirst().key))
    .pageNumber(0)
    .pageSize(20)
    .list();

Map<String, Long> totals = engine
    .buildQuery(ctx, "salesDb", "orders")
    .groupBy("category")
    .useEs(false)
    .countGroup();
```

## Full Examples

> 所有 HTTP 示例假定服务监听在 `http://datapilot.local`，租户通过 `Authorization` 头由 `@UserContext` 解析；Java 示例假定已注入 `Map<String, DataSourceService<?, ?>> serviceMap`，通过 `serviceMap.get("salesDb").engine` 拿到 `DataSourceEngine<Context<User>, User> engine`，并持有当前请求上下文 `Context<User> ctx`。

### 1. get — 取单条

**HTTP — 按主键**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/get?useCamel=true' \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{
        "filter": { "fields": { "id": { "EQ": 10086 } } },
        "fields": ["id","customerName","amount","status"],
        "useEs": false
      }'
```

预期响应（`Model` 形式的 JSON）：

```json
{ "id": 10086, "customerName": "Alice", "amount": 299.00, "status": "PAID" }
```

**HTTP — 按业务条件取第一条**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/get?useCamel=true' \
  -H 'Content-Type: application/json' \
  -d '{
        "filter": { "and": [
          { "fields": { "customer_id": { "EQ": 42 } } },
          { "fields": { "status":      { "EQ": "PAID" } } }
        ] },
        "sorts": { "items": [ { "field": "created_at", "order": "DESC" } ] }
      }'
```

**Java engine — 按主键**

```java
import org.dataPilot.DataSourceEngine;
import org.dataPilot.common.EngineContext;
import org.dataPilot.data.Model;
import org.dataPilot.db.search.filter.QueryCondition;
import org.dataPilot.handler.CommonExecutor;
import paas.auth.Context;
import paas.auth.User;

CommonExecutor<Context<User>, User> exec = engine.useExecutor(ctx);
Model order = exec.executeResult((EngineContext<Context<User>, User> ec) ->
    engine.buildQuery(ctx, "salesDb", "orders")
          .filter(QueryCondition.field("id").eq(10086L))
          .fields(java.util.List.of("id", "customer_name", "amount", "status"))
          .single(Model.class));
```

**Java engine — 条件 + 排序取首条**

```java
Model latestPaid = engine.useExecutor(ctx).executeResult(ec ->
    engine.buildQuery(ctx, "salesDb", "orders")
          .filter(QueryCondition.And(
              QueryCondition.field("customer_id").eq(42L),
              QueryCondition.field("status").eq("PAID")))
          .pageSize(1)
          .single(Model.class));
```

### 2. list — 分页列表

**HTTP**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/list?useCamel=true' \
  -H 'Content-Type: application/json' \
  -d '{
        "filter": { "fields": { "status": { "IN": ["PAID","SHIPPED"] } } },
        "sorts":  { "items": [ { "field": "created_at", "order": "DESC" } ] },
        "pageNumber": 0,
        "pageSize": 20,
        "needTotalCount": true,
        "useEs": true
      }'
```

响应：

```json
{
  "items": [ { "id": 10086, "status": "PAID", "amount": 299.00 } ],
  "total": 137
}
```

**HTTP — 仅返回指定字段 + 关联**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/list' \
  -H 'Content-Type: application/json' \
  -d '{
        "fields": ["id","customer_id","amount"],
        "findAssociations": ["customer"],
        "pageNumber": 2,
        "pageSize": 50
      }'
```

**Java engine**

```java
import org.dataPilot.common.ListResult;

ListResult<Model> page = engine.useExecutor(ctx).executeResult(ec ->
    engine.buildQuery(ctx, "salesDb", "orders")
          .filter(QueryCondition.field("status").in(java.util.List.of("PAID", "SHIPPED")))
          .pageNumber(0)
          .pageSize(20)
          .list());

long total = page.total;
for (Model m : page.items) {
    System.out.println(m.getLong("id") + " -> " + m.getString("status"));
}
```

### 3. count

**HTTP**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/count' \
  -H 'Content-Type: application/json' \
  -d '{ "filter": { "fields": { "amount": { "GTE": 1000 } } }, "useEs": false }'
```

响应：`842`

**HTTP — 复合条件计数**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/count' \
  -H 'Content-Type: application/json' \
  -d '{ "filter": { "and": [
          { "fields": { "status":     { "EQ": "PAID" } } },
          { "fields": { "created_at": { "BETWEEN": ["2026-01-01","2026-04-01"] } } }
       ] } }'
```

**Java engine**

```java
Long hi = engine.useExecutor(ctx).executeResult(ec ->
    engine.buildQuery(ctx, "salesDb", "orders")
          .filter(QueryCondition.field("amount").gte(1000))
          .count());
```

**Java engine — 条件计数**

```java
Long paidLastQuarter = engine.useExecutor(ctx).executeResult(ec ->
    engine.buildQuery(ctx, "salesDb", "orders")
          .filter(QueryCondition.And(
              QueryCondition.field("status").eq("PAID"),
              QueryCondition.field("created_at").between("2026-01-01", "2026-04-01")))
          .count());
```

### 4. create — 单条插入

**HTTP**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/create?useCamel=true' \
  -H 'Content-Type: application/json' \
  -d '{
        "values": {
          "customer_id": 42,
          "amount": 299.00,
          "status": "PENDING"
        },
        "whiteColumns": ["customer_id","amount","status"]
      }'
```

响应（回填主键）：`{"id":10087,"customerId":42,"amount":299.0,"status":"PENDING"}`

**HTTP — 带审计元数据**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/create' \
  -H 'Content-Type: application/json' \
  -d '{
        "values": { "customer_id": 42, "amount": 299.00 },
        "logMeta": { "source": "import-job", "batchId": "B-20260407-001" }
      }'
```

**Java engine**

```java
import java.util.Map;

Model created = engine.useExecutor(ctx).executeResult(ec ->
    engine.buildCreator(ctx, "salesDb", "orders")
          .white(java.util.Set.of("customer_id", "amount", "status"))
          .create(Map.of(
              "customer_id", 42L,
              "amount",      new java.math.BigDecimal("299.00"),
              "status",      "PENDING")));
Long newId = created.getLong("id");
```

**Java engine — 带 logMeta**

```java
import io.micronaut.json.tree.JsonNode;
import org.x9.json.JsonObject;

engine.useExecutor(ctx)
      .logMeta(JsonObject.Of("source", "import-job", "batchId", "B-20260407-001"))
      .execute(ec ->
          engine.buildCreator(ctx, "salesDb", "orders")
                .create(Map.of("customer_id", 42L, "amount", new java.math.BigDecimal("299.00"))));
```

### 5. batchCreate — 批量插入

**HTTP**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/batchCreate?useCamel=true' \
  -H 'Content-Type: application/json' \
  -d '{
        "batchValues": [
          { "customer_id": 1, "amount": 10.00 },
          { "customer_id": 2, "amount": 20.00 },
          { "customer_id": 3, "amount": 30.00 }
        ]
      }'
```

响应：返回 `List<Model>`，元素顺序与请求一致，包含回填主键。

**HTTP — 限制列 + 关联写入**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/batchCreate' \
  -H 'Content-Type: application/json' \
  -d '{
        "batchValues": [ { "customer_id": 1, "amount": 10, "internal_note": "ignored" } ],
        "whiteColumns": ["customer_id","amount"],
        "updateAssociations": ["items"]
      }'
```

**Java engine — 批量插入（通过 DataCreator 的内部 batchValues 字段）**

```java
java.util.List<Map<String,Object>> rows = java.util.List.of(
    Map.of("customer_id", 1L, "amount", new java.math.BigDecimal("10.00")),
    Map.of("customer_id", 2L, "amount", new java.math.BigDecimal("20.00")),
    Map.of("customer_id", 3L, "amount", new java.math.BigDecimal("30.00")));

engine.useExecutor(ctx).execute(ec -> {
    var creator = engine.buildCreator(ctx, "salesDb", "orders")
                        .white(java.util.Set.of("customer_id", "amount"));
    for (Map<String,Object> row : rows) {
        creator.create(row);   // 同一事务内逐行插入；失败整体回滚
    }
});
```

### 6. update — 按条件更新

**HTTP — 按主键**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/update?useCamel=true' \
  -H 'Content-Type: application/json' \
  -d '{
        "filter": { "fields": { "id": { "EQ": 10086 } } },
        "values": { "status": "SHIPPED" },
        "whiteColumns": ["status"]
      }'
```

响应：更新后的 `Model`。

**HTTP — 按条件批量更新 + 字段运算符**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/update' \
  -H 'Content-Type: application/json' \
  -d '{
        "filter": { "fields": { "status": { "EQ": "PENDING" } } },
        "values": { "retry_count": 0 },
        "operators": { "retry_count": { "type": "INCREMENT", "value": 1 } }
      }'
```

**Java engine — 按主键**

```java
engine.useExecutor(ctx).execute(ec ->
    engine.buildUpdater(ctx, "salesDb", "orders")
          .filter(QueryCondition.field("id").eq(10086L))
          .update(Map.of("status", "SHIPPED")));
```

**Java engine — 条件批量**

```java
engine.useExecutor(ctx).execute(ec ->
    engine.buildUpdater(ctx, "salesDb", "orders")
          .filter(QueryCondition.And(
              QueryCondition.field("status").eq("PENDING"),
              QueryCondition.field("created_at").lt("2026-01-01")))
          .update(Map.of("status", "EXPIRED")));
```

### 7. delete — 删除

**HTTP — 按主键**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/delete' \
  -H 'Content-Type: application/json' \
  -d '{
        "filter": { "fields": { "id": { "EQ": 10086 } } },
        "deleteConfirm": true
      }'
```

响应：`{"success":true}`

**HTTP — 按条件批量删除**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/delete' \
  -H 'Content-Type: application/json' \
  -d '{
        "filter": { "fields": { "status": { "EQ": "EXPIRED" } } },
        "deleteConfirm": true,
        "logMeta": { "reason": "quarterly-cleanup" }
      }'
```

**Java engine**

```java
engine.useExecutor(ctx).execute(ec ->
    engine.buildDeleter(ctx, "salesDb", "orders")
          .filter(QueryCondition.field("id").eq(10086L))
          .confirmDelete(true)   // 关键：不置 true 不会真正删除
          .delete());
```

**Java engine — 批量删除**

```java
engine.useExecutor(ctx).execute(ec ->
    engine.buildDeleter(ctx, "salesDb", "orders")
          .filter(QueryCondition.field("status").eq("EXPIRED"))
          .confirmDelete(true)
          .delete());
```

### 8. save — 单条 upsert

**HTTP — 以主键命中则更新，否则插入**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/save?useCamel=true' \
  -H 'Content-Type: application/json' \
  -d '{
        "values": {
          "id": 10086,
          "customer_id": 42,
          "amount": 399.00,
          "status": "PAID"
        }
      }'
```

**HTTP — 新增（无主键）**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/save?useCamel=true' \
  -H 'Content-Type: application/json' \
  -d '{
        "values": { "customer_id": 99, "amount": 88.00, "status": "PENDING" },
        "whiteColumns": ["customer_id","amount","status"]
      }'
```

**Java engine — 通过 DataSourceService**

```java
import org.dataPilot.manager.service.DataSourceService;
import org.dataPilot.repository.request.SaveRequest;

DataSourceService<Context<User>, User> svc =
    (DataSourceService<Context<User>, User>) serviceMap.get("salesDb");

SaveRequest req = new SaveRequest();
req.values = Map.of("id", 10086L, "amount", new java.math.BigDecimal("399.00"), "status", "PAID");
Model saved = svc.save(ctx, "salesDb", "orders", true, req);
```

**Java engine — 新增落回同一 engine（使用 Updater 的 create 语义）**

```java
engine.useExecutor(ctx).executeResult(ec ->
    engine.buildUpdater(ctx, "salesDb", "orders")
          .create(Map.of("customer_id", 99L, "amount", new java.math.BigDecimal("88.00"))));
```

### 9. batchSave — 批量 upsert

**HTTP**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/batchSave?useCamel=true' \
  -H 'Content-Type: application/json' \
  -d '{
        "batchValues": [
          { "id": 10086, "status": "SHIPPED" },
          { "id": 10087, "status": "CANCELED" },
          { "customer_id": 55, "amount": 12.34 }
        ]
      }'
```

响应：`List<Model>`，前两条为更新结果，最后一条为新增回填。

**HTTP — 限定列 + 关联**

```bash
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/batchSave' \
  -H 'Content-Type: application/json' \
  -d '{
        "batchValues": [ { "id": 1, "status": "SHIPPED" } ],
        "white": ["status"],
        "updateAssociations": ["shipments"]
      }'
```

**Java engine — 通过 DataSourceService**

```java
import org.dataPilot.repository.request.BatchSaveRequest;

BatchSaveRequest req = new BatchSaveRequest();
req.batchValues = java.util.List.of(
    Map.of("id", 10086L, "status", "SHIPPED"),
    Map.of("id", 10087L, "status", "CANCELED"));
req.white = java.util.Set.of("status");

java.util.List<? extends Model> out =
    ((DataSourceService<Context<User>, User>) serviceMap.get("salesDb"))
        .batchSave(ctx, "salesDb", "orders", true, req);
```

## End-to-End Scenario: list → update → delete in one EngineExecutor transaction

需求：将所有 6 个月前仍处于 `PENDING` 的订单查出 → 标记为 `EXPIRED` → 再把其中金额为 0 的彻底删除。整个流程在一个 `executeResult` 中完成，任何异常整体回滚。

```java
import org.dataPilot.DataSourceEngine;
import org.dataPilot.common.EngineContext;
import org.dataPilot.common.ListResult;
import org.dataPilot.common.exception.DataLockExistException;
import org.dataPilot.common.exception.MetaChangeException;
import org.dataPilot.common.exception.VersionConflictException;
import org.dataPilot.data.Model;
import org.dataPilot.db.search.filter.QueryCondition;
import org.dataPilot.handler.CommonExecutor;
import org.x9.json.JsonObject;
import paas.auth.Context;
import paas.auth.User;

import java.util.List;
import java.util.Map;

public class ExpirePendingOrdersJob {

    private final DataSourceEngine<Context<User>, User> engine;

    public ExpirePendingOrdersJob(DataSourceEngine<Context<User>, User> engine) {
        this.engine = engine;
    }

    public int run(Context<User> ctx, String sixMonthsAgoIso) {
        CommonExecutor<Context<User>, User> exec = engine.useExecutor(ctx)
            .logMeta(JsonObject.Of("job", "expire-pending-orders"));

        try {
            return exec.executeResult((EngineContext<Context<User>, User> ec) -> {

                // 1) list：找出候选订单
                ListResult<Model> stale = engine.buildQuery(ctx, "salesDb", "orders")
                    .filter(QueryCondition.And(
                        QueryCondition.field("status").eq("PENDING"),
                        QueryCondition.field("created_at").lt(sixMonthsAgoIso)))
                    .fields(List.of("id", "amount"))
                    .pageSize(10_000)
                    .list();

                if (stale.isEmpty()) {
                    return 0;
                }

                // 2) update：全部标记为 EXPIRED
                List<Long> allIds = stale.items.stream().map(m -> m.getLong("id")).toList();
                engine.buildUpdater(ctx, "salesDb", "orders")
                      .filter(QueryCondition.field("id").in(allIds))
                      .update(Map.of("status", "EXPIRED"));

                // 3) delete：其中金额为 0 的彻底删除
                List<Long> zeroAmountIds = stale.items.stream()
                    .filter(m -> java.math.BigDecimal.ZERO
                                   .compareTo(m.get("amount", java.math.BigDecimal.ZERO)) == 0)
                    .map(m -> m.getLong("id"))
                    .toList();

                if (!zeroAmountIds.isEmpty()) {
                    engine.buildDeleter(ctx, "salesDb", "orders")
                          .filter(QueryCondition.field("id").in(zeroAmountIds))
                          .confirmDelete(true)
                          .delete();
                }

                return allIds.size();
            });
        } catch (DataLockExistException e) {
            // 有行被其它事务锁住：稍后重试
            throw e;
        } catch (VersionConflictException e) {
            // 乐观锁版本号冲突：重新拉取最新数据再重试
            throw e;
        } catch (MetaChangeException e) {
            // schema 已变更：需要刷新 CollectionOption 再执行
            throw e;
        }
    }
}
```

> 说明：`executeResult` 的事务边界由引擎托管——开始时 `start()`，Lambda 内无异常则 `commit()`，抛出任何异常即 `rollback()`。因此 list/update/delete 三步要么一起成功要么一起回滚，无需业务代码手动处理事务。

## Common Pitfalls

| 陷阱 | 触发场景 | 正确做法 |
|---|---|---|
| `useCamel` 未传导致字段名错配 | HTTP 响应字段与前端期望的 camelCase 不一致 | 所有读写端点统一加 `?useCamel=true`，引擎嵌入时 `Model` 用 `CamelJsonModel` 会自动互转 |
| 删除不生效 | `DeleteRequest.deleteConfirm` 默认 `false`，没有显式置 `true` | HTTP 中明确 `"deleteConfirm": true`；Java 中 `.confirmDelete(true)` |
| 未用 windowId 导致多步写入不原子 | 先 create 再 update 分两个 HTTP 请求 | 先 `POST /.../acquireWindowId` 拿到 `windowId`，在后续请求的 query string 追加 `windowId=...`，最后 `POST /.../executeWindow?windowId=...` 提交；中途失败可 `clearWindow` 丢弃或 `revokeOperate?logId=...` 回滚单步；Java 嵌入时用 `executeResult` 替代 windowId 更直观 |
| 租户 / Context 解析失败 | 调用 HTTP 时没带鉴权头 | 所有端点都通过 `@UserContext("上下文") Context<?> ctx` 获取身份；无鉴权头会得到空 Context 导致数据可见性错误。Java 嵌入时必须显式传入由业务层构造好的 `Context<User>` 到 `engine.buildXxx(ctx, ...)` 与 `engine.useExecutor(ctx)` |
| 绕过 white/black 写入敏感列 | 请求体里放了受保护的字段（如 `tenant_id`） | 总是显式声明 `whiteColumns` 列白名单，而不是依赖 `blackColumns` 黑名单 |
| 使用 `useEs=true` 却未建立索引 | `FindRequest` 默认 `useEs=true`，而目标集合未接入 ES | 对只读 DB 的请求显式设 `useEs=false`，避免走空索引 |
| `pageSize` 缺省导致全量拉取 | 列表接口未设分页 | 始终设置 `pageSize` 与 `pageNumber`；需要总数时保留 `needTotalCount=true`，不需要时设为 `false` 节省代价 |
| 批量插入中途失败 | 在 `executeResult` 之外循环调用 create | 把循环写在 `executeResult` 的 Lambda 里，使得所有插入处于同一事务，一处失败整体回滚 |
| logMeta 丢失 | 审计系统拿不到 `source / batchId` 等信息 | HTTP 请求体里填 `logMeta`，Java 侧用 `exec.logMeta(JsonObject.Of(...))` 链式注入 |

## Exception Types

所有异常位于包 `org.dataPilot.common.exception`，消费者需显式 catch：

| 异常类 | 继承 | 触发条件 | 建议处置 |
|---|---|---|---|
| `DataPilotException` | `RuntimeException` | SDK 通用错误基类 | 兜底捕获、记录日志 |
| `DataLockExistException` | `RuntimeException` | 行级锁被其它事务占用 | 退避重试，或提示用户重试 |
| `VersionConflictException` | `RuntimeException` | 乐观锁版本号不一致 | 重新读取最新记录后合并变更再提交 |
| `MetaChangeException` | `RuntimeException` | 集合 schema 已变更，内存元数据过期 | 刷新 `CollectionOption`（调用 `/.../options`）后重试 |

标准处理骨架：

```java
try {
    engine.useExecutor(ctx).executeResult(cmd);
} catch (DataLockExistException e) {
    // 行锁冲突 → 重试或返回 409
} catch (VersionConflictException e) {
    // 乐观锁 → 重新读取再提交
} catch (MetaChangeException e) {
    // schema 变更 → 刷新元数据
} catch (DataPilotException e) {
    // 其它 SDK 错误 → 记录并返回 500
}
```
