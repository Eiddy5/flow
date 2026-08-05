---
name: datapilot-schema-api
description: |
  cloud-datapilot Schema API & PgREST：将外部 HTTP API 定义为集合、OperationType/HttpMethod 映射、
  OpenAPI/JSON Schema 导出、PgREST 视图/函数集合、PgrestSqlParser SQL 解析、
  物化视图刷新、@ApiSchema/@PgFunctionSchema 注解。Use when the user asks to
  "Schema API", "外部API", "PgREST", "PostgreSQL function", "物化视图",
  "OpenAPI export", "parseFunction", "ApiSchema".
argument-hint: "[operation] [api|pgrest]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.java"
version: 1.0.0
---

# Schema API 与 PgREST

面向 SDK 消费者，覆盖如何将外部 HTTP API 和 PostgreSQL 视图/函数定义为 DataPilot 集合。

## 1. Schema API — 外部 HTTP API 转集合

### 1.1 概念

将外部 HTTP API 包装为 DataPilot 集合，使其可通过统一的 `DataSourceEngine` 接口访问。

### 1.2 Java API 定义

```java
import org.dataPilot.option.schema.SchemaApiCollectionOption;
import org.dataPilot.option.schema.ApiOperation;
import org.dataPilot.option.schema.Params;

// 定义 API 集合
SchemaApiCollectionOption option = SchemaApiCollectionOption.builder()
    .name("external_users")
    .dataSourceKey("externalApi")
    .baseUrl("https://api.example.com/v1")
    .title("外部用户 API")
    .description("包装外部用户服务")
    // 查询操作
    .addOperation(ApiOperation.builder()
        .name("query")
        .operationType(OperationType.QUERY)
        .method(HttpMethod.POST)
        .path("/users/search")
        .params(Params.Of(
            Param.of("page", ParameterLocation.QUERY, DataType.INTEGER),
            Param.of("size", ParameterLocation.QUERY, DataType.INTEGER)
        ))
        .requestSchema(schema)
        .responseSchema(schema)
        .build())
    // 创建操作
    .addOperation(ApiOperation.builder()
        .name("create")
        .operationType(OperationType.CREATE)
        .method(HttpMethod.POST)
        .path("/users")
        .build())
    // 获取单条
    .addOperation(ApiOperation.builder()
        .name("get")
        .operationType(OperationType.GET)
        .method(HttpMethod.GET)
        .path("/users/{id}")
        .params(Params.Of(
            Param.of("id", ParameterLocation.PATH, DataType.STRING)
        ))
        .build())
    // 更新操作
    .addOperation(ApiOperation.builder()
        .name("update")
        .operationType(OperationType.UPDATE)
        .method(HttpMethod.PUT)
        .path("/users/{id}")
        .build())
    // 删除操作
    .addOperation(ApiOperation.builder()
        .name("delete")
        .operationType(OperationType.DELETE)
        .method(HttpMethod.DELETE)
        .path("/users/{id}")
        .build())
    // 公共请求头
    .addCommonHeader("Authorization", "Bearer some-token")
    .addCommonHeader("X-API-Key", "secret-key")
    .auth("Bearer")
    .build();

// 注册到数据源
SchemaApiDataSource ds = engine.defineSchemaApiDataSource(
    new SchemaApiDataSourceOption("externalApi", "外部 API 数据源"));
ds.addCollection(option);
```

### 1.3 支持的 OperationType

```java
CREATE, QUERY, GET, UPDATE, DELETE, SAVE, COUNT,
AGGREGATE, BATCH_CREATE, BATCH_SAVE, CUSTOM
```

### 1.4 HTTP Method 与参数位置

```java
// HttpMethod: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
// ParameterLocation: QUERY, HEADER, PATH, COOKIE, BODY
```

### 1.5 Micronaut 注解方式

