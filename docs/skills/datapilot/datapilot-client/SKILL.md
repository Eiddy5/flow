---
name: datapilot-client
description: |
  前端调用 cloud-datapilot SDK 完整指南：HTTP REST 接口、WebSocket 订阅、useCamel 驼峰转换、
  认证鉴权、响应类型、错误处理、分页排序过滤。Use when the user asks to "前端调用 datapilot",
  "call datapilot API from frontend", "vue/react 调用 datapilot", "WebSocket subscribe".
argument-hint: "[endpoint] [datasource] [collection]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.{java,ts,vue,js}"
version: 1.1.0
---

# 前端调用 cloud-datapilot 指南

面向前端开发者，通过 HTTP REST 和 WebSocket 两种方式调用 cloud-datapilot 数据引擎。

## 1. 基础配置

### 1.1 通用请求头

```http
POST /dataPilot/{datasource}/{collection}/{action}
Authorization: Bearer <token>
Content-Type: application/json
```

### 1.2 基础 URL

开发环境通常是 `http://<host>:<port>`，所有 datapilot 端点统一以 `/dataPilot` 为前缀。

### 1.3 驼峰转换 (useCamel)

绝大多数前端项目使用 camelCase 命名，而数据库字段通常是 snake_case。**强烈建议所有请求统一携带 `?useCamel=true`**：

```typescript
// ❌ 不带 useCamel，响应是 snake_case
GET /dataPilot/salesDb/orders/get
→ { "customer_id": 42, "order_no": "ORD-001" }

// ✅ 带 useCamel=true，自动转 camelCase
GET /dataPilot/salesDb/orders/get?useCamel=true
→ { "customerId": 42, "orderNo": "ORD-001" }
```

**适用所有 CRUD 端点**: `get`, `list`, `count`, `create`, `batchCreate`, `update`, `save`, `batchSave`

### 1.4 TypeScript 请求封装

```typescript
// api/datapilot.ts
const BASE = '/dataPilot'

interface DatapilotResponse<T> {
  items?: T[]
  total?: number
  [key: string]: any
}

async function request<T>(
  datasource: string,
  collection: string,
  action: string,
  body: Record<string, any>,
  useCamel = true
): Promise<T> {
  const url = `${BASE}/${datasource}/${collection}/${action}${useCamel ? '?useCamel=true' : ''}`
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getToken()}`
    },
    body: JSON.stringify(body)
  })
  if (!res.ok) {
    throw new DatapilotError(res.status, await res.text())
  }
  return res.json()
}
```

---

## 2. HTTP CRUD 操作

### 2.1 查询单条 (get)

```typescript
// 按主键取一条记录
const order = await request<Order>('salesDb', 'orders', 'get', {
  filter: { fields: { id: { $eq: 10086 } } },
  findAssociations: ['customer']  // 预加载关联
})

// 按条件取一条
const user = await request<User>('main', 'users', 'get', {
  filter: { fields: { email: { $eq: 'alice@example.com' } } }
})
```

### 2.2 分页列表 (list)

```typescript
interface ListParams {
  filter?: Record<string, any>
  sorts?: { items: { field: string; order: 'asc' | 'desc' }[] }
  pageNumber?: number  // 0 基
  pageSize?: number
  fields?: string[]
  findAssociations?: string[]
  needTotalCount?: boolean
  useEs?: boolean
}

interface ListResult<T> {
  items: T[]
  total: number  // 总记录数
}

const result = await request<ListResult<Order>>('salesDb', 'orders', 'list', {
  filter: {
    and: [
      { fields: { status: { $in: ['PENDING', 'SHIPPED'] } } },
      { fields: { amount: { $gte: 50 } } }
    ]
  },
  sorts: { items: [{ field: 'createdAt', order: 'desc' }] },
  pageNumber: 0,
  pageSize: 20,
  fields: ['id', 'customerId', 'amount', 'status', 'customer.id', 'customer.name'],
  findAssociations: ['customer'],
  needTotalCount: true,
  useEs: true  // 优先走 Elasticsearch
})
```

### 2.3 计数 (count)

```typescript
const count = await request<number>('salesDb', 'orders', 'count', {
  filter: { fields: { status: { $eq: 'PENDING' } } }
})
// → 137
```

### 2.4 创建 (create)

```typescript
// 单条创建
const newOrder = await request<Order>('salesDb', 'orders', 'create', {
  values: {
    customerId: 42,
    amount: 299.00,
    status: 'PENDING'
  },
  whiteColumns: ['customerId', 'amount', 'status']
})
// → { id: '10086', customerId: 42, amount: 299.00, status: 'PENDING', ... }

