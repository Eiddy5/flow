# cloud-datapilot Skill 套件

面向外部 SDK 消费者的完整技能索引。

- **Maven 坐标**：`org.x9.cloud:cloud-datapilot:2.0.47`
- **目标读者**：只持有 jar 包、无源码访问权限的外部集成方
- **参考原则**：每个 skill 按当前真实入口给出 HTTP、Java engine 或运行时配置示例；没有公开 HTTP 端点的能力不会虚构双通道契约
- **触发词验收**：参见 [`VALIDATION.md`](VALIDATION.md)

## 增量更新基准

套件当前覆盖到的源码提交记录在 [`SYNC_BASE.json`](SYNC_BASE.json)。下次更新先读取其中的 `sourceCommit`，再审计：

```bash
BASE=$(sed -n 's/.*"sourceCommit": "\([0-9a-f]*\)".*/\1/p' docs/skills/SYNC_BASE.json)
git log --oneline "$BASE"..HEAD
git diff --name-only "$BASE"..HEAD -- src/main/java
```

更新完成后，将 `sourceCommit` 替换为本次实际审阅的源码快照提交。不要把纯 skill 文档提交当作源码基准。

## Skill 索引

### 调用入口

| Skill | 一句话职责 | 关键触发词 |
|-------|----------|-----------|
| [datapilot-client](datapilot-client/SKILL.md) | 前端调用指南：HTTP REST、HTTP 注册 + WebSocket 推送、useCamel、Vue/React 集成、错误处理 | 前端调用、call datapilot API、vue/react 调用、subscribeChange、WebSocket subscribe |
| [datapilot-engine](datapilot-engine/SKILL.md) | Java 后端引擎嵌入：DataSourceEngine、EngineExecutor、DataQuery 分组摘要、运行时配置、ExtendableQuery、ES LoadStrategy | embed engine、Java 后端调用、DataSourceEngine、listGroup、groupInfo、LoadStrategy |

### 数据操作

| Skill | 一句话职责 | 关键触发词 |
|-------|----------|-----------|
| [datapilot-crud](datapilot-crud/SKILL.md) | 单集合 CRUD：HTTP/Java 增删改查，以及 Java-only listGroup/countGroup 分组摘要 | 读写 datapilot 集合、Java 分组查询、groupInfo、`/dataPilot/{ds}/{col}/*`、SDK jar 集成 |
| [datapilot-filter](datapilot-filter/SKILL.md) | 构建 `QueryCondition` 并 translate 到 jOOQ / SQL / Mongo / Elasticsearch / CQEngine / Expression 六种方言 | build filter、where clause、query condition、过滤条件、方言翻译 |
| [datapilot-operator](datapilot-operator/SKILL.md) | 请求运算符：NumberOperator 自增自减、ArrayOperator PG数组操作、JsonArrayOperator JSONB数组操作、SetNullOperator | 运算符、自增、数组追加、setNull、NumberOperator、JsonArrayOperator |

### 数据模型

| Skill | 一句话职责 | 关键触发词 |
|-------|----------|-----------|
| [datapilot-model](datapilot-model/SKILL.md) | 数据模型：JsonModel/CamelJsonModel/MapModel、ListResult/ListMapResult、注解 DTO 字段映射、序列化、驼峰转换、变更追踪 | Model、JsonModel、ModelObjectMapper、ListMapResult、DTO 映射、驼峰转换 |
| [datapilot-field](datapilot-field/SKILL.md) | 字段类型详解：25+ 字段类型配置、系统字段行为表、计算字段、枚举字段、快照字段、自定义表单字段、验证器集成 | 字段类型、添加字段、calculate field、枚举字段、字段扩展、field option |
| [datapilot-association](datapilot-association/SKILL.md) | 关联系统：四种关联、级联、预加载、BelongsTo 分组信息、ES EMBEDDED/LOOKUP/HYBRID 策略 | 关联、BelongsTo、groupInfo、association lookup、HYBRID、关联查询 |

### 进阶功能

| Skill | 一句话职责 | 关键触发词 |
|-------|----------|-----------|
| [datapilot-window](datapilot-window/SKILL.md) | 写入窗口事务：acquire / execute / clear / revoke，操作合并规则，行锁 | 写入窗口、批量事务提交、行锁、CollaborateService |
| [datapilot-lock](datapilot-lock/SKILL.md) | 协作与锁定：Redis 行级锁、乐观锁版本控制、集合元数据锁、协作在场、MetaChangeException | 行锁、lock row、并发控制、CollaborateService、LockService |
| [datapilot-cache](datapilot-cache/SKILL.md) | 缓存治理：region 清除、本地 / 分布式、统计 dump、自动失效 | clear cache、invalidate region、缓存统计、CacheManager |
| [datapilot-subscribe](datapilot-subscribe/SKILL.md) | 数据变更订阅：真实 HTTP 请求/响应、Redis 注册、topic/instance、通知 consumer 开关与清理接口 | subscribe change、订阅集合变更、topic instance、parseSubscribeMessage、clearSubscriptions |
| [datapilot-event](datapilot-event/SKILL.md) | 事件系统：生命周期注解、紧凑 DBEvent 载荷、Pulsar consumer 与 WebSocket 通知 | 事件监听、DBActionEvent、DbEventType、OnDbEvent、JooqNotifyConsumer、数据变更事件 |
| [datapilot-interceptor](datapilot-interceptor/SKILL.md) | 数据变更通知收件人拦截器：两段式过滤、RecipientDecision 裁剪、注解/代码/collectionKeys 注册 | 拦截数据变更通知、过滤推送收件人、DataChangeRecipientInterceptor、RecipientDecision、只发给负责人 |

