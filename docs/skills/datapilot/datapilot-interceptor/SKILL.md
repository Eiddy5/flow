---
name: datapilot-interceptor
description: |
  DataChangeRecipientInterceptor 数据变更通知收件人拦截器：两段式过滤架构、RecipientDecision 四种决策模式、
  注解/代码/collectionKeys 三种注册方式、dispatch 五种分发行为。Use when the user asks to "拦截数据变更通知",
  "过滤 websocket 推送收件人", "自定义变更通知接收者", "DataChangeRecipientInterceptor", "只发给负责人",
  "RecipientDecision", "DSDataChangeRecipientInterceptor"。
argument-hint: "[collection] [decision]"
allowed-tools: Read Grep Glob
paths: "src/main/java/org/dataPilot/dbaction/notify/interceptor/**"
version: 0.1.0
---

# 数据变更通知收件人拦截器

在 websocket 数据变更通知链路上，按业务规则二次裁剪收件人：已命中订阅分组，但在真正推送前决定最终发给谁。

## When to Use

- "某个表的变更只发给负责人"
- "过滤 websocket 推送的收件人"
- "屏蔽操作本人接收自己的变更通知"
- "自定义 DataChangeRecipientInterceptor"
- "按部门/组织做通知裁剪"
- 编辑路径 `src/main/java/org/dataPilot/dbaction/notify/interceptor/` 下的文件时

## 链路位置

```
数据库变更
  → JooqNotifyConsumer.execute(...)
  → DataChangeNotify.handle(...)
  → 表达式匹配订阅分组
  → DataChangeRecipientInterceptorRegistry.resolve(...)   ← 本拦截器在此
  → 决定 broadcast / 定向发送 / 丢弃
  → DataBus.broadcast(...) 或 DataBus.writeSocketTo(...)
```

拦截器执行时机：**已知道通知命中了哪些订阅者，但还没推送到 websocket**。

前置条件：`JooqNotifyConsumer` 默认不注册。Micronaut 应设置 `datapilot.enable-jooq-notify-consumer=true`，非 Micronaut 环境调用 `configuration.setEnableJooqNotifyConsumer(true)`，否则不会进入通知与拦截器链路。

## 核心接口

### DataChangeRecipientInterceptor

```java
public interface DataChangeRecipientInterceptor {
    // 静态声明所属 collection/topic
    default Collection<String> collectionKeys() { return List.of(); }

    // 动态判断本次消息是否需要此拦截器（应保持轻量）
    boolean supports(DbCollection<?> collection, String topic, String instanceId);

    // 给出收件人裁剪结果（纯决策，不要有副作用）
    RecipientDecision intercept(DataChangeDispatchContext context);

    // 执行顺序，越小越先执行
    default int order() { return 0; }
}
```

### RecipientDecision — 四种决策模式

```java
RecipientDecision.pass()                                // 不做过滤，继续 broadcast
RecipientDecision.dropAll()                             // 丢弃，不发给任何人
RecipientDecision.allowUsers(List<String> userIds)      // 只允许指定用户
RecipientDecision.allowCookies(List<String> cookieIds)  // 只允许指定连接
RecipientDecision.denyUsers(List<String> userIds)       // 剔除指定用户
RecipientDecision.denyCookies(List<String> cookieIds)   // 剔除指定连接
```

**推荐优先使用 `allowUsers/denyUsers`**，因为业务规则通常面向"人"而非某条 websocket 连接。`DataBus` 支持 `SessionType.User` 按用户路由。

### DataChangeDispatchContext — 分发上下文

| 字段 | 说明 |
|------|------|
| `collection` | 当前集合 |
| `topic` | 当前 topic（等同 collection key） |
| `instanceId` | 订阅表达式 |
| `event` | 数据变更事件，含 `getFieldValues()` |
| `candidates` | 当前还存活的候选订阅者集合 |

## 注册方式

### 方式一：Micronaut 注解扫描（推荐）

```java
@DSDataChangeRecipientInterceptor(
    engineKey = "*",
    collections = {"task"}
)
public class TaskOwnerRecipientInterceptor implements DataChangeRecipientInterceptor {
    // ...
}
```

### 方式二：代码手动注册

```java
configuration.registerDataChangeRecipientInterceptor(
    new TaskOwnerRecipientInterceptor(),
    "task"  // 指定 collection，省略则为全局
);
```

### 方式三：覆盖 collectionKeys()

```java
@Override
public Collection<String> collectionKeys() {
    return List.of("task", "order");
}
```

## 两段式过滤

1. **静态分桶**：根据 `collectionKeys` / 注解 `collections`，按 collection 取出候选链（全局桶 + 当前 collection 桶）
2. **动态过滤**：执行 `supports(collection, topic, instanceId)`，只让命中的 interceptor 参与本次 dispatch

## Dispatch 五种分发行为

