---
name: datapilot-window
description: |
  写入窗口事务与行锁操作指南。Use when the user asks to "acquire window",
  "execute window", "revoke operate", "clear window", "lock row", "unlock row",
  "写入窗口", "批量事务提交", or works with files under
  `src/main/java/org/dataPilot/manager/**` 涉及 CollaborateService / DataLockModel /
  ExecuteWindowResult. Scope: 仅覆盖 window 事务批处理与行级锁契约；不涉及 CRUD 字段映射、
  查询 DSL、索引重建等其他 skill 的领域。
argument-hint: "[acquire|execute|clear|revoke|lock|unlock]"
allowed-tools: Read Grep Glob
paths: "src/main/java/org/dataPilot/manager/**"
version: 0.1.0
---

# DataPilot Write Window & Lock Skill

用于在 cloud-datapilot 中以原子方式批处理多步写入（create / update / delete）以及获取/释放行级锁。

## When to Use

- "我需要一次性写多条记录，要么全成功要么全回滚"
- "如何在执行多步修改前拿到 windowId"
- "怎么撤销某一步 operate，而不是整体 rollback"
- "编辑某行前如何加锁防止并发冲突"
- "executeWindow 中途失败怎么办"
- 编辑路径 `src/main/java/org/dataPilot/manager/**`、`CollaborateService`、`DataLockModel` 相关代码时

## Concept

**写入窗口 (Write Window)** 是 DataPilot 的事务抽象：调用 `acquireWindowId` 领取一个 `windowId`，
随后所有 `create` / `update` / `delete` 调用都携带该 windowId，变更被缓冲在窗口内而非直接落库。
直到调用 `executeWindow` 才按顺序原子提交；调用 `clearWindow` 则整体丢弃缓冲内容。
每一步 operate 会产生一个 `logId`，可通过 `revokeOperate` 针对单步精细回滚。

**行锁 (Row Lock)** 与窗口正交：`lock` / `unlock` 对特定记录主键加/解互斥锁，
防止其它事务并发修改同一行；锁冲突抛 `DataLockExistException`。

窗口生命周期：`acquireWindowId` → N × mutate(windowId) → `executeWindow` | `clearWindow`。
窗口不跨进程共享，服务重启后未 execute 的 windowId 失效。

## HTTP Endpoints

所有路径以 `/{datasource}/{collection}` 为前缀。

### Window Transactions

| Method | Path | Query / Body | Response | 说明 |
|---|---|---|---|---|
| POST | `/acquireWindowId` | — | `JsonObject{"windowId": string}` | 领取新 windowId |
| POST | `/executeWindow` | `windowId` (query) | `ExecuteWindowResult` | 原子提交窗口内全部变更 |
| POST | `/clearWindow` | `windowId` (query) | `JsonObject.Success()` | 丢弃窗口内全部缓冲变更 |
| POST | `/revokeOperate` | `windowId`, `logId` (query) | `JsonObject.Success()` | 按 logId 撤销单步 operate |

窗口期间的写入接口：`POST /create`、`POST /update`、`POST /delete` 均接受 `windowId` query 参数，
携带时变更进入窗口；不携带则直接落库。

### Locking

| Method | Path | Body | Response | 说明 |
|---|---|---|---|---|
| POST | `/lock` | `LockRequest` | `DataLockModel` | 获取行锁 |
| POST | `/unlock` | `LockRequest` | `DataLockModel` | 释放行锁 |

## DTO Field Tables

### LockRequest

`org.dataPilot.repository.request.LockRequest`

| Field | Type | 说明 |
|---|---|---|
| `datasource` | `String` | 数据源 key |
| `collection` | `String` | 集合 / 表名 |
| `pkMap` | `Map<String,Object>` | 目标记录主键（单主键或复合主键） |
| `lockType` | `String` | 锁类型，如 `ROW`、`TABLE` |
| `lockTimeout` | `Long` | 锁超时毫秒数；到期自动释放 |

### DataLockModel

