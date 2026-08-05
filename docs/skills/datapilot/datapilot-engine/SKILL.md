---
name: datapilot-engine
description: |
  Java 后端嵌入 cloud-datapilot 引擎完整指南：DataSourceEngine、EngineExecutor 事务模板、
  DataQuery/DataUpdater/DataDeleter/DataAggregate/DataCreator 构建器 API、DB/ES 分组摘要、
  ExtendableQuery 类型安全查询、索引 LoadStrategy 注册、事务管理、异常处理。Use when the user asks to
  "embed datapilot engine", "Java 后端调用 datapilot", "DataSourceEngine",
  "EngineExecutor", "buildQuery", "listGroup", "groupInfo", "buildUpdater", "executeResult",
  "LoadStrategy", "索引策略", "无主键更新同步索引".
argument-hint: "[class] [method]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.java"
version: 1.1.0
---

# Java 后端引擎嵌入指南

面向 Java 后端开发者，通过 `DataSourceEngine` 在 Spring Boot / Micronaut 应用中嵌入 cloud-datapilot 数据引擎。

## 1. 入口引擎 DataSourceEngine

### 1.1 获取引擎实例

```java
import org.dataPilot.DataSourceEngine;
import paas.auth.Context;
import paas.auth.User;

// 获取默认引擎 (单例)
DataSourceEngine<Context<User>, User> engine =
    DataSourceEngine.use("default", MyDataSourceEngine.class);

// 获取引擎 Key
String key = engine.getKey();
```

引擎是**单例**，通过 `instances` Map 管理。工厂类型通过 `factoryMap` 注册。

### 1.2 引擎构建器速查

```java
// === 创建执行器 (事务边界) ===
CommonExecutor<Context<User>, User> exec = engine.useExecutor(ctx);

// === 查询构建器 ===
DataQuery<Context<User>, User, ?> query =
    engine.buildQuery(ctx, "salesDb", "orders");        // 字符串方式
DataQuery<Context<User>, User, ?> query2 =
    engine.buildQuery(ctx, ORDERS);                      // jOOQ 表引用

// === 创建构建器 ===
DataCreator<Context<User>, User> creator =
    engine.buildCreator(ctx, "salesDb", "orders");

// === 更新构建器 ===
DataUpdater<Context<User>, User, ?> updater =
    engine.buildUpdater(ctx, "salesDb", "orders");
DataUpdater<Context<User>, User, ?> updater2 =
    engine.buildUpdater(ctx, ORDERS);

// === 删除构建器 ===
DataDeleter<Context<User>, User, ?> deleter =
    engine.buildDeleter(ctx, "salesDb", "orders");

// === 聚合构建器 ===
DataAggregate<Context<User>, User> agg =
    engine.buildAggregate(ctx, "salesDb", "orders");

// === 全局搜索 ===
DataGlobalSearch<Context<User>, User, ?> search =
    engine.buildGlobalSearch(ctx);

// === 多集合查询 ===
MultiSearchQuery<Context<User>, User> multi =
    engine.buildMultiQuery(ctx);

// === 类型安全查询 ===
OrdersQuery q = engine.useQuery(OrdersQuery.class, ctx);
OrdersUpdate u = engine.useUpdate(OrdersUpdate.class, ctx);
OrdersDelete d = engine.useDelete(OrdersDelete.class, ctx);

// === 定义数据源 ===
DbDataSource db = engine.defineDbDataSource(dbOption);
ApiDataSource api = engine.defineApiDataSource(apiOption);
SchemaApiDataSource schemaApi = engine.defineSchemaApiDataSource(schemaApiOption);

// === 列出所有数据源 ===
List<DataSource<?, ?, ?>> sources = engine.getDataSources();

// === 注册插件 ===
engine.registerPlugin(new MyPlugin());

// === 注册字段类型 ===
engine.registerField("customType", new CustomField());

// === 非 Micronaut 场景注册 ES 索引加载策略 ===
dataPilotIndex.registerLoadStrategy(new OrderUniqueKeyLoadStrategy());
```

---

## 2. EngineExecutor 事务模板

### 2.1 核心概念

`EngineExecutor` 是 Java 端最强的事务控制机制。所有 Lambda 内的操作在**同一事务**中执行。

事务生命周期: `clear → start → execute(fn) → commit(成功) / rollback(异常) → close`

