---
name: datapilot-lock
description: |
  cloud-datapilot 协作与锁定：Redis 行级锁、协作在场、CollectionLockManager 集合级元数据锁、
  窗口执行 Redisson 分布式锁、MetaChangeException 处理。Use when the user asks to
  "行锁", "lock row", "unlock", "锁定", "collaboration", "presence", "并发控制",
  "CollaborateService", "LockService".
argument-hint: "[lock|unlock] [datasource] [collection]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.java"
version: 1.0.0
---

# 协作与锁定

面向 SDK 消费者，覆盖 cloud-datapilot 的行级锁定、协作在场、集合级元数据锁、窗口执行锁等并发控制机制。

## 1. 行级锁定 (Redis-based)

基于 Redis 的分布式行锁，防止多用户同时编辑同一行。

### 1.1 Java API

```java
import org.dataPilot.service.CollaborateService;
import org.dataPilot.service.LockRequest;
import org.dataPilot.service.DataLockModel;

CollaborateService collab = dataSourceService.collaborateService;

// 锁定一行
DataLockModel lock = collab.lock(ctx, "salesDb", "orders",
    new LockRequest(10086L, "editing_field"));

// 解锁一行
DataLockModel unlock = collab.unlock(ctx, "salesDb", "orders",
    new LockRequest(10086L, "editing_field"));

// 批量锁定
LockService lockService = ...;
List<DataLockModel> locks = lockService.lock(ctx, "salesDb", "orders",
    List.of(10086L, 10087L, 10088L), "batch_edit");
```

### 1.2 HTTP API

```bash
# 锁定
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/lock' \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{ "keyValue": 10086, "field": "editing_field" }'
# → { "id": "lock_xxx", "keyValue": 10086, "field": "editing_field", "lockedBy": {... } }

# 解锁
curl -X POST 'http://datapilot.local/dataPilot/salesDb/orders/unlock' \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{ "keyValue": 10086, "field": "editing_field" }'
```

### 1.3 前端使用

```typescript
// 进入编辑页面 → 锁定行
async function startEditing(orderId: number) {
  try {
    await request('salesDb', 'orders', 'lock', {
      keyValue: orderId,
      field: 'editing'
    })
    // 锁定成功，进入编辑模式
  } catch (e) {
    // 409 DataLockExistException → 提示用户数据被他人锁定
    ElMessage.warning('该订单正在被其他用户编辑')
  }
}

// 离开编辑页面 → 解锁行
async function stopEditing(orderId: number) {
  await request('salesDb', 'orders', 'unlock', {
    keyValue: orderId,
    field: 'editing'
  })
}
```

---

## 2. DataLockExistException

当行已被其他用户锁定时，锁操作抛出此异常。

### 2.1 Java 处理

```java
try {
    collab.lock(ctx, "salesDb", "orders", new LockRequest(10086L, "edit"));
} catch (DataLockExistException e) {
    // HTTP 409 Conflict
    throw new HttpStatusException(409, "该数据正在被 " + e.getLockedBy() + " 编辑");
}
```

### 2.2 HTTP 响应

```http
HTTP/1.1 409 Conflict
Content-Type: application/json

{
  "error": "DataLockExistException",
  "message": "该数据已被锁定",
  "lockedBy": { "userId": "user_123", "userName": "张三" }
}
```

---

## 3. VersionConflictException (乐观锁)

当字段配置了 `VersionField` 时，更新操作会检查版本号。

### 3.1 冲突处理

```java
try {
    engine.buildUpdater(ctx, "salesDb", "orders")
          .eq("id", 10086)
          .update(Map.of("status", "SHIPPED"));
} catch (VersionConflictException e) {
    // 1. 重新读取最新数据
    Model latest = engine.buildQuery(ctx, "salesDb", "orders")
        .filter(QueryCondition.Eq("id", 10086))
        .single();
    // 2. 合并用户变更
    latest.put("status", "SHIPPED");
    // 3. 重新提交 (携带新的 version)
    engine.buildUpdater(ctx, "salesDb", "orders")
          .eq("id", 10086)
          .save(latest);
}
```