`org.dataPilot.manager.model.DataLockModel extends SerializableObject`

| Field | Type | 说明 |
|---|---|---|
| `key` | `Object` | 被锁记录主键 |
| `dataSourceKey` | `String` | 数据源 ID |
| `tableName` | `String` | 集合名 |
| `field` | `String` | 可选锁定字段（字段级锁） |
| `user` | `User` | 持锁用户 |
| `lockTime` | `Long` | 加锁时间戳（ms） |

### ExecuteWindowResult

`org.dataPilot.manager.model.ExecuteWindowResult`

| Field | Type | 说明 |
|---|---|---|
| `windowId` | `String` | 已提交的窗口 ID |
| `totalLogs` | `int` | 窗口内 operate 数量 |
| `message` | `String` | 状态信息 |
| `logsIds` | `List<String>` | 每步 operate 的 logId（顺序对应提交顺序） |
| `user` | `User` | 提交者 |
| `lastExecutedTime` | `Long` | 提交完成时间戳 |

静态工厂：`ExecuteWindowResult.Of(String message)`。

## Workflow

1. 根据本文件确认 `CollaborateService`、`DataLockModel` 与 `ExecuteWindowResult` 契约
2. 按场景选择以下示例对应的调用序列
3. 在引擎内嵌场景中，走 `DataSourceEngine.collaborateService` 同名方法而非 HTTP

## Examples

### 示例 1：批量写入原子提交（2 create + 1 update → execute）

```bash
# 1. 领取窗口
curl -XPOST /orders/order/acquireWindowId
# => {"windowId": "w-20260407-001"}

# 2. 多步写入，全部携带 windowId
curl -XPOST "/orders/order/create?windowId=w-20260407-001" \
  -d '{"record":{"orderNo":"A001","amount":100}}'
curl -XPOST "/orders/order/create?windowId=w-20260407-001" \
  -d '{"record":{"orderNo":"A002","amount":200}}'
curl -XPOST "/orders/order/update?windowId=w-20260407-001" \
  -d '{"pkMap":{"id":"x"},"record":{"status":"PAID"}}'

# 3. 原子提交
curl -XPOST "/orders/order/executeWindow?windowId=w-20260407-001"
# => ExecuteWindowResult{ totalLogs: 3, logsIds: ["l1","l2","l3"], ... }
```

### 示例 2：放弃窗口（clearWindow）

```bash
curl -XPOST /orders/order/acquireWindowId           # w-abandon
curl -XPOST "/orders/order/create?windowId=w-abandon" -d '...'
# 业务决策改变，丢弃
curl -XPOST "/orders/order/clearWindow?windowId=w-abandon"
```

`clearWindow` 之后该 windowId 失效，后续任何携带该 id 的写入应视为错误。

### 示例 3：按 logId 撤销单步 operate

```bash
curl -XPOST /orders/order/acquireWindowId              # w-partial
curl -XPOST "/orders/order/create?windowId=w-partial" -d '{"record":{"orderNo":"B001"}}'
# 执行提交
curl -XPOST "/orders/order/executeWindow?windowId=w-partial"
# => logsIds: ["log-create-b001"]

# 业务发现 B001 需撤销，单独回滚该步
curl -XPOST "/orders/order/revokeOperate?windowId=w-partial&logId=log-create-b001"
```

`revokeOperate` 针对已 execute 后的补偿回滚，通过 `logId` 精确定位目标 operate。

### 示例 4：显式行锁保护的写入

```bash
# 1. 加锁
curl -XPOST /orders/order/lock -d '{
  "datasource":"orders","collection":"order",
  "pkMap":{"id":"ORD-42"},
  "lockType":"ROW","lockTimeout":30000
}'
# => DataLockModel{ key:"ORD-42", user:..., lockTime:... }

# 2. 受保护地修改
curl -XPOST /orders/order/update -d '{"pkMap":{"id":"ORD-42"},"record":{"status":"LOCKED_EDIT"}}'

# 3. 解锁
curl -XPOST /orders/order/unlock -d '{
  "datasource":"orders","collection":"order",
  "pkMap":{"id":"ORD-42"},"lockType":"ROW"
}'
```