### 2.2 无返回值操作 (execute)

```java
import org.dataPilot.handler.CommonExecutor;
import org.dataPilot.common.EngineContext;

CommonExecutor<Context<User>, User> exec = engine.useExecutor(ctx);

exec.execute((EngineContext<Context<User>, User> ec) -> {
    // 所有操作在同一事务中
    engine.buildCreator(ctx, "salesDb", "orders")
          .create(Map.of("customerId", 42, "amount", 100.0));

    engine.buildUpdater(ctx, "salesDb", "customers")
          .filter(QueryCondition.Eq("id", 42))
          .update(Map.of("orderCount", 1));
});
// 异常自动 rollback
```

### 2.3 有返回值操作 (executeResult)

```java
Model order = exec.executeResult(ec ->
    engine.buildQuery(ctx, "salesDb", "orders")
          .filter(QueryCondition.Eq("id", 10086))
          .single(Model.class)
);

ListResult<Model> list = exec.executeResult(ec ->
    engine.buildQuery(ctx, "salesDb", "orders")
          .filter(QueryCondition.field("status").eq("PENDING"))
          .pageSize(20)
          .list()
);
```

### 2.4 审计元数据

```java
exec.logMeta(JsonObject.Of(
    "source", "import-job",
    "batchId", "B-20250401"
)).execute(ec -> {
    engine.buildCreator(ctx, "salesDb", "orders")
          .create(values);
});
```

### 2.5 注入自定义 DSLContext

```java
exec.dsl(customDslContext).executeResult(ec -> {
    // 使用自定义 jOOQ DSLContext
});
```

---

## 3. DataQuery 完整 API

### 3.1 过滤条件

```java
DataQuery<C, U, ?> query = engine.buildQuery(ctx, "salesDb", "orders");

// 链式设置
query.eq("status", "active")
     .gt("amount", 100)
     .lt("amount", 1000)
     .gte("createdAt", "2025-01-01")
     .lte("createdAt", "2025-12-31")
     .ne("status", "deleted")
     .in("status", List.of("A", "B", "C"))
     .notIn("region", List.of("XX", "YY"))
     .isNull("deletedAt")
     .isNotNull("email")
     .between("date", "2025-01-01", "2025-12-31")
     .notBetween("score", 0, 60)
     .like("name", "张%")
     .startsWith("code", "ORD")
     .endsWith("email", "@company.com")
     .contains("desc", "重要")
     .regex("code", "^[A-Z]{2}\\d+")
     .keyword("搜索词");

// 直接设置 QueryCondition
query.filter(QueryCondition.And(
    QueryCondition.Eq("status", "active"),
    QueryCondition.Gt("amount", 100)
));

// AND 条件组
query.and(
    QueryCondition.Eq("region", "CN"),
    QueryCondition.Eq("status", "active")
);

// OR 条件组
query.or(
    QueryCondition.Eq("status", "expired"),
    QueryCondition.Eq("status", "cancelled")
);
```

### 3.2 排序

```java
// 单字段
query.desc("createdAt");
query.asc("name");

// 多字段
query.sort(QuerySorts.Of(
    QuerySort.Of("createdAt", SortOrder.desc),
    QuerySort.Of("amount", SortOrder.asc)
));

// JSONB 子字段排序
query.sort(QuerySorts.Of(
    QuerySort.Of("metadata->>'priority'", JsonbValueType.INTEGER)
));
```

### 3.3 分页、字段选择、分组

```java
query.pageNumber(0)       // 页码 (0基)
     .pageSize(20)         // 每页条数
     .offset(100)          // 偏移量 (与分页互斥)
     .fields("id", "customerId", "amount", "status")
     .excludeFields("internalNote")
     .groupBy("status")
     .bucketSorts(QuerySorts.Of("count", SortOrder.desc))
     .bucketPageSize(10)
     .bucketOffset(0);
```

### 3.4 关联加载

```java
query.findAssociations("customer", "items")
     .withParent(true)          // 包含父表字段
     .withChildren("subItems"); // 指定子关联
```

关联树默认在当前执行线程内加载，避免事务连接在线程池任务之间扩散：

```java
configuration.setUseDirectAssociationLoadingExecutor(true); // 默认值
```

