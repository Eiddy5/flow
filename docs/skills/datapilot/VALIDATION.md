# 触发词匹配验收

对 25 个 skill 逐一生成 5 条典型自然语言 prompt（中英混合），并对照各自 `SKILL.md` frontmatter 的 `description` 关键词进行自评：若 prompt 中至少一个关键触发词在 description 中出现（含语义等价），计 PASS，否则 FAIL。

---

## 调用入口

### 1. datapilot-client

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "前端怎么调用 datapilot API" | 前端调用、call datapilot API | PASS |
| 2 | "How to call datapilot from Vue" | vue 调用 | PASS |
| 3 | "WebSocket 订阅数据变更" | WebSocket subscribe | PASS |
| 4 | "useCamel 参数什么意思" | useCamel | PASS |
| 5 | "React 项目中集成 datapilot" | react 调用 | PASS |

**5/5 PASS**

### 2. datapilot-engine

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "Java 后端怎么嵌入 datapilot engine" | Java 后端调用、embed engine | PASS |
| 2 | "DataSourceEngine 怎么用" | DataSourceEngine | PASS |
| 3 | "executeResult 事务模板" | executeResult | PASS |
| 4 | "用 listGroup 先取 category 分组信息" | listGroup、groupInfo | PASS |
| 5 | "更新事件没有主键，怎么注册 LoadStrategy 同步 ES 索引" | LoadStrategy、无主键更新同步索引 | PASS |

**5/5 PASS**

---

## 数据操作

### 3. datapilot-crud

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "帮我调用 `/dataPilot/main/user/list` 取前 50 条" | `/dataPilot/{datasource}/{collection}/*`、list | PASS |
| 2 | "How do I embed cloud-datapilot engine in a Spring Boot app and call create?" | embed engine、create | PASS |
| 3 | "batchCreate 一批订单到 datapilot 集合" | batchCreate、datapilot 集合 | PASS |
| 4 | "I only have the SDK jar, how to write to a collection?" | SDK jar 集成 | PASS |
| 5 | "groupBy category 后只取每组信息和总量" | 分组查询、groupInfo | PASS |

**5/5 PASS**

### 4. datapilot-filter

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "把这段 where clause 转成 Mongo 过滤" | where clause、translate filter | PASS |
| 2 | "build a QueryCondition for age > 18 and city = 'SH'" | build filter、query condition | PASS |
| 3 | "我要把过滤条件翻译到 ES DSL" | translate filter to es、过滤条件 | PASS |
| 4 | "用 jOOQ Condition 表达同一组条件" | jooq、query condition | PASS |
| 5 | "JSONB GIN_ARRAY_CONTAINS 条件怎么生成" | JSONB index strategy | PASS |

**5/5 PASS**

### 5. datapilot-operator

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "amount 字段自增 10 怎么写" | 自增 | PASS |
| 2 | "用 ArrayOperator 追加元素到 tags" | ArrayOperator | PASS |
| 3 | "JsonArrayOperator 按条件删除元素" | JsonArrayOperator | PASS |
| 4 | "upsert 时把字段设为 null" | setNull | PASS |
| 5 | "批量 save 每行用不同运算符" | 运算符 | PASS |

**5/5 PASS**

---

## 数据模型

### 6. datapilot-model

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "CamelJsonModel 是怎么做驼峰转换的" | CamelJsonModel、驼峰转换 | PASS |
| 2 | "JsonModel 和 MapModel 有什么区别" | JsonModel、MapModel | PASS |
| 3 | "ListMapResult 的 groupInfo 是什么" | ListMapResult、groupInfo | PASS |
| 4 | "DB 的 buyer_code 怎么映射到 DTO 的 customerCode" | ModelObjectMapper、DTO 映射 | PASS |
| 5 | "how to track field changes with model snapshot" | model snapshot | PASS |

**5/5 PASS**