// 批量创建
const orders = await request<Order[]>('salesDb', 'orders', 'batchCreate', {
  batchValues: [
    { customerId: 1, amount: 10.00 },
    { customerId: 2, amount: 20.00 },
    { customerId: 3, amount: 30.00 }
  ]
})
```

### 2.5 更新 (update)

```typescript
// 按条件更新
await request('salesDb', 'orders', 'update', {
  filter: { fields: { status: { $eq: 'PAID' } } },
  values: { status: 'SHIPPED' },
  whiteColumns: ['status'],
  // operators: { retryCount: { type: 'add', number: 1 } }  // 字段自增
})

// 按主键更新 (推荐: 使用 filter.id)
await request('salesDb', 'orders', 'update', {
  filter: { fields: { id: { $eq: 10086 } } },
  values: { status: 'SHIPPED', amount: 399.00 },
  whiteColumns: ['status', 'amount']
})
```

### 2.6 单条 Upsert (save)

```typescript
// 有 id 则更新，无 id 则创建
const saved = await request<Order>('salesDb', 'orders', 'save', {
  values: { id: '10086', status: 'SHIPPED', amount: 399.00 },
  whiteColumns: ['status', 'amount']
})
```

### 2.7 批量 Upsert (batchSave)

```typescript
const savedList = await request<Order[]>('salesDb', 'orders', 'batchSave', {
  batchValues: [
    { id: '10086', status: 'SHIPPED' },
    { customerId: 99, amount: 99.00 }  // 无 id → 创建
  ],
  whiteColumns: ['status', 'amount', 'customerId']
})
```

### 2.8 删除 (delete)

```typescript
// ⚠️ 必须设置 deleteConfirm: true，否则不生效
await request('salesDb', 'orders', 'delete', {
  filter: { fields: { id: { $eq: 10087 } } },
  deleteConfirm: true
})
// → { success: true }
```

---

## 3. 过滤条件 JSON 格式

### 3.1 运算符速查

| 运算符 | JSON Key | 示例 |
|--------|----------|------|
| 等于 | `$eq` | `{ "status": { "$eq": "active" } }` |
| 不等于 | `$ne` | `{ "status": { "$ne": "deleted" } }` |
| 大于 | `$gt` | `{ "amount": { "$gt": 100 } }` |
| 大于等于 | `$gte` | `{ "amount": { "$gte": 100 } }` |
| 小于 | `$lt` | `{ "amount": { "$lt": 1000 } }` |
| 小于等于 | `$lte` | `{ "amount": { "$lte": 1000 } }` |
| 在集合中 | `$in` | `{ "status": { "$in": ["A","B","C"] } }` |
| 不在集合中| `$nin` | `{ "status": { "$nin": ["X","Y"] } }` |
| 范围 | `$range` | `{ "age": { "$range": [18, 65] } }` |
| LIKE | `$like` | `{ "name": { "$like": "张%" } }` |
| 关键词 | `$keyword` | `{ "_keyword": { "$keyword": "搜索词" } }` |
| 正则 | `$regex` | `{ "code": { "$regex": "^[A-Z]{2}\\d+" } }` |
| IS NULL | `$null` | `{ "deletedAt": { "$null": true } }` |
| IS NOT NULL | `$notNull` | `{ "email": { "$notNull": true } }` |
| BETWEEN | `$between` | `{ "date": { "$between": ["2025-01-01","2025-12-31"] } }` |
| NOT BETWEEN| `$notBetween` | `{ "score": { "$notBetween": [0, 60] } }` |
| 前缀 | `$startsWith` | `{ "name": { "$startsWith": "A" } }` |
| 后缀 | `$endsWith` | `{ "email": { "$endsWith": "@qq.com" } }` |
| 包含 | `$contains` | `{ "desc": { "$contains": "重要" } }` |

### 3.2 AND/OR 组合

```typescript
// AND: 所有条件为真
{
  filter: {
    and: [
      { fields: { status: { $eq: 'active' } } },
      { fields: { amount: { $gt: 100 } } }
    ]
  }
}