只有把该配置设为 `false`，并且查询同时调用 `isAsync(true)` 时，关联节点才会使用 `EngineContext` 的异步 executor 扇出。该开关目前只有 Java 配置入口。

### 3.5 ES 控制

```java
query.useEs(true);                           // 走 ES
query.useEs(filterCtx -> filterCtx.hasKeyword());  // 动态决定
```

### 3.6 执行

```java
// 取单条
Model single = query.single();
MyDTO dto = query.single(MyDTO.class);

// 分页列表
ListResult<Model> list = query.list();
ListResult<MyDTO> dtos = query.list(MyDTO.class);

// 计数
Long count = query.count();

// ES 搜索 (返回原始 ES 响应)
SearchResult<JsonModel> searchResult = query.search();
CountResponse searchCount = query.searchCount();

// 异步查询
query.isAsync(true).list();

// 缓存查询
query.cache(true).list();

// DISTINCT
query.isDistinct(true).list();

// 去重查询
query.distinct(true).list();
```

### 3.7 分组摘要与分组计数

```java
// 普通字段：groupInfo 为字段值
List<ListMapResult<Model>> statusGroups = engine
    .buildQuery(ctx, "salesDb", "orders")
    .groupBy("status")
    .pageSize(0)
    .useEs(false)
    .listGroup(Model.class);

// BelongsTo：key 为源外键，groupInfo 为完整目标模型
List<ListMapResult<Model>> categoryGroups = engine
    .buildQuery(ctx, "salesDb", "orders")
    .groupBy("category")
    .pageSize(0)
    .useEs(false)
    .listGroup(Model.class);

// 只取 key -> total，不加载 groupInfo
Map<String, Long> totals = engine
    .buildQuery(ctx, "salesDb", "orders")
    .groupBy("category")
    .countGroup();
```

DB 摘要模式要求 `pageSize(0)` 且只允许一个 group expression。返回项的 `items.items` 为空，`items.total` 是组内数据量；BelongsTo 目标通过一次批量查询补全。前端后续使用 `key` 过滤源外键并调用普通 `list()` 分页。

---

## 4. DataUpdater 完整 API

### 4.1 条件更新

```java
DataUpdater<C, U, ?> updater = engine.buildUpdater(ctx, "salesDb", "orders");

// 设置条件 (同 DataQuery)
updater.eq("id", 10086)
       .filter(QueryCondition.field("status").eq("PENDING"));

// 执行更新
updater.update(Map.of("status", "SHIPPED", "amount", 399.00));
```

### 4.2 带运算符的更新

```java
// 数字自增
updater.set("retryCount", NumberOperator.Add(1))
       .update(Map.of("status", "RETRY"));

// 数组追加
updater.set("tags", ArrayOperator.addItem("newTag"))
       .update(Map.of());

// JSONB 数组操作
updater.set("items", JsonArrayOperator.addItem(Map.of("key", "value")))
       .update(Map.of());

// 设 NULL
updater.set("deletedAt", SetNullOperator.Of())
       .update(Map.of());
```

### 4.3 列白名单/黑名单

```java
updater.white(Set.of("status", "amount"))     // 只允许这些列
       .white("status", "amount")              // 变参形式
       .black(Set.of("internalId"));           // 禁止这些列
```

### 4.4 Upsert 语义

```java
// save: 有主键则更新，无主键则创建 (单条 upsert)
updater.save(Map.of("id", 10086, "status", "SHIPPED"));
updater.save(model);

// create: 不存在则创建 (insert-if-not-exists)
updater.create(Map.of("customerId", 42, "amount", 100.0));

// batchCreate: 批量创建
updater.batchCreate(List.of(
    Map.of("customerId", 1, "amount", 10.0),
    Map.of("customerId", 2, "amount", 20.0)
));

// batchSave: 批量 upsert
updater.batchSave(List.of(
    Map.of("id", 10086, "status", "SHIPPED"),
    Map.of("customerId", 99, "amount", 99.0)  // 无 id → 创建
));

// 逐行运算符 (batchSave 场景)
updater.perRecordOperators(List.of(
    Map.of("amount", NumberOperator.Add(5)),
    Map.of("amount", NumberOperator.Sub(3))
));
```

### 4.5 关联写入

```java
updater.updateAssociations("customer", "items");
updater.defaultUpdateAssociations(true);
```

---