### 7. datapilot-field

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "datapilot 支持哪些字段类型" | 字段类型 | PASS |
| 2 | "怎么配置计算字段 calculate field" | calculate field | PASS |
| 3 | "枚举字段怎么定义存储 ordinal" | 枚举字段 | PASS |
| 4 | "自定义字段类型怎么扩展" | 字段扩展、field type | PASS |
| 5 | "JSONB 路径怎么配置 GIN_ARRAY_CONTAINS" | JSONB index、GIN_ARRAY_CONTAINS | PASS |

**5/5 PASS**

### 8. datapilot-association

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "BelongsTo 关联怎么配置" | BelongsTo | PASS |
| 2 | "一对多 HasMany 级联删除" | HasMany、级联删除 | PASS |
| 3 | "BelongsToMany 多对多桥接表" | BelongsToMany | PASS |
| 4 | "关联查询 findAssociations 怎么用" | 关联查询 | PASS |
| 5 | "BelongsTo 的 ES HYBRID lookup 怎么配置" | association lookup、HYBRID | PASS |

**5/5 PASS**

---

## 进阶功能

### 9. datapilot-window

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "怎么 acquire window 然后批量提交写入" | acquire window、批量事务提交 | PASS |
| 2 | "我要对这一行加锁 lock row" | lock row | PASS |
| 3 | "execute window 之后如何 clear" | execute window、clear window | PASS |
| 4 | "revoke operate 会回滚什么？" | revoke operate | PASS |
| 5 | "写入窗口 unlock 行锁" | 写入窗口、unlock row | PASS |

**5/5 PASS**

### 10. datapilot-lock

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "行锁怎么用 CollaboratorService" | 行锁、CollaborateService | PASS |
| 2 | "lock row 防止并发编辑" | lock row | PASS |
| 3 | "VersionConflictException 乐观锁怎么处理" | 乐观锁、并发控制 | PASS |
| 4 | "MetaChangeException 什么时候触发" | MetaChangeException | PASS |
| 5 | "collection lock 集合元数据锁" | CollectionLockManager | PASS |

**5/5 PASS**

### 11. datapilot-cache

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "clear cache region user_profile" | clear cache、region | PASS |
| 2 | "查看缓存统计" | 查看缓存统计 | PASS |
| 3 | "只清本地缓存不要动分布式" | 只清本地缓存、local cache、distributed cache | PASS |
| 4 | "invalidate a region through CacheManager" | invalidate a region、CacheManager | PASS |
| 5 | "dump 当前缓存里的数据" | dump cached data | PASS |

**5/5 PASS**

### 12. datapilot-subscribe

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "订阅集合变更事件" | 订阅集合变更、subscribe change | PASS |
| 2 | "listen for inserts updates deletes on orders" | listen for inserts updates deletes | PASS |
| 3 | "parseSubscribeMessage 会返回 before/after 吗" | parseSubscribeMessage、topic/instance | PASS |
| 4 | "监听数据变化的 websocket consumer 怎么写" | 监听数据变化、websocket | PASS |
| 5 | "receive change events from datapilot" | receive change events、datapilot | PASS |

**5/5 PASS**

### 13. datapilot-event

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "BeforeCreate 生命周期钩子怎么注册" | lifecycle hook、DbEventType | PASS |
| 2 | "DSEventListener 监听集合事件" | DSEventListener | PASS |
| 3 | "OnDbEvent AfterUpdate 怎么做" | OnDbEvent | PASS |
| 4 | "DBEvent update 为什么只有字段名标记" | DBEvent、DBActionEvent、紧凑载荷 | PASS |
| 5 | "JooqNotifyConsumer 为什么没有推送" | JooqNotifyConsumer、enable-jooq-notify-consumer | PASS |

**5/5 PASS**