若另一会话对同一 `pkMap` 再次 `lock`，服务端抛 `DataLockExistException`。

## Java Engine Embedding

进程内嵌入（非 HTTP）时，复用同一 `DataSourceEngine` 的 `collaborateService` 与 CRUD 方法。
在 `EngineExecutor` 模板中，windowId 作为参数在多步调用间传递：

```java
// engine: DataSourceEngine<C, U>
String windowId = engine.collaborateService.createWindow(ctx, "orders", "order");
try {
    engine.create(ctx, "orders", "order", windowId, true, createReqA);
    engine.create(ctx, "orders", "order", windowId, true, createReqB);
    engine.update(ctx, "orders", "order", windowId, true, updateReq);

    ExecuteWindowResult result =
        engine.collaborateService.executeWindow(ctx, "orders", "order", windowId);
    // result.logsIds 可保存用于后续 revokeOperator
} catch (DataLockExistException lockEx) {
    engine.collaborateService.clearWindow(ctx, windowId);
    throw lockEx;                                 // 交上层重试
} catch (RuntimeException ex) {
    engine.collaborateService.clearWindow(ctx, windowId);
    throw ex;
}

// 精细回滚某步
engine.collaborateService.revokeOperator(ctx, windowId, logId);

// 独立行锁
DataLockModel lock = engine.lock(ctx, "orders", "order", lockRequest);
try {
    engine.update(ctx, "orders", "order", null, true, updateReq);
} finally {
    engine.unlock(ctx, "orders", "order", lockRequest);
}
```

注意方法名差异：HTTP 为 `revokeOperate`，服务类为 `revokeOperator`；
HTTP 为 `acquireWindowId`，服务类为 `createWindow`。契约语义一致。

## Checklist

- [ ] 已确认 `CollaborateService` 调用入口和窗口生命周期
- [ ] 每个窗口都有配对的 `executeWindow` 或 `clearWindow`，无悬挂 windowId
- [ ] 写入接口 (`create`/`update`/`delete`) 的 `windowId` 参数显式传递（或显式传 null 表示直写）
- [ ] `LockRequest.pkMap` 与集合主键定义一致
- [ ] `lock` 与 `unlock` 在 `try/finally` 中成对出现
- [ ] 捕获 `DataLockExistException` 与 `VersionConflictException` 后做清理或重试
- [ ] `ExecuteWindowResult.logsIds` 需要持久化的场景已保存，便于后续 `revokeOperate`

## Common Mistakes

| Mistake | Fix |
|---|---|
| 领取 windowId 后既不 execute 也不 clear，造成资源挂起 | 使用 `try/finally`，异常路径一律 `clearWindow` |
| 误把 `revokeOperate` 当作整体 rollback | 单步补偿用 `revokeOperate(logId)`；整体放弃用 `clearWindow`（execute 前）|
| 并发线程共用同一 windowId | 每个逻辑事务独立 `acquireWindowId`，windowId 不共享 |
| 捕获 `DataLockExistException` 后继续 retry 不退避 | 指数退避或失败上抛，避免活锁 |
| `executeWindow` 中途失败后继续下发 operate | 视窗口为已失效，重新 `acquireWindowId` 并重放剩余 operate |
| `VersionConflictException`（乐观锁）被当普通异常忽略 | 重新读取最新记录，合并变更后重新进入新窗口 |
| HTTP 使用 `revokeOperator`，服务类使用 `revokeOperate` | 以对应层实际方法名为准：HTTP=`revokeOperate`，Service=`revokeOperator` |
| 对未 execute 的窗口调用 `revokeOperate` | `revokeOperate` 仅对已提交的 logId 生效，未 execute 请用 `clearWindow` |
| 忽略 `LockRequest.lockTimeout` 导致锁永不释放 | 始终设置合理 `lockTimeout`；并在业务结束显式 `unlock` |
