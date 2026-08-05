---
name: datapilot-event
description: |
  cloud-datapilot 事件系统：DbEventType 全部 20 个生命周期事件、@OnDbEvent/@DSEventListener/@DbEvent
  注解、紧凑 DBEvent/DBActionEvent 载荷、数据变更 Pulsar 消息流、EngineConsumer 架构、BeforeCreate/AfterFetch 等钩子、
  事件监听器注册。Use when the user asks to "事件监听", "lifecycle hook", "数据变更事件",
  "DbEventType", "OnDbEvent", "DSEventListener", "data change event".
argument-hint: "[eventType] [annotation]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.java"
version: 1.1.0
---

# 事件系统

面向 SDK 消费者，覆盖 cloud-datapilot 的完整事件体系：20 个生命周期事件、注解驱动监听器、Pulsar 数据变更流。

## 1. 全部生命周期事件 (DbEventType)

| 事件 | 触发时机 | 方向 |
|------|---------|------|
| `BeforeCreate` | 创建记录前（校验/预处理） | 前置 |
| `AfterCreate` | 创建记录后（索引/通知） | 后置 |
| `BeforeUpdate` | 更新记录前 | 前置 |
| `AfterUpdate` | 更新记录后 | 后置 |
| `BeforeDelete` | 删除记录前 | 前置 |
| `AfterDelete` | 删除记录后 | 后置 |
| `BeforeFind` | 列表查询前 | 前置 |
| `AfterFind` | 列表查询后 | 后置 |
| `BeforeGet` | 单条查询前 | 前置 |
| `AfterGet` | 单条查询后 | 后置 |
| `BeforeFetch` | 字段加载前 | 前置 |
| `AfterFetch` | 字段加载后（计算字段求值） | 后置 |
| `BeforeFetchAll` | 批量加载字段前 | 前置 |
| `AfterFetchAll` | 批量加载字段后 | 后置 |
| `BeforeSearch` | ES 搜索前 | 前置 |
| `AfterSearch` | ES 搜索后 | 后置 |
| `BeforeSave` | save/upsert 前 | 前置 |
| `AfterSave` | save/upsert 后 | 后置 |
| `BeforeValidator` | 验证器执行前 | 前置 |
| `BeforeIndex` | 索引更新前 | 前置 |

---

## 2. @OnDbEvent — 字段级生命周期钩子

字段类通过 `@OnDbEvent` 注解声明关注的时机，引擎在扫描字段时自动绑定。

```java
// 示例: CreateAtField 内部逻辑
public class CreateAtField extends BaseField {
    @OnDbEvent(DbEventType.BeforeCreate)
    public void setCreateTime(Model model) {
        if (model.get(this.name) == null) {
            model.put(this.name, System.currentTimeMillis());
        }
    }
}

// 示例: UpdateAtField 内部逻辑
public class UpdateAtField extends BaseField {
    @OnDbEvent({DbEventType.BeforeCreate, DbEventType.BeforeSave})
    public void setUpdateTime(Model model) {
        model.put(this.name, System.currentTimeMillis());
    }
}

// 示例: VersionField 逻辑
public class VersionField extends BaseField {
    @OnDbEvent(DbEventType.BeforeSave)
    public void incrementVersion(Model model) {
        Integer current = model.getInteger(this.name);
        model.put(this.name, (current == null) ? 0 : current + 1);
    }
}
```

## 3. @DSEventListener — 集合级事件监听器

在 Bean 上声明监听器，监听特定集合的事件：

```java
import org.dataPilot.common.annotation.DSEventListener;
import org.dataPilot.common.annotation.OnDbEvent;
import org.dataPilot.common.event.DbEventType;

@DSEventListener(engineKey = "default")
public class OrderEventListener {

    @OnDbEvent(DbEventType.AfterCreate)
    public void onOrderCreated(EngineContext<?, ?> ctx, Model model) {
        System.out.println("新订单创建: " + model.getString("id"));
        // 发送通知、更新统计等
    }

    @OnDbEvent(DbEventType.BeforeDelete)
    public void onOrderDeleting(EngineContext<?, ?> ctx, Model model) {
        // 删除前检查: 已支付的订单不允许删除
        String status = model.getString("status");
        if ("PAID".equals(status)) {
            throw new IllegalStateException("已支付订单不可删除");
        }
    }
}
```

## 4. @DbEvent — 多事件声明

在单个方法上声明多个事件类型和集合：

```java
import org.dataPilot.common.annotation.DbEvent;

public class AuditLogger {

    @DbEvent(
        type = DbEventType.AfterUpdate,
        collections = {"orders", "customers", "products"}
    )
    @DbEvent(
        type = DbEventType.AfterDelete,
        collections = {"orders"}
    )
    public void logChanges(DbEvent<Model> event) {
        // event.getModel() — 变更的数据
        // event.getType()  — DbEventType
        // event.getCollectionName() — 集合名
        System.out.println(
            event.getCollectionName() + " " + event.getType() + ": " + event.getModel().toJson()
        );
    }
}
```

## 5. 插件内自动注册事件监听器

`EnginePlugin` 可以自动扫描 `@OnEvent` 和 `@OnDbEvent` 注解：

```java
public interface EnginePlugin<C, U> {
    // 默认实现: 扫描插件类上的 @OnEvent/@OnDbEvent 注解并注册
    default void registerAnnotatedListeners(DataSourceEngine<C, U> engine) {
        // 引擎在 load 阶段自动调用
    }
}
```

---

## 6. 数据变更事件架构 (Pulsar)

### 6.1 变更传播流程