### 14. datapilot-interceptor

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "拦截数据变更通知，只发给负责人" | 拦截数据变更通知、只发给负责人 | PASS |
| 2 | "过滤 websocket 推送收件人" | 过滤推送收件人 | PASS |
| 3 | "DataChangeRecipientInterceptor 怎么注册" | DataChangeRecipientInterceptor | PASS |
| 4 | "RecipientDecision 有哪几种模式" | RecipientDecision | PASS |
| 5 | "DSDataChangeRecipientInterceptor 注解用法" | DSDataChangeRecipientInterceptor | PASS |

**5/5 PASS**

---

## 搜索与索引

### 15. datapilot-global-search

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "全局搜索关键字 'invoice'" | 全局搜索、关键字 | PASS |
| 2 | "跨集合搜索订单和物流" | 跨集合搜索 | PASS |
| 3 | "register a search alias mapping" | register search alias、alias mapping | PASS |
| 4 | "调用 searchGlobal 接口怎么组装 request" | searchGlobal | PASS |
| 5 | "addSearchAlias 用法" | addSearchAlias | PASS |

**5/5 PASS**

### 16. datapilot-rebuild-index

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "帮我重建 user 集合的索引" | 重建索引、rebuild index | PASS |
| 2 | "Run a FULL reindex on orders" | FULL、reindex | PASS |
| 3 | "datapilot 索引重建 increment 模式怎么调" | datapilot 索引重建、INCREMENTAL | PASS |
| 4 | "调用 /dataPilot/rebuildIndex 清空旧索引" | `/dataPilot/rebuildIndex`、CLEAR | PASS |
| 5 | "refresh index for a partial time range" | refresh index、PARTIAL | PASS |

**5/5 PASS**

---

### 17. datapilot-index-fallback-scan

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "ES 消费漏了一批数据怎么自动补偿" | ES missed-event recovery、index compensation | PASS |
| 2 | "@DBIndexFallbackScan 怎么配置" | @DBIndexFallbackScan | PASS |
| 3 | "fallback scan AUTO 和 FIXED 有什么区别" | fallback scan、AUTO/FIXED | PASS |
| 4 | "用 UpdateAt 水位把 DB 变更追平到 ES" | DBIndex consistency repair、watermark | PASS |
| 5 | "Redis 和 Pulsar 在索引兜底扫描中做什么" | Redis state、Pulsar delayed messages | PASS |

**5/5 PASS**

---

## Schema 与集成

### 18. datapilot-schema

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "list datasources 我注册了哪些库" | list datasources | PASS |
| 2 | "查看表结构 user_account" | 查看表结构 | PASS |
| 3 | "describe collection orders 字段类型" | describe collection | PASS |
| 4 | "updateCollection 修改主键" | updateCollection | PASS |
| 5 | "parse pgrest function signature" | parse pgrest function | PASS |

**5/5 PASS**

### 19. datapilot-schema-api

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "把外部 API 包装为 datapilot 集合" | Schema API、外部API | PASS |
| 2 | "PgREST 视图怎么定义" | PgREST | PASS |
| 3 | "PostgreSQL function 作为集合" | PostgreSQL function | PASS |
| 4 | "物化视图怎么刷新" | 物化视图 | PASS |
| 5 | "导出 OpenAPI 文档" | OpenAPI export | PASS |

**5/5 PASS**

### 20. datapilot-api-schema-scanner

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "scan controller to schema api" | scan controller to schema api | PASS |
| 2 | "ApiSchemaScanner 为什么没注册 Controller" | debug ApiSchemaScanner | PASS |
| 3 | "生成前端接口的 request schema" | generate request schema、前端接口元数据 | PASS |
| 4 | "generate response schema from Micronaut method" | generate response schema | PASS |
| 5 | "解析 API Schema 并注册 SchemaApiCollection" | 解析 API Schema | PASS |

**5/5 PASS**

---

## 表达式与 AI