## 5. DataDeleter 完整 API

```java
DataDeleter<C, U, ?> deleter = engine.buildDeleter(ctx, "salesDb", "orders");

// 条件删除
deleter.eq("id", 10086)
       .filter(QueryCondition.field("status").eq("EXPIRED"))
       .in("status", List.of("CANCELLED", "EXPIRED"));

// ⚠️ 必须显式确认，否则删除不生效
deleter.confirmDelete(true);

// 执行
deleter.delete();
```

---

## 6. DataAggregate 完整 API

```java
DataAggregate<C, U> agg = engine.buildAggregate(ctx, "salesDb", "orders");

// 聚合函数 (自动注册)
agg.max("amount")                    // 最大值
   .max("amount", "maxAmount")       // 带别名
   .min("amount")
   .min("amount", "minAmount")
   .sum("amount")
   .sum("amount", "totalAmount")
   .avg("amount")
   .avg("amount", "avgAmount")
   .count("id")
   .count("id", "orderCount");

// 分组
agg.groupBy("status", "region");

// 过滤
agg.filter(QueryCondition.field("createdAt").gte("2025-01-01"));

// 字段选择
agg.fields("status", "region", "totalAmount", "orderCount");

// 去重
agg.distinct(true);

// 执行
ListResult<AggregateResult> result = agg.aggregate();
ListResult<MyAggDTO> dtos = agg.aggregate(MyAggDTO.class);
```

---

## 7. ExtendableQuery / ExtendableUpdate / ExtendableDelete (类型安全)

### 7.1 定义

```java
import org.dataPilot.handler.query.ExtendableQuery;
import org.jooq.impl.TableImpl;

// 类型安全的查询
public class OrdersQuery extends ExtendableQuery<Session<User>, User, OrdersQuery> {
    @Override
    public TableImpl<?> getTable() {
        return ORDERS;  // jOOQ 生成的表引用
    }
}

// 类型安全的更新
public class OrdersUpdate extends ExtendableUpdate<Session<User>, User, OrdersUpdate> {
    @Override
    public TableImpl<?> getTable() { return ORDERS; }
}

// 类型安全的删除
public class OrdersDelete extends ExtendableDelete<Session<User>, User, OrdersDelete> {
    @Override
    public TableImpl<?> getTable() { return ORDERS; }
}
```

### 7.2 使用

```java
// 获取实例
OrdersQuery q = engine.useQuery(OrdersQuery.class, ctx);
OrdersUpdate u = engine.useUpdate(OrdersUpdate.class, ctx);
OrdersDelete d = engine.useDelete(OrdersDelete.class, ctx);

// 链式查询
ListResult<Model> result = q
    .eq(ORDERS.STATUS, "active")
    .gt(ORDERS.AMOUNT, 100)
    .desc(ORDERS.CREATED_AT)
    .pageSize(20)
    .findAssociations("customer")
    .list();

// Lambda 嵌套条件
ListResult<Model> result2 = q
    .filter(f -> f
        .and(f2 -> f2
            .eq(ORDERS.STATUS, "active")
            .gt(ORDERS.AMOUNT, 100))
        .or(f2 -> f2
            .eq(ORDERS.STATUS, "archived")
            .lt(ORDERS.AMOUNT, 50)))
    .list();

// 类型安全更新
u.eq(ORDERS.ID, 10086)
 .setValue(ORDERS.STATUS, "SHIPPED")
 .update(Map.of());
```

---

## 8. 多集合并行查询 (MultiSearchQuery)

```java
List<MultiQueryResult<JsonModel>> results = engine.useExecutor(ctx).executeResult(ec ->
    engine.buildMultiQuery(ctx)
          .add("salesDb", "orders", item -> item
              .keyword("紧急")
              .filter(QueryCondition.field("status").eq("PENDING"))
              .pageSize(5)
              .alias("urgent_orders"))
          .add("salesDb", "customers", item -> item
              .gte("created_at", "2025-01-01")
              .pageSize(10)
              .alias("new_customers"))
          .search()
);

for (MultiQueryResult<JsonModel> r : results) {
    // r.getKey() → alias
    // r.getItems() → 数据
}

// 并行计数
Map<String, Long> counts = engine.buildMultiQuery(ctx)
    .add("salesDb", "orders", item -> item.filter(filter1))
    .add("salesDb", "customers", item -> item.filter(filter2))
    .count();
```

