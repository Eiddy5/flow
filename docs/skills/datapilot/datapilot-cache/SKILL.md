---
name: datapilot-cache
description: |
  cache, invalidate, clear cache, region, 缓存, cache region, statistics,
  local cache, distributed cache, datapilot. Use when the user asks to
  "clear cache", "invalidate a region", "查看缓存统计", "dump cached data",
  "只清本地缓存", or works with CacheManager / DataCacheService / CacheRequest.
  Scope: HTTP cache endpoints under /dataPilot and Java embedded cache
  invalidation via DataSourceService.engine.cacheService. Does NOT cover
  cache configuration (see CollectionOption.cacheOptions) or L2 backend
  wiring (see cachePlugin).
argument-hint: "[region|dataSourceKey.collection|statistics|nearAllData]"
allowed-tools: Read Grep Glob
paths: "src/main/java/org/dataPilot/cache/**,src/main/java/org/dataPilot/repository/DataCacheService.java,src/main/java/org/dataPilot/repository/request/CacheRequest.java,src/main/java/org/dataPilot/micronaut/application/DataSourceController.java"
version: 0.1.0
---

# DataPilot Cache Management

统一说明 DataPilot 三层缓存（L1 本地 / L2 分布式 / DB 源）的清理、统计与诊断接口。

## When to Use

- "清一下 orders 集合的缓存" / "clear orders cache"
- "只清本地缓存，保留分布式" / "clear local L1 only"
- "看一下某个 region 的命中率" / "get cache statistics"
- "dump 一下缓存里大概有哪些数据" / "dump near-all-data"
- 代码提交后需要在 Java 侧主动失效某个集合缓存
- 编辑路径 `src/main/java/org/dataPilot/cache/**` 或 `DataCacheService` 时

## Cache Tiers

DataPilot 缓存分三层，清理接口按层级区分：

| Tier | 位置 | 特点 | 失效方式 |
|---|---|---|---|
| **L1 Local** | JVM 本地（每个节点各一份） | 最快，但多节点不一致 | `clearLocalCache` / `clearAllLocalCache` |
| **L2 Distributed** | Redis（`RedisCache`，同步写入，`RMap` + `JsonJacksonCodec`） | 跨节点共享，`DuelCache` 组合 L1+L2 | `clearCache` / `clearAllCache`（同时清 L1） |
| **DB Source** | 数据库本体 | 最终真源，不走缓存接口 | 写操作通过 `handleEvent` 自动传播 |

选型原则：
- **写后主动失效某个集合** → `clearCache`（L1+L2 都清，最安全）
- **本节点发现脏数据，不想波及集群** → `clearLocalCache`
- **全量灾难恢复 / 启动初始化** → `clearAllCache`（影响范围大，慎用）
- **只排查本节点问题** → `clearAllLocalCache`

## HTTP Endpoints

所有端点以 `@Controller("/dataPilot")` 为前缀，方法均为 `POST`，可选查询参数 `?serviceKey=<name>` 指定多数据源场景下的引擎实例。

| Endpoint | Body | Returns | Purpose |
|---|---|---|---|
| `POST /dataPilot/clearCache` | `CacheRequest` | `JsonObject.Success` | 清单个 region（L1+L2） |
| `POST /dataPilot/clearAllCache` | — | `JsonObject.Success` | 清所有 region |
| `POST /dataPilot/clearLocalCache` | `CacheRequest` | `JsonObject.Success` | 只清单个 region 的 L1 |
| `POST /dataPilot/clearAllLocalCache` | — | `JsonObject.Success` | 清本节点全部 L1 |
| `POST /dataPilot/getCacheStatistics` | `CacheRequest` | `JsonObject`（命中/未命中/大小） | 单 region 指标 |
| `POST /dataPilot/getAllCacheStatistics` | — | `JsonObject` | 全部 region 指标 |
| `POST /dataPilot/getNearAllData` | `CacheRequest` | `JsonObject`（条目快照） | 诊断用：dump 已缓存条目 |

### CacheRequest Body

`org.dataPilot.repository.request.CacheRequest`

| Field | Type | Required | Description |
|---|---|---|---|
| `dataSourceKey` | `String` | yes | 数据源标识，对应 `DataSourceService` 的 key |
| `collection` | `String` | yes | 集合名 |
| `type` | `CacheType` | yes | 缓存类型：`BEAN` / `INDEX` / `EXPR` / `ASSOCIATION` / … |

工厂方法：

```java
CacheRequest.Of(String dataSourceKey, String collection, CacheType type);
CacheRequest.OfBean(String dataSourceKey, String collection); // type = BEAN
// getKey() 返回 "dataSourceKey.collection"
```

## Java Embedded Usage

服务端代码通过 `DataSourceService.engine.cacheService` 拿到 `DataCacheService`，直接调用失效方法，无需发 HTTP。