// OR: 任一条件为真
{
  filter: {
    or: [
      { fields: { status: { $eq: 'expired' } } },
      { fields: { status: { $eq: 'cancelled' } } }
    ]
  }
}

// 嵌套: region='CN' AND (status='A' OR status='B')
{
  filter: {
    and: [
      { fields: { region: { $eq: 'CN' } } },
      {
        or: [
          { fields: { status: { $eq: 'A' } } },
          { fields: { status: { $eq: 'B' } } }
        ]
      }
    ]
  }
}
```

### 3.3 字段间比较 ($expr)

```typescript
// field_a > field_b (字段间比较)
{ fields: { 'field_a': { $expr: { $gt: { field: 'field_b' } } } } }
```

### 3.4 空查询

```typescript
// 查询全部 (不传 filter 或空 filter)
{ pageSize: 20, pageNumber: 0 }
```

---

## 4. Vue 3 集成示例

### 4.1 Composable: useDatapilot

```typescript
// composables/useDatapilot.ts
import { ref } from 'vue'

interface UseDatapilotOptions {
  datasource: string
  collection: string
  useCamel?: boolean
}

export function useDatapilot(options: UseDatapilotOptions) {
  const { datasource, collection, useCamel = true } = options
  const loading = ref(false)
  const error = ref<string | null>(null)

  const suffix = useCamel ? '?useCamel=true' : ''

  async function call<T>(action: string, body: Record<string, any>): Promise<T> {
    loading.value = true
    error.value = null
    try {
      const res = await fetch(`/dataPilot/${datasource}/${collection}/${action}${suffix}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${getToken()}`
        },
        body: JSON.stringify(body)
      })
      if (!res.ok) {
        const msg = await res.text()
        throw new Error(`[${res.status}] ${msg}`)
      }
      return res.json()
    } catch (e: any) {
      error.value = e.message
      throw e
    } finally {
      loading.value = false
    }
  }

  function get(filter: Record<string, any>) {
    return call('get', { filter })
  }

  function list(params: ListParams) {
    return call('list', params)
  }

  async function create(values: Record<string, any>, whiteColumns?: string[]) {
    return call('create', { values, whiteColumns })
  }

  async function update(filter: Record<string, any>, values: Record<string, any>, whiteColumns?: string[]) {
    return call('update', { filter, values, whiteColumns })
  }

  async function save(values: Record<string, any>, whiteColumns?: string[]) {
    return call('save', { values, whiteColumns })
  }

  async function batchCreate(batchValues: Record<string, any>[]) {
    return call('batchCreate', { batchValues })
  }

  async function batchSave(batchValues: Record<string, any>[]) {
    return call('batchSave', { batchValues })
  }

  async function remove(filter: Record<string, any>) {
    return call('delete', { filter, deleteConfirm: true })
  }

  async function count(filter?: Record<string, any>) {
    return call<number>('count', { filter })
  }

  return { loading, error, get, list, create, update, save, batchCreate, batchSave, remove, count }
}
```

### 4.2 列表页示例

```vue
<script setup lang="ts">
import { useDatapilot } from '@/composables/useDatapilot'
import { ref, onMounted, watch } from 'vue'

const { loading, list: queryList } = useDatapilot({ datasource: 'salesDb', collection: 'orders' })

const orders = ref<Order[]>([])
const total = ref(0)
const page = ref(0)
const pageSize = ref(20)
const keyword = ref('')