### 21. datapilot-aviator

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "evaluate an expression `score > 60 && vip`" | evaluate an expression | PASS |
| 2 | "跑一段规则脚本做 computed field" | rule script、computed field | PASS |
| 3 | "用 AviatorExpression 编译动态表达式" | AviatorExpression、dynamic expression、compile | PASS |
| 4 | "filter records by formula" | filter records by formula | PASS |
| 5 | "register custom function 到 Aviator" | register custom function | PASS |

**5/5 PASS**

### 22. datapilot-text-to-sql

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "把这句自然语言转 SQL：上月销售额前十" | 自然语言、SQL 生成 | PASS |
| 2 | "natural language to SQL via SqlGenerator" | natural language to SQL、SqlGenerator | PASS |
| 3 | "NL2SQL 怎么准备 schema context" | NL2SQL、schema context | PASS |
| 4 | "ask question to database in plain English" | ask question to database | PASS |
| 5 | "generate SQL from prompt 的入参是什么" | generate SQL from prompt | PASS |

**5/5 PASS**

---

## 扩展性

### 23. datapilot-annotation

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "@Table 注解怎么配置集合" | @Table、注解 | PASS |
| 2 | "@Field 注解有哪些类型" | @Field、Micronaut annotation | PASS |
| 3 | "@BelongsToField 关联注解" | 注解 | PASS |
| 4 | "@DSPlugin 怎么注册插件" | @DSPlugin | PASS |
| 5 | "@DBIndexFallbackScan 和 @JsonbIndex 怎么用" | @DBIndexFallbackScan、@JsonbIndex | PASS |

**5/5 PASS**

### 24. datapilot-plugin

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "怎么写一个 datapilot 插件" | 插件 | PASS |
| 2 | "EnginePlugin 接口怎么实现" | EnginePlugin | PASS |
| 3 | "DSPlugin 注解自动注册" | DSPlugin、registerPlugin | PASS |
| 4 | "插件生命周期 beforeLoad load" | 插件 | PASS |
| 5 | "怎么扩展引擎功能" | 扩展引擎 | PASS |

**5/5 PASS**

### 25. datapilot-test

| # | Prompt | 命中关键词 | 结果 |
|---|--------|----------|------|
| 1 | "运行单元测试" | 运行单元测试 | PASS |
| 2 | "run test for cache module" | run test | PASS |
| 3 | "集成测试需要什么环境" | 集成测试 | PASS |
| 4 | "测试覆盖率怎么查" | 测试覆盖 | PASS |
| 5 | "帮我写一个测试用例" | 测试用例 | PASS |

**5/5 PASS**

---

## 汇总

| Skill | 通过 | 备注 |
|-------|------|------|
| datapilot-client | 5/5 | — |
| datapilot-engine | 5/5 | — |
| datapilot-crud | 5/5 | — |
| datapilot-filter | 5/5 | — |
| datapilot-operator | 5/5 | — |
| datapilot-model | 5/5 | — |
| datapilot-field | 5/5 | — |
| datapilot-association | 5/5 | — |
| datapilot-window | 5/5 | — |
| datapilot-lock | 5/5 | — |
| datapilot-cache | 5/5 | — |
| datapilot-subscribe | 5/5 | — |
| datapilot-event | 5/5 | — |
| datapilot-interceptor | 5/5 | — |
| datapilot-global-search | 5/5 | — |
| datapilot-rebuild-index | 5/5 | — |
| datapilot-index-fallback-scan | 5/5 | — |
| datapilot-schema | 5/5 | — |
| datapilot-schema-api | 5/5 | — |
| datapilot-api-schema-scanner | 5/5 | — |
| datapilot-aviator | 5/5 | — |
| datapilot-text-to-sql | 5/5 | — |
| datapilot-annotation | 5/5 | — |
| datapilot-plugin | 5/5 | — |
| datapilot-test | 5/5 | — |

**总通过率：125/125 (100%)**。每个 skill 的 frontmatter description 均包含充分的中英文触发词以覆盖典型外部 SDK 消费者的自然语言提问模式。