---

## 9. 定义数据源与集合

```java
// 1. 定义数据源
DbDataSourceOption dsOpt = new DbDataSourceOption();
dsOpt.key = "salesDb";
dsOpt.name = "Sales Database";
dsOpt.dbType = DbType.postgresql;
dsOpt.dbName = "sales";
dsOpt.jooqSchema = SCHEMA;

DbDataSource ds = engine.defineDbDataSource(dsOpt);

// 2. 定义集合
DbCollectionOption colOpt = new DbCollectionOption();
colOpt.name = "orders";
colOpt.dataSourceKey = "salesDb";
colOpt.title = "订单";
colOpt.primaryKeys = List.of("id");
colOpt.cache = true;
colOpt.cacheOptions = new CacheOptions();
colOpt.version = 1;
colOpt.openLog = true;

// 3. 定义字段
FieldOption idField = new FieldOption();
idField.name = "id";
idField.type = FieldType.AutoId;
idField.primaryKey = true;

FieldOption nameField = new FieldOption();
nameField.name = "orderNo";
nameField.type = FieldType.String;
nameField.length = 50;
nameField.validators = List.of(new RequiredValidator("orderNo", "订单号不能为空"));

colOpt.fields = List.of(idField, nameField);
ds.addCollection(colOpt);
```

### 9.1 Micronaut 运行时配置映射

`DataPilotConfigurationProperties` 会把 `datapilot.*` 配置应用到 `EngineConfiguration`：

```yaml
datapilot:
  debug: false
  development: false
  es-hosts:
    - http://127.0.0.1:9200
  manager-data-source-key: manager
  default-db-data-source: main
  max-association-depth: 5
  validate-associations-on-startup: true
  enable-jooq-notify-consumer: false
  trace:
    enabled: true
    detail-enabled: false
```

`enable-jooq-notify-consumer` 默认关闭；只有显式开启才注册 `JooqNotifyConsumer`。ES 兜底扫描的 `datapilot.es.fallback-scan.*` 配置见 `datapilot-index-fallback-scan` skill。

---

## 10. 完整示例

### 10.1 复杂事务：查询 → 更新 → 删除

```java
import org.dataPilot.common.exception.*;

public class OrderExpireJob {
    private final DataSourceEngine<Context<User>, User> engine;

    public int expireStaleOrders(Context<User> ctx, String sixMonthsAgo) {
        return engine.useExecutor(ctx)
            .logMeta(JsonObject.Of("job", "expire-orders"))
            .executeResult(ec -> {

                // 1. list — 查找过期订单
                ListResult<Model> stale = engine.buildQuery(ctx, "salesDb", "orders")
                    .filter(QueryCondition.And(
                        QueryCondition.Eq("status", "PENDING"),
                        QueryCondition.Lt("createdAt", sixMonthsAgo)))
                    .fields("id", "amount", "customerId")
                    .findAssociations("customer")
                    .pageSize(10_000)
                    .list();

                if (stale.isEmpty()) return 0;

                List<Long> allIds = stale.items.stream()
                    .map(m -> m.getLong("id")).toList();

                // 2. update — 批量标记为 EXPIRED
                engine.buildUpdater(ctx, "salesDb", "orders")
                      .filter(QueryCondition.In("id", allIds))
                      .update(Map.of("status", "EXPIRED"));

                // 3. delete — 金额为 0 的直接删除
                List<Long> zeroIds = stale.items.stream()
                    .filter(m -> m.getDecimal("amount")
                        .compareTo(BigDecimal.ZERO) == 0)
                    .map(m -> m.getLong("id")).toList();

                if (!zeroIds.isEmpty()) {
                    engine.buildDeleter(ctx, "salesDb", "orders")
                          .filter(QueryCondition.In("id", zeroIds))
                          .confirmDelete(true)
                          .delete();
                }

                return allIds.size();
            });
    }
}
```

### 10.2 聚合统计

```java
ListResult<AggregateResult> result = engine.useExecutor(ctx).executeResult(ec ->
    engine.buildAggregate(ctx, "salesDb", "orders")
          .count("id", "orderCount")
          .sum("amount", "totalAmount")
          .avg("amount", "avgAmount")
          .min("amount", "minAmount")
          .max("amount", "maxAmount")
          .groupBy("status", "region")
          .fields("status", "region", "orderCount", "totalAmount")
          .filter(QueryCondition.field("createdAt").gte("2025-01-01"))
          .aggregate()
);
```