### 搜索与索引

| Skill | 一句话职责 | 关键触发词 |
|-------|----------|-----------|
| [datapilot-global-search](datapilot-global-search/SKILL.md) | 跨集合全文搜索 + search alias 注册 + GlobalSearchSource | 全局搜索、跨集合搜索、searchGlobal、addSearchAlias |
| [datapilot-rebuild-index](datapilot-rebuild-index/SKILL.md) | 二级索引重建（ES / 内存），支持 FULL / INCREMENTAL / PARTIAL / CLEAR 四种 `RebuildMode` | rebuild index、reindex、重建索引、`/dataPilot/rebuildIndex` |
| [datapilot-index-fallback-scan](datapilot-index-fallback-scan/SKILL.md) | DB→ES 兜底增量扫描：AUTO/FIXED 调度、UpdateAt 水位、Redis CAS、Pulsar 延迟消息 | fallback scan、ES 漏同步补偿、DBIndex 一致性、索引追平 |

### Schema 与集成

| Skill | 一句话职责 | 关键触发词 |
|-------|----------|-----------|
| [datapilot-schema](datapilot-schema/SKILL.md) | 元数据自省：datasource / collection / field 结构，pgrest function 解析，collection DDL | list datasources、describe collection、查看表结构、updateCollection |
| [datapilot-schema-api](datapilot-schema-api/SKILL.md) | Schema API & PgREST：外部 HTTP API 转集合、PgREST 视图/函数、OpenAPI 导出、PgrestSqlParser | Schema API、外部API、PgREST、PostgreSQL function、物化视图、OpenAPI export |
| [datapilot-api-schema-scanner](datapilot-api-schema-scanner/SKILL.md) | 扫描 Micronaut Controller 的 `@ApiSchema`，生成前端可消费的请求/响应 schema 并注册集合 | ApiSchemaScanner、scan controller、request schema、前端接口元数据 |

### 表达式与 AI

| Skill | 一句话职责 | 关键触发词 |
|-------|----------|-----------|
| [datapilot-aviator](datapilot-aviator/SKILL.md) | Aviator 动态表达式编译 / 求值、`VariableContainer`、自定义函数、`AviatorExpressionCondition` | evaluate expression、rule script、表达式求值、computed field |
| [datapilot-text-to-sql](datapilot-text-to-sql/SKILL.md) | 自然语言转 SQL：`SqlGenerator` + `ParsedQuery` + schema context 准备 | text to sql、NL2SQL、自然语言转 SQL、ask question to database |

### 测试

| Skill | 一句话职责 | 关键触发词 |
|-------|----------|-----------|
| [datapilot-test](datapilot-test/SKILL.md) | 测试规范与执行指南：UnitTest / IntegrationTest 组织、Gradle 任务、Docker 环境、测试用例编写 | run test、测试规范、单元测试、集成测试、test coverage、测试覆盖 |

## 扩展性

| Skill | 一句话职责 | 关键触发词 |
|-------|----------|-----------|
| [datapilot-annotation](datapilot-annotation/SKILL.md) | Micronaut 注解全集：@Table/@Field/@BelongsToField/@DSPlugin/@DSEventListener 等全部注解 | 注解、@Table、@Field、Micronaut annotation、@DSPlugin |
| [datapilot-plugin](datapilot-plugin/SKILL.md) | 插件系统：EnginePlugin 接口、生命周期、内置插件、注册方式 | 插件、EnginePlugin、DSPlugin、registerPlugin、扩展引擎 |

## 使用方式

1. **初次接入**：从 [datapilot-client](datapilot-client/SKILL.md)（前端）或 [datapilot-engine](datapilot-engine/SKILL.md)（后端）开始
2. **按需查阅**：根据任务关键词匹配上表中的 skill，打开对应 `SKILL.md`
3. **跨域组合**：跨域任务（如"过滤查询 → 批量更新 → 订阅变更"）按执行顺序串联多个 skill
4. **全貌参考**：启动阶段可浏览 [datapilot-field](datapilot-field/SKILL.md)、[datapilot-annotation](datapilot-annotation/SKILL.md) 了解数据建模能力

## 撰写规范

- 统一模板位于 `_template/SKILL.md`，新建 skill 时复制该目录并重命名 `name`、替换占位符。
- 代码使用英文，注释使用中文。
- 每个 skill 覆盖 HTTP（前端调用）和 Java Engine（后端调用）双通道，含完整代码示例。
- 引用代码时使用类名、方法名等概念性描述，禁止依赖行号。
- 描述与正文聚焦能力本身，避免出现进度类词汇与外部工具名称。