```java
@Inject
DataSourceService<?, ?> service;

// 入口
DataCacheService<Context<User>, User> cache = service.engine.cacheService;
```

`org.dataPilot.repository.DataCacheService<C, U>` 方法签名：

```java
public void       clearCache(C ctx, CacheRequest request);
public void       clearAllCache(C ctx);
public void       clearLocalCache(C ctx, CacheRequest request);
public void       clearAllLocalCache(C ctx);
public JsonObject getCacheStatistics(C ctx, CacheRequest request);
public JsonObject getAllCacheStatistics(C ctx);
public JsonObject getNearAllData(C ctx, CacheRequest request);
```

底层落到 `org.dataPilot.cache.CacheManager`：

```java
void clear(String cacheKey, CacheType type);      // cacheKey = "ds.collection"
void clearAll();
void clearLocal(String cacheKey, CacheType type);
void clearAllLocal();
void visitMetrics(MetricVisitor visitor);
```

## Workflow

1. 确认 `dataSourceKey` 与 `collection` 拼写，与 `DataSourceService` 注册一致
2. 区分用户意图：**HTTP 调用** 还是 **Java 嵌入式**
3. 判断清理层级：写后失效 → `clearCache`；节点内排障 → `clearLocalCache`
4. 构造 `CacheRequest`（`dataSourceKey` + `collection` + `CacheType`）
5. 执行后通过 `getCacheStatistics` 或 `getNearAllData` 验证

## Examples

### 1. HTTP：清单个 region

```http
POST /dataPilot/clearCache?serviceKey=primary
Content-Type: application/json

{
  "dataSourceKey": "mainDb",
  "collection":    "orders",
  "type":          "BEAN"
}
```

### 2. HTTP：只清本地缓存，保留分布式

```http
POST /dataPilot/clearLocalCache
Content-Type: application/json

{
  "dataSourceKey": "mainDb",
  "collection":    "users",
  "type":          "INDEX"
}
```

### 3. HTTP：查询单 region 统计

```http
POST /dataPilot/getCacheStatistics
Content-Type: application/json

{
  "dataSourceKey": "mainDb",
  "collection":    "products",
  "type":          "BEAN"
}
```

返回体示例字段：`hitCount` / `missCount` / `size` / `evictions`。

### 4. HTTP：诊断用，dump 近似全量条目

```http
POST /dataPilot/getNearAllData
Content-Type: application/json

{
  "dataSourceKey": "mainDb",
  "collection":    "dictCodes",
  "type":          "BEAN"
}
```

> 仅用于问题排查，生产环境大集合禁用。

### 5. Java 嵌入式：变更后主动失效

```java
@Inject
DataSourceService<Context<User>, User> service;

public void onOrderMutated(Context<User> ctx, String orderId) {
    // 业务更新
    // ...

    // 主动失效 orders 集合 BEAN 缓存（L1+L2）
    CacheRequest req = CacheRequest.OfBean("mainDb", "orders");
    service.engine.cacheService.clearCache(ctx, req);
}
```

### 6. Java 嵌入式：只清本地（已知其他节点不受影响时）

```java
CacheRequest req = CacheRequest.Of("mainDb", "users", CacheType.INDEX);
service.engine.cacheService.clearLocalCache(ctx, req);
```

## Checklist

- [ ] 已确认 `dataSourceKey` 与 `collection` 拼写，与 `DataSourceService` 注册一致
- [ ] 已选对 `CacheType`（BEAN vs INDEX vs EXPR）
- [ ] 已选对清理层级（L1 only vs L1+L2）
- [ ] 多租户场景：`Context` 中租户身份正确，避免越权清缓存
- [ ] `clearAllCache` 类接口仅限运维通道，不在业务链路里调用
- [ ] 写后失效在事务提交之后执行，避免"清了又被旧事务回填"

## Common Mistakes

| Mistake | Fix |
|---|---|
| 用 `clearAllCache` 解决单集合脏数据 | 用 `clearCache` + 精确 `CacheRequest` |
| 事务未提交就 `clearCache`，导致旧值又被加载 | 在 commit 回调里失效 |
| 多租户下传错 `Context`，清掉别的租户缓存 | 使用当前请求的 `Context<U>`，由框架解析租户 |
| 期望 `clearLocalCache` 同步到其它节点 | L1 是节点本地，跨节点需用 `clearCache`（走 L2 广播） |
| 对大集合调用 `getNearAllData` 拖垮服务 | 仅在诊断环境使用，生产禁用 |
| HTTP 调用忘记 `?serviceKey=` 指向错引擎 | 多数据源部署时显式传 `serviceKey` |
| 按行号引用 `DataSourceController` 里的端点 | 引用 `@Post("/clearCache")` 等方法名 |