### 10.3 批量 upsert + 窗口事务替代方案

```java
// Java 端推荐用 executeResult 代替窗口事务
engine.useExecutor(ctx).executeResult(ec -> {
    // 批量 upsert 直接在一个事务中完成
    engine.buildUpdater(ctx, "salesDb", "orders")
          .batchSave(List.of(
              Map.of("id", 10086, "status", "SHIPPED"),
              Map.of("id", 10087, "status", "CANCELLED"),
              Map.of("customerId", 99, "amount", 99.0)  // 新建
          ));
    return true;
});
```

---

## 11. ES 索引 LoadStrategy 扩展

### 11.1 什么时候需要

`DataPilotIndex` 消费 DBActionEvent 同步 ES 时，默认只会用主键批量回库加载完整模型。以下场景必须注册 `LoadStrategy`：

- 更新语句不是按主键更新，事件里没有 `id` / 主键字段。
- 事件里只有联合唯一索引，例如 `tenant_id + external_code`。
- 事件里只有业务唯一键，例如 `order_no`、`node_code`。
- 希望把同一类无主键事件合并成一次批量查询，而不是逐条回库。

没有主键且没有任何策略能处理的事件会被跳过并记录 warn。不要让策略“兜底处理所有事件”；策略只处理自己能唯一定位行的事件。

### 11.2 策略契约

```java
import org.dataPilot.common.EngineContext;
import org.dataPilot.data.Model;
import org.dataPilot.db.collection.DbCollection;
import org.dataPilot.dbindex.strategy.LoadStrategy;
import org.paas.sql.common.event.DBActionEvent;

import java.util.List;

public interface LoadStrategy {
    int getPriority();                         // 数值越大越先匹配
    String getName();                          // 日志/排查用名称
    boolean canHandle(DBActionEvent event, DbCollection<?> collection);
    List<Model> load(EngineContext context, DbCollection<?> collection, List<DBActionEvent> events);
}
```

关键语义：

- `canHandle(...)` 是事件归类，只做轻量字段检查，不查数据库。
- `load(...)` 收到的是同一个 `collection`、同一个事件 mode、同一个 strategy 匹配出来的一组 events。
- `events` 是列表是为了“先分类，再统一批量查询”；不要在 `load` 里逐个 event 查数据库。
- `load(...)` 返回完整 `Model` 列表，`DataPilotIndex` 会转回 `DBActionEvent` 继续更新 ES。
- 查不到的 event 返回空即可，不要抛异常阻断整个消费链。
- 多个策略都能处理时，`getPriority()` 大的先执行；主键加载永远优先于自定义策略。

### 11.3 Micronaut 自动注册

在 Micronaut 应用中，把策略做成 Bean 即可自动进入 `StrategyRegistry`：

```java
import jakarta.inject.Singleton;
import org.dataPilot.common.EngineContext;
import org.dataPilot.common.ListResult;
import org.dataPilot.data.Model;
import org.dataPilot.db.collection.DbCollection;
import org.dataPilot.db.search.filter.QueryCondition;
import org.dataPilot.dbindex.strategy.LoadStrategy;
import org.dataPilot.repository.request.FindRequest;
import org.paas.sql.common.event.DBActionEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Singleton
public class OrderUniqueKeyLoadStrategy implements LoadStrategy {

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public String getName() {
        return "order-tenant-external-code";
    }

    @Override
    public boolean canHandle(DBActionEvent event, DbCollection<?> collection) {
        return "orders".equals(collection.getName())
                && event.fieldValues != null
                && !collection.metaModel.hasPk(event.fieldValues)
                && event.fieldValues.containsKey("tenant_id")
                && event.fieldValues.containsKey("external_code");
    }

    @Override
    public List<Model> load(EngineContext context, DbCollection<?> collection, List<DBActionEvent> events) {
        List<QueryCondition> filters = new ArrayList<>();
        for (DBActionEvent event : events) {
            filters.add(QueryCondition.And(
                    QueryCondition.field("tenant_id").eq(event.fieldValues.get("tenant_id")),
                    QueryCondition.field("external_code").eq(event.fieldValues.get("external_code"))
            ));
        }

        ListResult<Model> result = (ListResult<Model>) collection.repository()
                .find(context, FindRequest.builder()
                        .filter(QueryCondition.Or(filters))
                        .build());

        Map<String, Model> byUniqueKey = new LinkedHashMap<>();
        for (Model model : result.items) {
            byUniqueKey.put(key(model.get("tenant_id"), model.get("external_code")), model);
        }

        return events.stream()
                .map(event -> byUniqueKey.get(key(
                        event.fieldValues.get("tenant_id"),
                        event.fieldValues.get("external_code")
                )))
                .filter(Objects::nonNull)
                .toList();
    }

    private String key(Object tenantId, Object externalCode) {
        return tenantId + "::" + externalCode;
    }
}
```

