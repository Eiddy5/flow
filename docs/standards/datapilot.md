# DataPilot 使用指南

> 本文根据 CSES 项目当前代码整理，描述 DataPilot 在项目中的依赖引入、初始化、模型定义、数据访问、索引搜索和本地联调方式。

## Flow 项目接入说明

Flow Core 的 PostgreSQL Repository 不通过 DataPilot 选择数据库。宿主通过
`datasources.flow` 声明标准具名数据源，由 Micronaut 与 PAAS 自动创建具名
DataSource 和 JOOQ。
Schema 由部署人员在启动前手工执行完整基线，Flow 不使用 Flyway。

`core/src/main/java/org/cses/flow/infrastructure/datapilot` 只保留 Core/Demo 的
历史兼容适配；它不能代替 Flow Core 的 `datasources.flow` 配置。

## 1. DataPilot 在项目中的定位

DataPilot 是项目的数据访问抽象层，主要负责：

- 统一访问 PostgreSQL、MongoDB 和系统 API 等数据源。
- 通过注解描述表、字段、关联和索引。
- 提供统一的查询、创建、更新、删除 API。
- 支持可复用的业务查询对象。
- 支持 Elasticsearch 索引和全局搜索。
- 支持数据库变更事件和接收人过滤。

项目中的主要调用链如下：

```text
Gradle 引入 cloud-datapilot
  → Micronaut 扫描 DataPilot 和 CSES Bean
  → 创建 CsesDataSourceEngine
  → 注册 PostgreSQL / MongoDB / 系统 API 数据源
  → 扫描 @Table、@Field、@Dbindex 等模型
  → Repository 或 Query 调用 DataPilot API
```

## 2. 引入依赖

### 2.1 版本和依赖坐标

版本定义在 `gradle/libs.versions.toml`：

```toml
[versions]
cloud-datapilot = "2.0.48"

[libraries]
org-x9-cloud-datapilot = {
    module = "org.x9.cloud:cloud-datapilot",
    version.ref = "cloud-datapilot"
}
```

`core` 模块引入依赖：

```groovy
dependencies {
    implementation(libs.org.x9.cloud.datapilot)
}
```

相关文件：

