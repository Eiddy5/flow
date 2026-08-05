---
name: datapilot-subscribe
description: |
  Register, preview, and clear DataPilot data-change subscriptions through subscribeChange,
  parseSubscribeMessage, clearSubscriptions, and clearAllSubscriptions. Use when users ask
  about SubscribeChangeRequest, filtered collection notifications, subscription topic/instance
  metadata, WebSocket data-change delivery, or enabling JooqNotifyConsumer.
argument-hint: "[datasource] [collection]"
allowed-tools: Read Grep Glob
paths: "src/main/java/org/dataPilot/**"
version: 0.2.0
---

# DataPilot 数据变更订阅

当前 HTTP API 用 `QueryCondition` 注册集合级订阅。服务端根据当前 session、collection 和条件生成订阅标识并存入 Redis；topic 与 instance 都由服务端计算，客户端不提交它们。

## 前置条件

- 已配置 Redis，`SubscriberManager` 用它保存订阅、用户映射和条件索引。
- 需要实际接收 DB 变更通知时，显式启用 `JooqNotifyConsumer`：

```yaml
datapilot:
  enable-jooq-notify-consumer: true
```

非 Micronaut 环境：

```java
configuration.setEnableJooqNotifyConsumer(true);
```

该消费者默认关闭。关闭时仍可调用订阅注册接口，但 DBEvent 不会进入 `DataChangeNotify` 推送链路。

## 请求模型

`org.dataPilot.micronaut.application.SubscribeChangeRequest` 当前只有两个字段：

```java
public class SubscribeChangeRequest {
    public SubscribeType subscribeType;
    public QueryCondition filter;
}
```

当前 service 实现使用 `filter`，并根据它是否为主键条件推断 `ID` 或 `FILTER`；`subscribeType` 暂未参与注册逻辑。`filter` 应显式提供，订阅全表时传空的 `QueryCondition`，不要传 `null`。

## HTTP API

以下路径通常位于 DataPilot controller 根路径下；可选 query 参数 `serviceKey` 用于选择 engine service。

### 注册订阅

`POST /{datasource}/{collection}/subscribeChange`

```json
{
  "filter": {
    "fields": {
      "status": {"$eq": "PAID"}
    }
  }
}
```

响应只承诺两个字段：

```json
{
  "topic": "postgresql.sales.orders",
  "instance": "status == 'PAID'"
}
```

- `topic` 来自 collection key。
- `instance` 是过滤条件转换出的表达式。
- session 的 cookie/user/org/dept 信息用于服务端生成并保存完整 `Subscription`。
- 当前 HTTP 响应不返回 `subscriptionId` 或 `REGISTERED` 状态。

### 预解析订阅元数据

`POST /{datasource}/{collection}/parseSubscribeMessage`

请求体仍是同一个 `SubscribeChangeRequest`，响应仍是 `{topic, instance}`。这个名称容易误解：当前实现只是调用 `SubscriberManager.parse(...)` 计算订阅元数据，不保存订阅，也不解析 broker/WebSocket 原始消息，更不会返回 `before/after/op`。

### 清理当前用户在集合上的订阅

`POST /{datasource}/{collection}/clearSubscriptions`

```json
{
  "success": true,
  "count": 3
}
```

`count` 是当前 session 用户在该 collection 上被删除的订阅数。

### 清理集合的全部订阅

`POST /{datasource}/{collection}/clearAllSubscriptions`

该接口删除 collection 的全部订阅并返回成功对象，调用方应在服务层自行限制管理权限。

## Java 用法

```java
SubscribeChangeRequest request = new SubscribeChangeRequest();
request.filter = QueryCondition.field("status").eq("PAID");

JsonObject subscription = dataSourceService.subscribeChange(
    session, "postgresql.sales", "orders", request);

String topic = subscription.getString("topic");
String instance = subscription.getString("instance");
```

只预览 topic 与表达式、不写 Redis：

```java
JsonObject preview = dataSourceService.parseMessage(
    session, "postgresql.sales", "orders", request);
```

直接使用 manager 时，`subscribe(...)` 返回完整 `Subscription`，`parse(...)` 只构造对象：

```java
Subscription saved = subscriberManager.subscribe(context, collection, request.filter);
Subscription previewOnly = subscriberManager.parse(context, collection, request.filter);
```

## 推送语义

启用通知消费者后，链路为：

```text
DBEvent -> JooqNotifyConsumer -> DataChangeNotify
        -> Redis 条件候选集 -> Aviator 表达式匹配
        -> 收件人拦截器 -> WebSocket broadcast/targeted send
```

insert/update 通知会按主键回 DB 加载当前模型后再匹配和推送；原始 DBEvent 不是完整业务对象。WebSocket body 使用 `DataChangeMessage` 的 `pk / eventId / mode / data`，没有通用的 `before` 字段。

## 常见错误

| 错误 | 正确做法 |
|---|---|
| 请求里提交 `topic`、`subscriptionId` | 只提交 `filter`，topic/instance 由服务端生成 |
| 把 `parseSubscribeMessage` 当 wire payload decoder | 它只预计算订阅元数据，不解析消息 |
| 期待 `{op,before,after}` 响应 | 注册与预解析接口都只返回 `{topic,instance}` |
| 只注册订阅，不开启通知 consumer | 设置 `enable-jooq-notify-consumer=true` |
| 传 `filter=null` 订阅全表 | 传显式空 `QueryCondition` |
| 普通用户调用 `clearAllSubscriptions` | 在外层服务增加管理权限约束 |

## 验收清单

- [ ] 请求只依赖当前 `SubscribeChangeRequest` 字段
- [ ] `filter` 非 null，且能转换为 expression
- [ ] 已配置 Redis
- [ ] 需要推送时已开启 `JooqNotifyConsumer`
- [ ] 客户端只依赖 `{topic, instance}` 响应
- [ ] `parseSubscribeMessage` 没有被当作事件 payload 解析器
- [ ] 集合级全量清理受权限保护