---

## 4. 协作在场 (Presence)

### 4.1 Java API

```java
import org.dataPilot.service.CollaborationManager;

CollaborationManager collabManager = ...;

// 用户加入协作
collabManager.join(ctx, collection);
// 广播 CollaborationNotify 事件

// 获取当前协作人员
List<User> collaborators = collabManager.getCollaborators(collection);

// 用户退出协作
collabManager.quit(ctx, collection);
```

---

## 5. CollectionLockManager (JVM 级集合元数据锁)

防止集合配置在修改时被并发读写。

### 5.1 自动使用 (Repository 层)

```java
// LockRepositoryWrap 包装器自动对 CRUD 操作加读锁
// 如果集合配置正在变更 → 抛出 MetaChangeException

// 手动使用
CollectionLockManager locks = new CollectionLockManager();
try {
    locks.lockRead(collection, createRequest);  // 对当前集合 + 关联集合加读锁
    // ... 执行 CRUD ...
} finally {
    locks.release();
}
```

---

## 6. MetaChangeException

### 6.1 触发条件

- 集合 schema (字段定义、关联配置) 正在被修改
- 内存中的集合元数据与数据库不一致

### 6.2 处理方式

```java
try {
    engine.buildQuery(ctx, "salesDb", "orders").list();
} catch (MetaChangeException e) {
    // 刷新集合元数据
    engine.refreshCollection("salesDb", "orders");
    // 重试操作
    engine.buildQuery(ctx, "salesDb", "orders").list();
}
```

---

## 7. 窗口执行锁

`executeWindow` 内部使用 Redisson `RLock` 防止同一窗口并发执行：

```java
// 内部实现 (CollaborateService.executeWindow)
RLock rLock = redissonClient.getLock(windowId);
rLock.lock();
try {
    // 合并操作 → 执行事务 → 更新日志状态
} finally {
    rLock.unlock();
}
```

**无需使用者关注**，引擎自动处理。

---

## 8. 完整场景示例

```java
// 编辑并发控制完整流程
public class EditFlow {
    private final CollaborateService collab;
    private final DataSourceEngine<Context<User>, User> engine;

    public Model safeEdit(Context<User> ctx, String ds, String col,
                          Long recordId, Map<String, Object> changes) {
        // 1. 尝试锁定
        try {
            collab.lock(ctx, ds, col, new LockRequest(recordId, "edit"));
        } catch (DataLockExistException e) {
            throw new HttpStatusException(409, "数据被他人锁定");
        }

        try {
            // 2. 在事务中更新
            return engine.useExecutor(ctx).executeResult(ec -> {
                // 先读取 (获取最新 version)
                Model latest = engine.buildQuery(ctx, ds, col)
                    .filter(QueryCondition.Eq("id", recordId))
                    .single();

                // 合并变更
                changes.forEach(latest::put);

                // save (乐观锁检查)
                engine.buildUpdater(ctx, ds, col)
                      .save(latest);

                return latest;
            });
        } catch (VersionConflictException e) {
            throw new HttpStatusException(409, "数据已被他人修改，请刷新");
        } finally {
            // 3. 解锁
            collab.unlock(ctx, ds, col, new LockRequest(recordId, "edit"));
        }
    }
}
```

---

## 9. 常见陷阱

| 陷阱 | 正确做法 |
|------|---------|
| 锁定后忘记解锁 | `try { lock → work } finally { unlock }` |
| 锁冲突不处理 | 捕获 `DataLockExistException` 返回 409 + 提示 |
| 乐观锁冲突不重试 | 捕获 `VersionConflictException` 后重新读取合并 |
| MetaChangeException 不刷新 | 刷新集合元数据后重试 |
| 长时间锁定 | 设置锁超时，避免死锁 |