```java
import org.dataPilot.common.annotation.ApiSchema;
import org.dataPilot.common.annotation.ApiSchemaMetadata;

@ApiSchema(name = "userApi", title = "用户API", dataSourceKey = "externalApi")
@Controller("/api/users")
public class UserApiController {

    @ApiSchemaMetadata(
        operationName = "query",
        operationType = "QUERY",
        httpMethod = "POST",
        path = "/search"
    )
    @Post("/search")
    public ListResult<UserDTO> search(@Body SearchRequest req) {
        // 实际实现
    }

    @ApiSchemaMetadata(
        operationName = "get",
        operationType = "GET",
        httpMethod = "GET",
        path = "/{id}"
    )
    @Get("/{id}")
    public UserDTO getById(@PathVariable String id) {
        // 实际实现
    }
}

// 自动扫描注册: ApiSchemaScanner.scanAndRegister()
```

---

## 2. OpenAPI / JSON Schema 导出

### 2.1 导出 OpenAPI 3.0

```java
import org.dataPilot.option.schema.openapi.OpenApiExporter;

// 导出为 JSON 字符串
String openApiJson = OpenApiExporter.export(schemaApiCollection);

// 导出为 Map (可进一步处理)
Map<String, Object> openApiMap = OpenApiExporter.exportAsMap(schemaApiCollection);
```

### 2.2 导出 JSON Schema (Draft 7)

```java
import org.dataPilot.option.schema.jsonschema.JsonSchemaExporter;

// 导出为 JSON 字符串
String jsonSchema = JsonSchemaExporter.export(schemaDefinition);

// 导出为 Map
Map<String, Object> schemaMap = JsonSchemaExporter.exportAsMap(schemaDefinition);
```

---

## 3. PgREST — PostgreSQL 视图/函数

### 3.1 定义 PgrestView 集合

```java
import org.dataPilot.option.pgrest.PgrestViewCollectionOption;

PgrestViewCollectionOption option = PgrestViewCollectionOption.pgrestBuilder()
    .name("order_summary")
    .dataSourceKey("analyticsDb")
    .title("订单汇总视图")
    .viewName("v_order_summary")          // 已存在的视图名
    // 或者指定创建 SQL
    .viewSql("""
        CREATE VIEW v_order_summary AS
        SELECT customer_id, COUNT(*) as order_count, SUM(amount) as total_amount
        FROM orders GROUP BY customer_id
        """)
    .materialized(true)                    // 物化视图
    .allowRefresh(true)                    // 允许刷新
    .apiPath("/api/order_summary")         // 可通过此路径访问
    .description("按客户汇总的订单数据")
    .build();

// 注册
ds.addCollection(option);
```

### 3.2 定义 RPC 函数集合

```java
PgrestViewCollectionOption option = PgrestViewCollectionOption.pgrestBuilder()
    .name("calculate_discount")
    .dataSourceKey("salesDb")
    .rpc(true)
    .functionName("calculate_discount")
    .functionSignature("calculate_discount(customer_id int, amount numeric)")
    .functionSql("""
        CREATE OR REPLACE FUNCTION calculate_discount(
            customer_id int, amount numeric
        ) RETURNS numeric LANGUAGE plpgsql AS $$
        BEGIN
            RETURN amount * 0.9;
        END;
        $$;
        """)
    .functionReturnType("numeric")
    .functionLanguage("plpgsql")
    // 注册参数元数据
    .rpcParamType("customer_id", "integer")
    .rpcParamDescription("customer_id", "客户 ID")
    .rpcParamExample("customer_id", 42)
    .rpcParamType("amount", "number")
    .rpcParamDescription("amount", "订单金额")
    // Schema
    .requestSchema(requestSchema)
    .responseSchema(responseSchema)
    .build();
```

### 3.3 Micronaut 注解方式

```java
import org.dataPilot.common.annotation.PgFunctionSchema;

@PgFunctionSchema(
    functionName = "calculate_discount",
    functionSignature = "calculate_discount(customer_id int, amount numeric)",
    dataSourceKey = "salesDb",
    collectionName = "discount_calculator",
    description = "根据客户和金额计算折扣"
)
public class DiscountCalculator {
    // 此类作为集合元数据的载体
}
```