| 情况 | 行为 |
|------|------|
| 没有命中任何 interceptor | 继续 `broadcast` |
| 命中但结果与原集合一致 | 继续 `broadcast` |
| 命中且只剩部分目标 — 可按用户表达 | `DataBus.writeSocketTo(..., SessionType.User, userIds)` |
| 命中且只剩部分目标 — 需按连接表达 | `DataBus.writeSocketTo(..., SessionType.Cookie, cookieIds)` |
| 所有候选人被过滤掉 / interceptor 抛异常 | 不发送 / 记录日志并 fail-open 放行 |

## 常见使用模式

### 只发给负责人

```java
@Override
public RecipientDecision intercept(DataChangeDispatchContext context) {
    Map<String, Object> row = context.getEvent().getFieldValues();
    String ownerId = (String) row.get("ownerId");
    if (ownerId == null || ownerId.isBlank())
        return RecipientDecision.dropAll();
    return RecipientDecision.allowUsers(List.of(ownerId));
}
```

### 发给负责人 + 创建人

```java
Set<String> users = new LinkedHashSet<>();
users.add(row.get("ownerId"));
users.add(row.get("createBy"));
return RecipientDecision.allowUsers(users);
```

### 剔除操作本人

```java
String operatorId = (String) context.getEvent().getFieldValues().get("updateBy");
return RecipientDecision.denyUsers(List.of(operatorId));
```

### 某种状态直接不发

```java
if ("draft".equals(row.get("status")))
    return RecipientDecision.dropAll();
return RecipientDecision.pass();
```

### 只针对某类 instance 表达式

```java
@Override
public boolean supports(DbCollection<?> collection, String topic, String instanceId) {
    return "task".equals(topic) && instanceId != null && instanceId.contains("ownerId");
}
```

## 最佳实践

| 规则 | 说明 |
|------|------|
| 拆成小而专的 interceptor | 如 `TaskOwnerInterceptor` + `TaskCollaboratorInterceptor`，避免一个超大 interceptor 处理所有逻辑 |
| 静态范围尽量缩小 | 通过注解 `collections`、`collectionKeys()`、注册传参之一声明作用范围，避免大量 interceptor 挂全局桶 |
| `supports()` 只做轻量判断 | topic/instanceId 前缀/action 初筛，不要做 RPC、数据库查询 |
| `intercept()` 只做收件人决策 | 从上下文取业务字段 → 计算目标用户 → 返回决策，不要写库、发事件、触发副作用 |
| 优先按用户裁剪 | `allowUsers/denyUsers` 优于 `allowCookies/denyCookies` |

## 执行顺序

多个 interceptor 按 `order()` 从小到大链式执行，每步都拿到上一步过滤后的 `candidates`：

```
GlobalOrgPermissionInterceptor   order=10   → 组织权限预过滤
TaskOwnerInterceptor             order=100  → 负责人规则
TaskCollaboratorInterceptor      order=200  → 协作者规则
```

## 快速上手模板

```java
import org.dataPilot.db.collection.DbCollection;
import org.dataPilot.dbaction.notify.interceptor.*;
import org.dataPilot.micronaut.annotation.DSDataChangeRecipientInterceptor;
import java.util.*;

@DSDataChangeRecipientInterceptor(
    engineKey = "*",
    collections = {"task"}
)
public class TaskRecipientInterceptor implements DataChangeRecipientInterceptor {

    @Override
    public boolean supports(DbCollection<?> collection, String topic, String instanceId) {
        return "task".equals(topic);
    }

    @Override
    public RecipientDecision intercept(DataChangeDispatchContext context) {
        Map<String, Object> row = context.getEvent().getFieldValues();
        String ownerId = row == null ? null : (String) row.get("ownerId");
        if (ownerId == null)
            return RecipientDecision.pass();
        return RecipientDecision.allowUsers(List.of(ownerId));
    }

    @Override
    public int order() {
        return 100;
    }
}
```

## FAQ

**Q: `topic` 和 `collection` 是什么关系？**
在当前 DataChange 通知链路里，`topic` 基本就是 `collection.getKey()`，因此按 collection 分桶就是按 topic 分桶。

**Q: `instanceId` 是什么？**
当前 `instanceId` 对应订阅表达式 `conditionExpression`，不是固定的业务主键。

**Q: 为什么有时候还是 broadcast？**
兼容策略：没命中 interceptor 或命中后结果无变化时，走 broadcast 避免不必要的定向发送开销。

**Q: 全局兜底权限拦截怎么做？**
写一个不带 `collections` 参数的注解或返回 `List.of("*")` 的 `collectionKeys()`，即为全局 interceptor。

**Q: 没有 interceptor 的表行为会变吗？**
不会。默认完全兼容，只有真正配置了拦截器的表才走定向发送。

## Checklist

- [ ] interceptor 已注册到具体 collection（避免挂全局桶）
- [ ] `enableJooqNotifyConsumer` 已显式开启
- [ ] `supports()` 只做轻量判断，无副作用
- [ ] `intercept()` 只做收件人决策，无副作用
- [ ] 优先使用 `allowUsers/denyUsers` 而非 `allowCookies/denyCookies`
- [ ] 多个 interceptor 时 `order()` 已正确设置