### 11.4 非 Micronaut 手动注册

非 Micronaut 或测试环境里，直接注册到 `DataPilotIndex`：

```java
DataPilotIndex dataPilotIndex = new DataPilotIndex(configuration);
dataPilotIndex.registerLoadStrategy(new OrderUniqueKeyLoadStrategy());
configuration.index = dataPilotIndex;
```

### 11.5 让 AI 生成策略时的提示词模板

把表名、唯一键字段、需要返回的关联告诉 AI：

```text
请根据 datapilot-engine skill 为 DataPilotIndex 写一个 LoadStrategy。
表名：orders
事件没有主键时可用联合唯一键：tenant_id + external_code
要求：
1. Micronaut @Singleton 自动注册。
2. canHandle 只判断表名、fieldValues 非空、无主键、包含两个唯一键字段。
3. load 收到同策略 events 后用 QueryCondition.Or + And 批量查询，一次 repository.find，不允许逐条查库。
4. 返回 Model 列表，查不到的 event 跳过，不抛异常。
5. getPriority 返回 100，getName 可读。
```

### 11.6 常见错误

| 错误 | 正确做法 |
|------|----------|
| `load` 里循环 `events` 每条 `repository.get/find` | 先把 event 分类成 OR 条件，一次 `repository.find` |
| `canHandle` 查询数据库 | `canHandle` 只检查字段和表名 |
| 忽略 `collection.metaModel.hasPk(...)` | 主键事件留给内置主键批量加载 |
| 策略匹配所有无主键事件 | 只匹配能用唯一键准确定位的表和字段 |
| 查不到数据就抛异常 | 返回空或过滤 `null`，让索引消费继续 |
| 手动注册后忘记挂到配置 | `configuration.index = dataPilotIndex` |

---

## 12. 异常处理

### 12.1 异常层次

```
RuntimeException
  └── DataPilotException
        ├── DataLockExistException   — 行级锁冲突
        ├── VersionConflictException — 乐观锁版本冲突
        └── MetaChangeException      — 集合 schema 变更
```

### 12.2 标准处理骨架

```java
try {
    return engine.useExecutor(ctx).executeResult(cmd);
} catch (DataLockExistException e) {
    // 行被锁定 → 退避重试或返回 409
    throw new HttpStatusException(409, "数据被锁定，请稍后重试");
} catch (VersionConflictException e) {
    // 版本冲突 → 重新读取后合并再提交
    Model latest = engine.buildQuery(ctx, ds, col)
        .filter(QueryCondition.Eq("id", recordId))
        .single(Model.class);
    // 合并后重试...
} catch (MetaChangeException e) {
    // schema 变更 → 刷新元数据后重试
    engine.refreshCollection(ds, col);
} catch (DataPilotException e) {
    log.error("DataPilot error", e);
    throw new HttpStatusException(500, "数据操作失败");
}
```

### 12.3 常见陷阱

| 陷阱 | 正确做法 |
|------|---------|
| 循环创建未包在事务中 | 循环写在 `executeResult` Lambda 内 |
| 忘记 `confirmDelete(true)` | 删除时必须调用 |
| `useCamelModel` 未设置 | 使用 `CamelJsonModel` 确保驼峰转换 |
| Context 为 null | 构建器调用前确保 Context 已注入用户信息 |
| 批量操作逐条调用 | 用 `batchCreate`/`batchSave` |
| 窗口事务与 executeResult 混用 | Java 端优先用 `executeResult`，HTTP 端用窗口 |