- [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
- [`core/build.gradle`](../../core/build.gradle)
- [`buildSrc/src/main/groovy/org.cses.build.base.gradle`](buildSrc/src/main/groovy/org.cses.build.base.gradle)

可以使用以下命令确认最终解析版本：

```bash
./gradlew :core:dependencyInsight \
  --dependency cloud-datapilot \
  --configuration compileClasspath
```

当前项目最终解析到 `org.x9.cloud:cloud-datapilot:2.0.48`。

### 2.2 本地源码联调

`settings.gradle` 支持将 Maven 依赖替换为本地 DataPilot 工程：

```groovy
if (providers.gradleProperty('useLocalCloudDatapilot').orNull?.toBoolean()) {
    includeBuild('../../yundiz/cloud-datapilot')
}
```

默认开关位于 `gradle.properties`：

```properties
useLocalCloudDatapilot=false
```

临时启用：

```bash
./gradlew -PuseLocalCloudDatapilot=true :core:compileJava
```

也可以在本地 `gradle.properties` 中设置：

```properties
useLocalCloudDatapilot=true
```

启用前应确认 `../../yundiz/cloud-datapilot` 相对于 CSES 工程确实存在，并且本地工程发布的模块坐标与 `org.x9.cloud:cloud-datapilot` 一致。

## 3. 初始化 DataPilot 引擎

### 3.1 定义项目引擎

CSES 使用 Micronaut 单例扩展 DataPilot：

```java
@Singleton
public class CsesDataSourceEngine
        extends MicronautDataSourceEngine<CsesSession, CsesUser> {

    @Override
    public String getKey() {
        return "cses";
    }
}
```

完整实现见：

- [`CsesDataSourceEngine.java`](server/src/main/java/org/cses/server/common/dataPilot/CsesDataSourceEngine.java)

泛型含义：

- `CsesSession`：一次业务操作使用的会话上下文。
- `CsesUser`：DataPilot 识别的用户对象。

### 3.2 注册逻辑数据源

项目在 `registerDataSources()` 中注册三类数据源。

#### PostgreSQL 主数据源

```java
DbDataSourceOption option = new DbDataSourceOption();
option.dbName = "cses";
option.name = "default";
option.title = "主数据源";
option.jooqSchema = Public.PUBLIC;
option.type = DataSourceType.orm;
option.dbType = DbType.postgresql;

defineDbDataSource(option);
```

#### MongoDB 数据源

```java
DbDataSourceOption option = new DbDataSourceOption();
option.dbName = "okr";
option.name = "okr";
option.title = "mongodb默认";
option.type = DataSourceType.nosql;
option.dbType = DbType.mongodb;

defineDbDataSource(option);
```

#### 系统 API 数据源

```java
ApiDataSourceOption option = new ApiDataSourceOption();
option.key = DataSourceKey.system_api;
option.name = "api";
option.title = "系统api";
option.type = DataSourceType.systemApi;

defineApiDataSource(option);
```

这里定义的是 DataPilot 使用的逻辑数据源。数据库 URL、账号、连接池等物理连接配置由项目的 Micronaut/平台组件和 Consul 配置提供，不应硬编码在引擎类中。

### 3.3 配置默认数据源

```java
@Override
public void configuration(EngineConfiguration configuration) {
    super.configuration(configuration);
    configuration.isDebug = false;
    configuration.openTrace(true);
    configuration.setOpenDetailTrace(false);
    configuration.setDefaultDBDataSource(DataSourceKey.postgresql);
    configuration.setManagerDataSourceKey(DataSourceKey.mongodb);
}
```

当前主要数据源键为：

| 用途 | 代码常量 | 实际键 |
| --- | --- | --- |
| 默认 PostgreSQL | `DataSourceKey.Default` / `DataSourceKey.postgresql` | `postgresql.cses` |
| MongoDB | `DataSourceKey.mongodb` / `DataSourceKey.mongodb_okr` | `mongodb.okr` |
| 系统 API | `DataSourceKey.system_api` | `system.api` |

定义位置：

- [`DataSourceKey.java`](server/src/main/java/org/cses/server/common/dataPilot/DataSourceKey.java)

业务代码应优先引用常量，不要散落字符串：

```java
DataSourceKey.Default
DataSourceKey.mongodb
```

### 3.4 注册引擎服务

项目通过一个具名 Micronaut Bean 将引擎交给 DataPilot 管理：

```java
@Singleton
@Named("cses")
public class CsesDatasourceEngineService
        extends DataSourceService<CsesSession, CsesUser> {

    @Inject
    CsesDataSourceEngine csesDataSourceEngine;

    @Override
    public DataSourceEngine<CsesSession, CsesUser> createDataSourceEngine() {
        return csesDataSourceEngine;
    }
}
```

实现见：

- [`CsesDatasourceEngineService.java`](server/src/main/java/org/cses/server/common/dataPilot/CsesDatasourceEngineService.java)

`@Named("cses")` 应与 `CsesDataSourceEngine#getKey()` 返回值保持一致。

### 3.5 注册兼容字段类型

项目还为旧业务字段类型注册了默认字段工厂：

```java
@Override
public void registerFieldTypes() {
    super.registerFieldTypes();
    CsesLegacyFieldTypes.registerInto(this);
}
```

这用于兼容 `taskStatus`、`taskFiles`、`doc` 等历史字段类型。新增标准业务字段时，应优先使用 DataPilot 已有的 `FieldType`；只有确有兼容需求时才扩展字段注册。

实现见：

- [`CsesLegacyFieldTypes.java`](server/src/main/java/org/cses/server/common/dataPilot/CsesLegacyFieldTypes.java)

## 4. 定义数据模型

### 4.1 PostgreSQL 模型

使用 `@Table` 绑定数据源和表：

```java
@Getter
@Setter
@Table(name = "tm_space", dataSourceKey = DataSourceKey.Default)
public class SpaceEntity extends TmSpaceObject {
}
```

项目中的 PostgreSQL 实体通常继承 jOOQ 生成的 POJO，使数据库字段和 Java 属性保持一致。

参考：

- [`SpaceEntity.java`](server/src/main/java/org/cses/server/service/taskManage/domain/space/entity/SpaceEntity.java)

### 4.2 MongoDB 模型

MongoDB 模型同样使用 `@Table`，但指定 MongoDB 数据源：

```java
@Data
@Table(
    name = TaskManageDocConfig.TABLE_NAME,
    dataSourceKey = DataSourceKey.mongodb,
    title = "任务管理文档配置",
    primaryKeys = "id"
)
public class TaskManageDocConfig {

    public static final String TABLE_NAME = "tm_doc_config";

    @Field(title = "ID", type = FieldType.AutoId)
    public String id;

    @Field(title = "模块", type = FieldType.String)
    public String module;

    @Field(title = "是否启用", type = FieldType.Boolean)
    public Boolean enabled;
}
```

参考：

- [`TaskManageDocConfig.java`](server/src/main/java/org/cses/server/service/taskManage/domain/doc/TaskManageDocConfig.java)

### 4.3 字段注解

常见字段定义：

```java
@Field(
    title = "版本",
    type = FieldType.Version,
    dataType = DataType.INTEGER
)
public Integer getVersion() {
    return version;
}
```

项目中还使用：

- `@NumberField`：数值字段。
- `@DateField`：日期或时间字段。
- `@EnumField`：枚举字段。
- `@CtxField`：从会话上下文获取值。
- `@Field(type = FieldType.AutoId)`：自动 ID。
- `@Field(type = FieldType.Version)`：乐观锁版本字段。
- `@Field(type = FieldType.State)`：状态或逻辑删除字段。

具体参数应以当前 DataPilot 版本中的注解定义为准。

### 4.4 定义关联

#### 一对多

```java
@HasManyField(
    title = "协作者列表",
    target = "tm_collaborator",
    targetDataSource = DataSourceKey.Default,
    sourceKey = "id",
    foreignKey = "node_id"
)
public List<CollaboratorEntity> collaboratorList;
```

#### 一对一

```java
@HasOneField(
    title = "空间节点",
    target = "tm_node_stat",
    targetDataSource = DataSourceKey.Default,
    sourceKey = "id",
    foreignKey = "node_id"
)
public NodeStatEntity node;
```

#### 归属关系

```java
@BelongsToField(
    title = "状态",
    target = "tm_task_status",
    targetDataSource = DataSourceKey.Default,
    targetKey = "id",
    foreignKey = "status_id"
)
public TaskStatusEntity status;
```

关联注解只描述关系。查询时如果需要回填关联对象，还需要显式调用：

```java
query.findAssociations("collaboratorList");
```

## 5. 注入引擎

推荐使用构造器注入：

```java
@Singleton
public class ExampleRepository {

    private final CsesDataSourceEngine dataSource;

    public ExampleRepository(CsesDataSourceEngine dataSource) {
        this.dataSource = dataSource;
    }
}
```

项目中也存在字段注入：

```java
@Inject
CsesDataSourceEngine dataSource;
```

新代码优先使用构造器注入，依赖关系更明确，也更方便测试。

## 6. 基础 CRUD

下面示例中的 `session` 是 `CsesSession`，`TABLE_NAME` 是表名。

### 6.1 创建

```java
Model model = dataSource
    .buildUpdater(session, DataSourceKey.Default, TABLE_NAME)
    .create(entity);

entity.setId(model.getString(Fields.ID));
```

`create()` 返回 DataPilot `Model`，可从中读取自动生成的 ID 或其他回写字段。

### 6.2 更新

保存一个已有实体：

```java
dataSource
    .buildUpdater(session, DataSourceKey.Default, TABLE_NAME)
    .save(entity);
```

批量保存：

```java
dataSource
    .buildUpdater(session, DataSourceKey.Default, TABLE_NAME)
    .batchSave(entities);
```

### 6.3 按主键查询

```java
ExampleEntity entity = dataSource
    .buildQuery(session, DataSourceKey.Default, TABLE_NAME)
    .pk(id)
    .single(ExampleEntity.class);
```

### 6.4 条件查询单条记录

```java
ExampleEntity entity = dataSource
    .buildQuery(session, DataSourceKey.Default, TABLE_NAME)
    .filter(
        QueryCondition.field(EXAMPLE.ID).eq(id)
    )
    .single(ExampleEntity.class);
```

### 6.5 查询列表

```java
ListResult<ExampleEntity> result = dataSource
    .buildQuery(session, DataSourceKey.Default, TABLE_NAME)
    .filter(
        QueryCondition.field(EXAMPLE.COMPANY_ID)
            .eq(session.getCompanyId())
    )
    .list(ExampleEntity.class);

List<ExampleEntity> items = result.getItems();
```

`list()` 返回 `ListResult<T>`，不要直接假设返回 `List<T>`。

### 6.6 组合条件

使用 `QueryCondition`：

```java
QueryCondition condition = QueryCondition
    .field(EXAMPLE.SOURCE_ID)
    .eq(sourceId)
    .and(
        QueryCondition.field(EXAMPLE.SOURCE_TYPE)
            .eq(sourceType.name())
    );

List<ExampleEntity> items = dataSource
    .buildQuery(session, DataSourceKey.Default, TABLE_NAME)
    .filter(condition)
    .list(ExampleEntity.class)
    .getItems();
```

也可以使用 DataPilot 链式条件：

```java
List<ExampleEntity> items = dataSource
    .buildQuery(session, DataSourceKey.Default, TABLE_NAME)
    .or()
        .eq(EXAMPLE.SOURCE_ID, resourceId)
        .eq(EXAMPLE.TARGET_ID, resourceId)
    .end()
    .list(ExampleEntity.class)
    .getItems();
```

条件块调用 `or()`、`and()` 后应配对调用 `end()`。

### 6.7 删除

```java
dataSource
    .buildDeleter(session, DataSourceKey.Default, TABLE_NAME)
    .pk(id)
    .delete();
```

删除前应明确 DataPilot 当前对该模型执行的是物理删除还是基于状态字段的逻辑删除。

### 6.8 复用已有 jOOQ 上下文

如果操作位于已有事务或 Repository 上下文中，将相同的 `DSLContext` 传给 DataPilot：

```java
dataSource
    .buildUpdater(session, DataSourceKey.Default, TABLE_NAME)
    .dsl(getDsl())
    .save(entity);
```

查询同理：

```java
dataSource
    .buildQuery(session, DataSourceKey.Default, TABLE_NAME)
    .dsl(getDsl())
    .pk(id)
    .single(ExampleEntity.class);
```

这样可以避免 DataPilot 操作脱离当前事务。

完整 CRUD 示例可参考：

- [`RelationRepositoryImpl.java`](server/src/main/java/org/cses/server/service/relation/impl/persistance/RelationRepositoryImpl.java)

## 7. 封装复杂查询

当查询包含参数解析、公共过滤条件、关联加载或多个业务查询方法时，使用 `ExtendableQuery`。

### 7.1 定义 Query

```java
@Prototype
public class SpaceQuery
        extends ExtendableQuery<CsesSession, CsesUser, SpaceQuery> {

    private String spaceId;

    @Override
    public String getDatasourceKey() {
        return DataSourceKey.Default;
    }

    @Override
    public TableImpl<?> getTable() {
        return TM_SPACE;
    }

    @Override
    public SpaceQuery parse(JsonObject params) {
        super.parse();
        this.spaceId = params.getString("spaceId");
        return this;
    }

    public SpaceQuery bySpaceId(String id) {
        field(TM_SPACE.ID).eq(id);
        return this;
    }

    public SpaceEntity detail() {
        findAssociations("collaboratorList");
        return single(SpaceEntity.class);
    }
}
```

查询类必须使用 `@Prototype`。查询对象内部包含本次请求的条件和参数，不应作为单例复用。

### 7.2 创建并使用 Query

```java
public SpaceQuery buildQuery(CsesSession session, JsonObject params) {
    return dataSourceEngine
        .useQuery(SpaceQuery.class, session)
        .parse(params);
}
```

调用：

```java
SpaceEntity space = buildQuery(session, params)
    .bySpaceId(spaceId)
    .detail();
```

参考：

- [`SpaceQuery.java`](server/src/main/java/org/cses/server/service/taskManage/application/space/query/SpaceQuery.java)
- [`SpaceApplicationService.java`](server/src/main/java/org/cses/server/service/taskManage/application/space/service/SpaceApplicationService.java)

适合使用 `ExtendableQuery` 的场景：

- 查询条件需要从接口参数统一解析。
- 同一实体存在多种业务查询组合。
- 需要统一加载关联。
- 需要在多个 Service/Reader 之间复用查询语义。

简单的单表 CRUD 不必额外创建 Query 类。

## 8. DataPilot 索引

### 8.1 声明模型需要索引

```java
@Table(name = "task_v3", camelCase = false)
@Dbindex
public class TaskEntity extends TaskV3Object {
}
```

### 8.2 配置索引字段

关键字字段：

```java
@DbIndexField(analyzerType = AnalyzerType.KEYWORD)
public String getId() {
    return super.getId();
}
```

嵌套字段：

```java
@DbIndexField(nested = true)
public List<CollaboratorEntity> collaborators;
```

忽略字段：

```java
@DbIndexIgnore
public JsonObject getLastActivity() {
    return super.getLastActivity();
}
```

中文和拼音分析：

```java
@DbIndexField(
    nested = true,
    analyzerType = AnalyzerType.CN_SMART_PINYIN,
    keywordFields = "name",
    assIndexFields = "name"
)
public TaskStatusEntity status;
```

参考：

- [`TaskEntity.java`](server/src/main/java/org/cses/server/service/taskManage/domain/task/entity/TaskEntity.java)

索引字段变更后，应确认是否需要重建索引。仅修改 Java 注解不一定会自动迁移已有 Elasticsearch Mapping。

## 9. 全局搜索

需要作为全局搜索来源的 Bean，实现 `GlobalSearchSource` 并添加 `@EnableGlobalSearch`：

```java
@Singleton
@EnableGlobalSearch
public class ExampleGlobalSearch
        implements GlobalSearchSource<CsesUser, CsesSession> {

    @Override
    public String getIndexName() {
        return "example_index";
    }

    @Override
    public Query buildQuery(CsesSession session, JsonObject params) {
        // 构建 Elasticsearch Query
    }

    @Override
    public SearchSourceConfig getSearchConfig(JsonObject params) {
        return SearchSourceConfig.builder()
            .highlightFields(List.of("name"))
            .build();
    }

    @Override
    public Object transformResult(
            CsesSession session,
            SearchResult<JsonModel> result,
            JsonObject params) {
        return result.items;
    }

    @Override
    public SourceType getSourceType() {
        return new SourceType("example", "示例");
    }
}
```

主要职责：

- `getIndexName()`：返回索引名。
- `buildQuery()`：根据会话和请求参数构建检索条件。
- `getSearchConfig()`：配置高亮等搜索选项。
- `transformResult()`：将索引结果转换为接口需要的响应。
- `getSourceType()`：声明搜索来源标识和标题。

参考：

- [`DocGlobalSearchIndex.java`](server/src/main/java/org/cses/server/service/doc/repositories/searcher/DocGlobalSearchIndex.java)

## 10. 数据变更事件

项目使用 DataPilot 的数据库变更分发能力控制事件接收人。

```java
@DSDataChangeRecipientInterceptor(
    collections = {"postgresql.cses.task_v3"}
)
public class TaskPermissionRecipientInterceptor
        implements DataChangeRecipientInterceptor {

    @Override
    public boolean supports(
            DbCollection<?> collection,
            String topic,
            String instanceId) {
        return true;
    }

    @Override
    public RecipientDecision intercept(
            DataChangeDispatchContext context) {
        return RecipientDecision.allowUsers(userIds);
    }
}
```

常见返回值：

- `RecipientDecision.pass()`：保留原分发结果。
- `RecipientDecision.dropAll()`：不向任何接收人分发。
- `RecipientDecision.allowUsers(userIds)`：只允许指定用户。

集合标识格式为：

```text
数据源键.表名
```

例如：

```text
postgresql.cses.task_v3
```

参考：

- [`TaskPermissionRecipientInterceptor.java`](server/src/main/java/org/cses/server/service/taskManage/domain/task/notify/TaskPermissionRecipientInterceptor.java)

## 11. 推荐的代码组织

```text
domain/
  entity/
    ExampleEntity.java       # @Table、@Field、关联、索引

application/
  query/
    ExampleQuery.java        # 可选，复杂查询使用 ExtendableQuery
  service/
    ExampleService.java      # 业务编排

infrastructure/
  repository/
    ExampleRepository.java   # DataPilot CRUD
  search/
    ExampleGlobalSearch.java # 可选，全局搜索
```

推荐职责：

- Entity 只描述数据结构和必要的领域行为。
- Query 封装查询语义，不处理业务流程。
- Repository 隔离 DataPilot、jOOQ 等持久化细节。
- Service 负责业务编排和事务边界。
- 搜索来源单独实现，避免把 Elasticsearch 查询混入普通 Repository。

## 12. 新增一个 DataPilot 模块的最小步骤

1. 确认目标数据源已在 `CsesDataSourceEngine` 中注册。
2. 创建实体并添加 `@Table`。
3. 为非默认字段或关联添加 `@Field`、`@HasManyField` 等注解。
4. 创建 Repository，并注入 `CsesDataSourceEngine`。
5. 使用 `buildQuery`、`buildUpdater`、`buildDeleter` 完成 CRUD。
6. 如果查询逻辑复杂，增加 `@Prototype` 的 `ExtendableQuery`。
7. 如果需要搜索，添加 `@Dbindex` 和索引字段注解。
8. 编写单元测试或集成测试，验证数据源键、表名、字段映射和事务。

最小示例：

```java
@Data
@Table(name = "example", dataSourceKey = DataSourceKey.Default)
public class ExampleEntity {

    @Field(title = "ID", type = FieldType.AutoId)
    private String id;

    @Field(title = "名称", type = FieldType.String)
    private String name;
}
```

```java
@Singleton
public class ExampleRepository {

    private static final String TABLE_NAME = "example";
    private final CsesDataSourceEngine dataSource;

    public ExampleRepository(CsesDataSourceEngine dataSource) {
        this.dataSource = dataSource;
    }

    public ExampleEntity findById(CsesSession session, String id) {
        return dataSource
            .buildQuery(session, DataSourceKey.Default, TABLE_NAME)
            .pk(id)
            .single(ExampleEntity.class);
    }

    public void create(CsesSession session, ExampleEntity entity) {
        Model model = dataSource
            .buildUpdater(session, DataSourceKey.Default, TABLE_NAME)
            .create(entity);
        entity.setId(model.getString(Fields.ID));
    }

    public void save(CsesSession session, ExampleEntity entity) {
        dataSource
            .buildUpdater(session, DataSourceKey.Default, TABLE_NAME)
            .save(entity);
    }

    public void delete(CsesSession session, String id) {
        dataSource
            .buildDeleter(session, DataSourceKey.Default, TABLE_NAME)
            .pk(id)
            .delete();
    }
}
```

## 13. 注意事项

### 数据源键

- 统一使用 `DataSourceKey` 常量。
- PostgreSQL 和 MongoDB 模型必须选择正确的数据源。
- 事件订阅中的集合名必须包含完整数据源键。

### 会话上下文

- 不要随意传 `null`。
- 正常请求应传当前 `CsesSession`。
- 系统任务可以根据现有业务约定使用 `CsesSession.System()`。
- 会话可能参与权限、租户、审计字段和 Trace 处理。

### 事务

- 已有 jOOQ 事务时，使用 `.dsl(currentDsl)` 复用事务上下文。
- 不要在同一业务操作中无意混用不同 `DSLContext`。

### 查询对象生命周期

- `ExtendableQuery` 必须使用 `@Prototype`。
- 不要缓存或跨请求复用 Query 实例。

### 关联查询

- 关联注解不会保证每次查询都自动加载关联。
- 需要关联数据时，显式使用 `findAssociations(...)`。
- 大列表加载深层关联时注意 N+1 查询和响应体积。

### 索引

- `@Dbindex` 模型的字段变更可能要求重建索引。
- `@DbIndexIgnore` 应用于不需要检索或不适合序列化的字段。
- 嵌套对象需要确认 `nested = true` 和 Elasticsearch Mapping 一致。

### 安全和配置

- 数据库密码、MongoDB URI 等敏感信息不要写入本文档或 Java 源码。
- 连接信息通过部署环境和 Consul 配置管理。
- 示例配置提交前应移除真实账号、密钥和内部地址。

## 14. 项目内参考入口

| 场景 | 参考文件 |
| --- | --- |
| DataPilot 引擎 | [`CsesDataSourceEngine.java`](server/src/main/java/org/cses/server/common/dataPilot/CsesDataSourceEngine.java) |
| 引擎服务注册 | [`CsesDatasourceEngineService.java`](server/src/main/java/org/cses/server/common/dataPilot/CsesDatasourceEngineService.java) |
| 数据源键 | [`DataSourceKey.java`](server/src/main/java/org/cses/server/common/dataPilot/DataSourceKey.java) |
| PostgreSQL 实体 | [`SpaceEntity.java`](server/src/main/java/org/cses/server/service/taskManage/domain/space/entity/SpaceEntity.java) |
| MongoDB 实体 | [`TaskManageDocConfig.java`](server/src/main/java/org/cses/server/service/taskManage/domain/doc/TaskManageDocConfig.java) |
| CRUD Repository | [`RelationRepositoryImpl.java`](server/src/main/java/org/cses/server/service/relation/impl/persistance/RelationRepositoryImpl.java) |
| 可扩展查询 | [`SpaceQuery.java`](server/src/main/java/org/cses/server/service/taskManage/application/space/query/SpaceQuery.java) |
| 索引模型 | [`TaskEntity.java`](server/src/main/java/org/cses/server/service/taskManage/domain/task/entity/TaskEntity.java) |
| 全局搜索 | [`DocGlobalSearchIndex.java`](server/src/main/java/org/cses/server/service/doc/repositories/searcher/DocGlobalSearchIndex.java) |
| 数据变更拦截 | [`TaskPermissionRecipientInterceptor.java`](server/src/main/java/org/cses/server/service/taskManage/domain/task/notify/TaskPermissionRecipientInterceptor.java) |
| Consul 配置入口 | [`server/src/main/resources/bootstrap-flow-standalone.yaml`](../../server/src/main/resources/bootstrap-flow-standalone.yaml) |