---

## 4. PgrestSqlParser — PostgreSQL SQL 解析器

### 4.1 全部方法

```java
import org.dataPilot.option.pgrest.PgrestSqlParser;

String sql = "CREATE OR REPLACE FUNCTION public.f(a int, b text) RETURNS json ...";

// 解析函数签名 → "f(a int, b text)"
String sig = PgrestSqlParser.parseFunctionSignatureFromSql(sql);

// 从签名获取函数名 → "f"
String name = PgrestSqlParser.parseFunctionNameFromSignature("f(a int, b text)");

// 转为 API 名 → "f" (去掉 schema 前缀)
String apiName = PgrestSqlParser.toApiFunctionName("public.f");

// 解析参数 → { "a": "int", "b": "text" }
LinkedHashMap<String, String> params =
    PgrestSqlParser.parseSignatureParams("f(a int, b text)");

// 从函数体推断返回字段 (解析 json_build_object)
LinkedHashMap<String, String> fields =
    PgrestSqlParser.parseJsonBuildObjectFields(sql);

// 解析返回类型 → "json" / "numeric" / "setof record"
String retType = PgrestSqlParser.parseReturnTypeFromSql(sql);

// 解析语言 → "sql" / "plpgsql"
String lang = PgrestSqlParser.parseLanguageFromSql(sql);

// 判 CREATE FUNCTION
boolean isFunc = PgrestSqlParser.looksLikeCreateFunctionSql(sql);

// 解析函数体
String body = PgrestSqlParser.parseFunctionBodyFromSql(sql);

// PG 类型 → OpenAPI 类型
String oapiInt = PgrestSqlParser.toOpenApiType("integer");    // → "integer"
String oapiNum = PgrestSqlParser.toOpenApiType("numeric");    // → "number"
String oapiStr = PgrestSqlParser.toOpenApiType("text");       // → "string"
```

### 4.2 HTTP API

```bash
curl -X POST 'http://datapilot.local/dataPilot/pgrest/parseFunction' \
  -H 'Content-Type: application/json' \
  -d '{
    "sql": "CREATE FUNCTION calc(a int, b text) RETURNS json LANGUAGE plpgsql AS $$ BEGIN ... END; $$"
  }'
# → { "functionName": "calc", "params": { "a": "int", "b": "text" }, "returnType": "json", ... }
```

---

## 5. 物化视图刷新

```java
// 仅 PgrestViewCollection (materialized=true) 支持
PgrestViewRepository<?> repo = (PgrestViewRepository<?>) collection.repository();
repo.refresh(context);  // 执行 REFRESH MATERIALIZED VIEW
```

---

## 6. 完整场景: 注册外部用户 API

```java
// 1. 定义 Schema API 数据源
SchemaApiDataSourceOption dsOpt = new SchemaApiDataSourceOption();
dsOpt.key = "externalApi";
dsOpt.name = "外部 API 服务";

SchemaApiDataSource ds = engine.defineSchemaApiDataSource(dsOpt);

// 2. 定义用户 API 集合
SchemaApiCollectionOption userApi = SchemaApiCollectionOption.builder()
    .name("externalUsers")
    .dataSourceKey("externalApi")
    .baseUrl("https://user-service.internal/api/v1")
    .title("外部用户")
    .addOperation(ApiOperation.builder()
        .name("query").operationType(OperationType.QUERY)
        .method(HttpMethod.POST).path("/users/search").build())
    .addOperation(ApiOperation.builder()
        .name("get").operationType(OperationType.GET)
        .method(HttpMethod.GET).path("/users/{id}").build())
    .addCommonHeader("X-Service-Token", "internal-secret")
    .build();

ds.addCollection(userApi);

// 3. 使用 — 与普通集合完全一致的 API
ListResult<Model> users = engine.buildQuery(ctx, "externalApi", "externalUsers")
    .filter(QueryCondition.field("department").eq("engineering"))
    .pageSize(20)
    .list();
// 内部自动: POST https://user-service.internal/api/v1/users/search
```