```
DB 写入 (INSERT/UPDATE/DELETE)
  │
  ▼
paas-sql dbaction (数据库层钩子)
  │
  ▼
Pulsar Topic "DBEvent"
  │
  ▼
EngineConsumer (消费分发)
  ├── JooqCacheConsumer    → 更新缓存
  ├── JooqIndexConsumer    → 更新 ES 索引
  └── JooqNotifyConsumer   → 通知订阅者（默认关闭，需显式开启）
       │
       ▼
    DataChangeNotify
       ├── 从 Redis 获取订阅条件 (SubscriberManager)
       ├── Aviator 表达式评估匹配
       └── WebSocket 推送变更通知
```

`JooqNotifyConsumer` 默认不在 consumer 列表中。需要通知订阅者时，Micronaut 配置 `datapilot.enable-jooq-notify-consumer=true`；非 Micronaut 环境调用 `configuration.setEnableJooqNotifyConsumer(true)`。

### 6.2 Pulsar 消息结构 (DBEvent)

```java
// org.paas.sql.common.event.DBEvent
public class DBEvent {
    public DbType dbtype;
    public String dbName;
    public List<DBActionEvent> events;
}

public class DBActionEvent {
    public DBActionMode mode;              // insert / update / delete
    public String tableName;
    public Map<String, Object> fieldValues;
}
```

原始事件采用紧凑载荷：

- insert 只携带主键。
- update 携带主键和发生变化的字段名，变化字段的值是 `Boolean.TRUE` 标记，不是新值。
- 进入无效状态的逻辑删除会转换为 delete 事件；delete 保留删除载荷。
- 原始 DBEvent 不承诺完整 after/oldData。`DataChangeNotify` 对 insert/update 按主键回 DB 加载当前模型后再做条件匹配和 WebSocket 推送。

---

## 7. 数据变更拦截器 (DataChangeRecipientInterceptor)

在 websocket 通知推送前，按业务规则二次裁剪收件人。详细文档见 [datapilot-interceptor](../datapilot-interceptor/SKILL.md)。

快速示例 — 只允许 admin 接收敏感数据变更：

```java
@DSDataChangeRecipientInterceptor(
    engineKey = "default",
    collections = {"sensitive_data"}
)
public class SensitiveDataInterceptor implements DataChangeRecipientInterceptor {

    @Override
    public RecipientDecision intercept(DataChangeDispatchContext context) {
        if (context.getUser().getRoles().contains("admin")) {
            return RecipientDecision.allowUsers(List.of(context.getUser().getId()));
        }
        return RecipientDecision.dropAll();
    }

    @Override
    public int order() {
        return 10;
    }
}
```

---

## 8. WebSocket 事件推送

### 8.1 WebSocket 监听器注解

```java
@WebsocketListener(
    condition = "status == 'active'",
    type = SubscribeType.FILTER
)
public void onDataChange(DataChangeMessage msg) {
    // 仅推送 status=active 的变更
}
```

### 8.2 DataChangeMessage 结构

```json
{
  "pk": 10086,
  "eventId": "evt_xxx",
  "mode": "update",
  "data": { "id": 10086, "status": "SHIPPED" }
}
```

该对象是 WebSocket envelope 的 body。envelope header 使用 `DataChange` channel、collection topic 和订阅表达式 instance，action 使用当前 `DBActionMode` 名称；body 没有通用 `before` 字段。

---

## 9. 前端事件订阅

先通过 HTTP `subscribeChange` 注册过滤条件并取得 `{topic, instance}`，再由部署使用的 WebSocket 客户端监听该 topic/instance。不要通过 WebSocket 自行发送一套未定义的 subscribe body，也不要把 `parseSubscribeMessage` 当作消息解码器。完整 HTTP 契约见 [datapilot-subscribe](../datapilot-subscribe/SKILL.md)。

---

## 10. 自定义事件处理完整示例

```java
import org.dataPilot.DataSourceEngine;
import org.dataPilot.common.annotation.*;
import org.dataPilot.common.event.*;
import paas.auth.Context;
import paas.auth.User;

@Component
@DSEventListener(engineKey = "default")
public class OrderLifecycleHandler {

    @OnDbEvent(DbEventType.BeforeCreate)
    public void validateBeforeCreate(EngineContext<Context<User>, User> ctx, Model model) {
        // 创建前校验
        if (model.getString("customerId") == null) {
            throw new IllegalArgumentException("客户 ID 不能为空");
        }
    }

    @OnDbEvent(DbEventType.AfterCreate)
    public void afterCreate(EngineContext<Context<User>, User> ctx, Model model) {
        // 创建后: 发送通知、更新统计
        notificationService.notify("新订单: " + model.getString("id"));
    }

    @OnDbEvent(DbEventType.AfterUpdate)
    public void afterUpdate(EngineContext<Context<User>, User> ctx, Model model) {
        // 状态变更通知
        String oldStatus = model.getOrigin() != null
            ? model.getOrigin().get("status") : null;
        String newStatus = model.getString("status");
        if (!Objects.equals(oldStatus, newStatus)) {
            log.info("订单 {} 状态: {} → {}",
                model.getString("id"), oldStatus, newStatus);
        }
    }

    @OnDbEvent(DbEventType.AfterDelete)
    public void afterDelete(EngineContext<Context<User>, User> ctx, Model model) {
        log.warn("订单被删除: {}", model.getString("id"));
    }
}
```

---

## 11. DispatchMode 枚举

用于拦截器决策：

```java
PASS, ALLOW_ONLY, DENY_SOME, DROP_ALL
```