async function fetchOrders() {
  const res = await queryList({
    filter: keyword.value
      ? { fields: { _keyword: { $keyword: keyword.value } } }
      : undefined,
    sorts: { items: [{ field: 'createdAt', order: 'desc' }] },
    pageNumber: page.value,
    pageSize: pageSize.value,
    findAssociations: ['customer'],
    fields: ['id', 'customerId', 'amount', 'status', 'customer.id', 'customer.name'],
    needTotalCount: true
  })
  orders.value = res.items
  total.value = res.total
}

onMounted(fetchOrders)
watch(page, fetchOrders)
</script>
```

### 4.3 表单提交示例

```vue
<script setup lang="ts">
import { useDatapilot } from '@/composables/useDatapilot'

const { create, update, save } = useDatapilot({ datasource: 'salesDb', collection: 'orders' })

async function handleSubmit(form: OrderForm) {
  if (form.id) {
    // 编辑 → save
    await save({
      id: form.id,
      status: form.status,
      amount: form.amount
    }, ['status', 'amount'])
  } else {
    // 新建 → create
    await create({
      customerId: form.customerId,
      amount: form.amount,
      status: 'PENDING'
    }, ['customerId', 'amount', 'status'])
  }
}
</script>
```

---

## 5. React 集成示例

```typescript
// hooks/useDatapilot.ts
function useDatapilot(datasource: string, collection: string) {
  const [loading, setLoading] = useState(false)
  const base = `/dataPilot/${datasource}/${collection}`

  const call = useCallback(async <T>(action: string, body: any): Promise<T> => {
    setLoading(true)
    const res = await fetch(`${base}/${action}?useCamel=true`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${getToken()}`
      },
      body: JSON.stringify(body)
    })
    setLoading(false)
    if (!res.ok) throw new Error(await res.text())
    return res.json()
  }, [base])

  const list = (params: ListParams) => call<{ items: any[]; total: number }>('list', params)
  const get = (filter: any) => call('get', { filter })
  const create = (values: any, white?: string[]) => call('create', { values, whiteColumns: white })
  const update = (filter: any, values: any, white?: string[]) => call('update', { filter, values, whiteColumns: white })
  const remove = (filter: any) => call('delete', { filter, deleteConfirm: true })
  const save = (values: any, white?: string[]) => call('save', { values, whiteColumns: white })

  return { loading, list, get, create, update, remove, save }
}
```

---

## 6. WebSocket 订阅

### 6.1 先通过 HTTP 注册

```typescript
interface SubscriptionMetadata {
  topic: string
  instance: string
}

async function registerDataChange(
  datasource: string,
  collection: string,
  filter: Record<string, any>
): Promise<SubscriptionMetadata> {
  const response = await fetch(
    `/dataPilot/${datasource}/${collection}/subscribeChange`,
    {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({filter})
    }
  )
  if (!response.ok) throw new Error(`subscribe failed: ${response.status}`)
  return response.json()
}

const metadata = await registerDataChange('salesDb', 'orders', {
  fields: {customerId: {$eq: 42}}
})
// metadata = {topic, instance}
```

客户端不提交 `topic` 或 `subscriptionId`。`parseSubscribeMessage` 也不是消息 decoder，它只预计算同样的 `{topic, instance}` 且不保存订阅。服务端要显式开启 `datapilot.enable-jooq-notify-consumer=true` 才会产生 DB 变更推送。

### 6.2 使用部署提供的 WebSocket adapter

WebSocket 连接地址和订阅帧属于 paas/websocket 部署契约，不由 DataPilot controller 定义。将 HTTP 返回的 channel/topic/instance 交给项目现有 adapter，不要自行发送一套 `{action: "subscribe", datasourceKey, collection}` 协议：

```vue
<script setup lang="ts">
import { inject, onMounted, onUnmounted, ref } from 'vue'

interface DataChangeEvent {
  pk: unknown
  eventId: string
  mode: string
  data: Record<string, unknown>
}

interface DataChangeSocketAdapter {
  subscribe(
    channel: string,
    topic: string,
    instance: string,
    handler: (event: DataChangeEvent) => void
  ): () => void
}

const socket = inject<DataChangeSocketAdapter>('dataChangeSocket')!
const notifications = ref<DataChangeEvent[]>([])
let unsubscribe = () => {}

onMounted(async () => {
  const {topic, instance} = await registerDataChange('salesDb', 'orders', {
    fields: {customerId: {$eq: 42}}
  })
  unsubscribe = socket.subscribe('DataChange', topic, instance, event => {
    notifications.value.unshift(event)
  })
})

onUnmounted(() => {
  unsubscribe()
})
</script>
```

推送 body 是 `DataChangeMessage`：`pk / eventId / mode / data`，没有通用的 `before` 字段。完整接口与清理订阅方式见 [datapilot-subscribe](../datapilot-subscribe/SKILL.md)。

---

## 7. 排序 (QuerySorts)

```typescript
// 排序格式
{
  sorts: {
    items: [
      { field: 'createdAt', order: 'desc' },   // 创建时间降序
      { field: 'amount', order: 'asc' },        // 金额升序
    ]
  }
}

// JSONB 子字段排序 (带类型转换)
{
  sorts: {
    items: [
      { field: "metadata->>'priority'", jsonbValueType: 'INTEGER', order: 'desc' }
    ]
  }
}

// jsonbValueType 可选值: STRING, INTEGER, LONG, DOUBLE, BIG_DECIMAL, BOOLEAN, DATE
```

---

## 8. 窗口事务

```typescript
// 窗口事务适用于前端分步操作场景 (表单多步骤填写)
const WIN = '/dataPilot/salesDb/orders'

// 1. 创建窗口
const { windowId } = await request(WIN, '', 'acquireWindowId', {})

// 2. 窗口内操作 (可多次)
await request(WIN, '', 'create', { values: { ... } }, false)  // +?windowId=xxx
await request(WIN, '', 'update', { filter: ..., values: ... }, false)

// 3a. 执行窗口 (提交全部操作)
await request(WIN, '', 'executeWindow', { windowId })

// 3b. 或者撤回
await request(WIN, '', 'clearWindow', { windowId })

// 3c. 或者撤回单步
await request(WIN, '', 'revokeOperate', { windowId, logId })
```

---

## 9. 缓存操作

```typescript
// 清除指定集合缓存
await request('', '', 'clearCache', {}, {
  dataSourceKey: 'salesDb',
  collection: 'orders',
  type: 'REGION'
})

// 清除全部缓存
await request('', '', 'clearAllCache', {}, {})

// 获取缓存统计
const stats = await request('', '', 'getCacheStatistics', {}, {
  dataSourceKey: 'salesDb',
  collection: 'orders',
  type: 'REGION'
})
```

---

## 10. 全局搜索

```typescript
const searchResult = await request<GlobalSearchResult>('', '', 'searchGlobal', {
  keyword: '搜索关键词',
  filter: { fields: { status: { $eq: 'active' } } },
  indices: ['orders', 'customers'],
  pageNumber: 0,
  pageSize: 20,
  sorts: { items: [{ field: '_score', order: 'desc' }] },
  groupBy: ['status']
})

// 响应:
// 单索引: { data: [...], total: N }
// 多索引: { items: [{ index: 'orders', data: [...], total: N }, ...] }
```

---

## 11. 响应类型速查

| 端点 | 响应类型 |
|------|---------|
| `get` | `Model` (JSON 对象) |
| `list` | `{ items: [...], total: N }` |
| `count` | `number` |
| `create` / `save` / `update` | `Model` (回填后的记录) |
| `batchCreate` / `batchSave` | `Model[]` |
| `delete` | `{ success: true }` |
| `acquireWindowId` | `{ windowId: "..." }` |
| `executeWindow` | `{ windowId, totalLogs, logsIds, message }` |
| `searchGlobal` | `{ data, items, ... }` |

---

## 12. 错误处理

### 12.1 常见 HTTP 错误

| HTTP 状态 | 异常类型 | 前端处置 |
|-----------|---------|---------|
| 409 | `DataLockExistException` | 提示用户数据被锁定，稍后重试 |
| 409 | `VersionConflictException` | 提示用户数据已变更，刷新后重试 |
| 500 | `MetaChangeException` | 提示系统配置变更，刷新页面 |
| 400 | 验证/参数错误 | 展示错误信息给用户 |
| 401/403 | 鉴权失败 | 跳转登录页 |

### 12.2 前端错误处理封装

```typescript
class DatapilotError extends Error {
  constructor(public status: number, message: string) {
    super(message)
    this.name = 'DatapilotError'
  }
}

async function safeCall<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn()
  } catch (e) {
    if (e instanceof DatapilotError) {
      switch (e.status) {
        case 409:
          ElMessage.warning('数据冲突，请刷新后重试')
          break
        case 400:
          ElMessage.error(e.message || '请求参数有误')
          break
        case 401:
        case 403:
          router.push('/login')
          break
        default:
          ElMessage.error('操作失败：' + e.message)
      }
    }
    throw e
  }
}
```

---

## 13. 常见陷阱

| 陷阱 | 正确做法 |
|------|---------|
| **忘记 useCamel** | 所有 CRUD 端点统一加 `?useCamel=true` |
| **删除不生效** | 必须传 `"deleteConfirm": true` |
| **list 不设分页** | 始终设置 `pageSize` 和 `pageNumber` |
| **批量操作多次请求** | 用 `batchCreate`/`batchSave` 一次搞定 |
| **多步写不原子** | 用窗口事务 (`acquireWindowId` → `executeWindow`) |
| **无用字段泄漏** | 通过 `whiteColumns` 控制，而非 `blackColumns` |
| **ES 查询无结果** | 集合未接入 ES 时设 `"useEs": false` |
| **忘记鉴权头** | 所有请求必须带 `Authorization: Bearer <token>` |

---

## 附录: 完整端点列表

```
# CRUD (全部 POST)
/dataPilot/{ds}/{col}/get            # 取单条
/dataPilot/{ds}/{col}/list           # 分页列表
/dataPilot/{ds}/{col}/count          # 计数
/dataPilot/{ds}/{col}/create         # 单条插入
/dataPilot/{ds}/{col}/batchCreate    # 批量插入
/dataPilot/{ds}/{col}/update         # 条件更新
/dataPilot/{ds}/{col}/delete         # 条件删除
/dataPilot/{ds}/{col}/save           # 单条 upsert
/dataPilot/{ds}/{col}/batchSave      # 批量 upsert

# 窗口事务
/dataPilot/{ds}/{col}/acquireWindowId  # 创建窗口
/dataPilot/{ds}/{col}/executeWindow    # 执行窗口
/dataPilot/{ds}/{col}/clearWindow      # 清除窗口
/dataPilot/{ds}/{col}/revokeOperate    # 撤回操作

# 事务锁
/dataPilot/{ds}/{col}/lock             # 锁定行
/dataPilot/{ds}/{col}/unlock           # 解锁行

# 订阅
/dataPilot/{ds}/{col}/subscribeChange       # 订阅变更
/dataPilot/{ds}/{col}/clearSubscriptions    # 清除用户订阅

# 元数据
/dataPilot/{ds}/{col}/options           # 获取集合配置
/dataPilot/{ds}/{col}/fullOptions       # 获取完整配置
/dataPilot/{ds}/collections             # 列出数据源下的集合
/dataPilot/dataSourceList               # 列出所有数据源

# 搜索
/dataPilot/searchGlobal                 # 全局搜索

# 缓存
/dataPilot/clearCache                   # 清除指定缓存
/dataPilot/clearAllCache                # 清除全部缓存
/dataPilot/clearLocalCache              # 清除本地缓存
/dataPilot/clearAllLocalCache           # 清除全部本地缓存
/dataPilot/getCacheStatistics           # 缓存统计
/dataPilot/getAllCacheStatistics        # 全部缓存统计

# 索引
/dataPilot/rebuildIndex                 # 重建索引
/dataPilot/addSearchAlias               # 添加搜索别名

# PgREST
/dataPilot/pgrest/parseFunction         # 解析 PG 函数
```
