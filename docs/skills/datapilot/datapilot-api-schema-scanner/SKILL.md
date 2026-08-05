---
name: datapilot-api-schema-scanner
description: |
  Inspect and use ApiSchemaScanner in cloud-datapilot. Use when the user asks to
  "scan controller to schema api", "generate request schema", "generate response schema",
  "debug ApiSchemaScanner", "解析 API Schema", "生成前端接口元数据", or works with files under
  `src/main/java/org/dataPilot/micronaut/schemaApi/**` and `src/test/java/org/dataPilot/micronaut/schemaApi/**`.
  Scope: Micronaut controller scanning, request/response schema inference, and SchemaApi collection registration only;
  pgrest belongs to datapilot-schema / pgrest tests, generic metadata browsing belongs to datapilot-schema.
argument-hint: "[controller-class|datasource-key|collection-name]"
allowed-tools: Read Grep Glob Bash
paths: "src/main/java/org/dataPilot/micronaut/schemaApi/**"
version: 0.1.0
---

# DataPilot ApiSchemaScanner

一句话目的：理解、注册、调试和使用 `ApiSchemaScanner`，把带有 `@ApiSchema` 的 Micronaut Controller 扫描成 `SchemaApiCollection`，并得到前端可消费的 request/response schema。

## When to Use

- "这个 Controller 为什么没有被扫描进 schema api？"
- "怎么用 `@ApiSchema` 把一个 Controller 注册成 schema api？"
- "帮我看 `ApiSchemaScanner` 会生成哪些 request/response 字段"
- "为什么 `@UserSession` / `HttpRequest` 没有出现在前端 schema 里？"
- "我要给前端一个接口元数据结构，应该怎么走 `ApiSchemaScanner`"
- "SchemaApiCollection 是怎么注册进引擎的？"
- 编辑 `src/main/java/org/dataPilot/micronaut/schemaApi/**` 或相关 lightweight tests 时

## Key Types

- `ApiSchemaScanner`
  入口类，负责扫描 `@ApiSchema`、收集 `ApiOperation`、注册到 `SchemaApiDataSource`
- `ApiSchema`
  声明式注解；类级别控制 collection 元信息，方法级别控制 operation 元信息
- `SchemaApiDataSource` / `SchemaApiCollectionOption`
  Scanner 最终写入的目标数据结构
- `SchemaDefinition` / `SchemaFieldOption` / `FieldOption`
  request/response schema 的核心承载类型

## Workflow

1. 先确认扫描入口是 `ApiSchemaScanner.scanAndRegister()`
2. 定位目标 Controller 是否具备类级别 `@ApiSchema`
3. 检查方法是否同时满足：
   - 是 HTTP 方法
   - 具备方法级 `@ApiSchema`
4. 看 `generateRequestSchemaAndParameters()`：
   - `query/header/path/cookie` 进入 `parameters`
   - `body` 进入 `requestSchema`
   - 特殊参数会被 `isSpecialParameter()` 跳过
5. 看 `generateResponseSchema()`：
   - 简单标量直接映射
   - 集合返回值走 `items`
   - 复杂对象走嵌套字段抽取
6. 最后确认 `registerCollectionToEngine()` 是否把结果注册到了 `SchemaApiDataSource`

## Registration Guide

### 前置条件

要让 `@ApiSchema` 真正注册成功，至少要满足两件事：

- 引擎里已经存在一个 `SchemaApiDataSource`
- 目标 Controller 是 Micronaut Bean，并且类和方法上的注解都齐全

默认 `@ApiSchema.dataSourceKey()` 是 `system.api`，所以最常见做法就是先注册一个 key 为 `system.api` 的 `SchemaApiDataSource`。

### 1. 先注册 `SchemaApiDataSource`

```java
@Override
public void registerDataSources() {
    SchemaApiDataSourceOption option = new SchemaApiDataSourceOption();
    option.setKey("system.api");
    option.setName("system.api");
    option.setTitle("Schema API");
    defineSchemaApiDataSource(option);
}
```

如果这里没有对应的数据源，`ApiSchemaScanner.registerCollectionToEngine()` 不会把扫描结果注册进去。

### 2. 在 Controller 类上加 `@ApiSchema`

类级别注解负责 collection 维度的信息：

```java
@Controller("/taskManage/task")
@ApiSchema(
    name = "task_v3",
    title = "任务管理 API",
    dataSourceKey = "system.api",
    tags = {"task_v3"}
)
public class TaskController {
}
```

这里最重要的字段是：

- `name`
  最终生成的 collection 名
- `dataSourceKey`
  要注册到哪个 `SchemaApiDataSource`
- `title` / `tags` / `description`
  给前端和导出文档用

### 3. 在方法上也加 `@ApiSchema`

只有类级别注解还不够；真正生成 operation 的是方法级 `@ApiSchema`。

```java
@Post("/create")
@ApiSchema(name = "createTask", description = "创建新任务")
public TaskEntity createTask(@Body CreateTaskCmd command) {
    return new TaskEntity();
}
```

如果方法上没有 `@ApiSchema`，当前 `ApiSchemaScanner` 不会注册这个 operation。

### 4. body / response 类型怎么写

- `@Body` 参数会进入 `requestSchema`
- `@QueryValue` / `@PathVariable` / `@Header` 等会进入 `parameters`
- 返回值类型会被用于生成 `responseSchema`

最小示例：

```java
public static class CreateTaskCmd {
    public String title;
    public List<BlockItem> blocks;
}

public static class BlockItem {
    public String taskId;
    public Boolean isPre;
}

@Table(name = "task_v3", dataSourceKey = "default", camelCase = false)
public static class TaskEntity {
    public String id;
    public String title;
}
```

### 5. 什么时候会自动跳过参数

像下面这些参数，一般不会出现在前端 schema 里：

- `@UserSession`
- `HttpRequest`
- `Principal`
- 安全/上下文类参数

这是 `isSpecialParameter()` 的预期行为，不是漏扫。

## Debug Checklist

- [ ] Controller 类上是否有 `@ApiSchema`
- [ ] 目标方法上是否也有 `@ApiSchema`
- [ ] 方法是否真的是 Micronaut HTTP endpoint
- [ ] `SchemaApiDataSource` 是否已经提前注册
- [ ] `dataSourceKey` 指向的是否是 `SchemaApiDataSource`
- [ ] 参数被跳过时，是否命中了 `isSpecialParameter()`
- [ ] body 字段不完整时，是否命中了嵌套类型/集合类型推断分支
- [ ] response schema 不完整时，是否是返回值泛型没有被正确识别

## Common Cases

### 1. 方法没被扫描到

- 只在类上有 `@ApiSchema` 不够；方法本身也要有 `@ApiSchema`
- 非 HTTP 方法不会进入扫描结果

### 0. 根本没注册出来

- 先检查有没有调用 `defineSchemaApiDataSource(...)`
- 再检查类上的 `dataSourceKey` 是否真指向这个数据源
- 再看 `scanAndRegister()` 是否在引擎启动流程里被调用

### 2. `@UserSession` / 上下文参数不该暴露

- 这类参数应该由 `isSpecialParameter()` 过滤掉
- 如果仍然出现在 schema 中，优先检查注解类型或参数包装类型有没有变

### 3. body 对象需要展开成前端友好结构

- 复杂对象优先走 `extractSchemaFields(...)`
- 集合/数组类型看是否会落到 `ArrayFieldOption`、`ArrayJsonOption`
- 已注册 collection 的实体对象，优先复用已有字段元数据

## Example

```text
User: 帮我看看 TaskControllerReplica 为什么 createTask 的 context 没有出现在 schema 里
Skill: 检查 ApiSchemaScanner.isSpecialParameter()，确认 @UserSession 参数被主动过滤，这是预期行为；再检查 requestSchema 中 task / blocks / related 的推断结果是否完整
```

```text
User: 我想把 TaskController 注册成 schema api 给前端用
Skill: 先确认已经注册 system.api 的 SchemaApiDataSource；然后在 Controller 类上加 @ApiSchema(name, dataSourceKey)，在 HTTP 方法上逐个加 @ApiSchema(name, description)，最后检查 scanAndRegister() 是否会把 operation 注册进 SchemaApiCollection
```

## Common Mistakes

| Mistake | Fix |
|---|---|
| 只加了类级 `@ApiSchema` 就以为会生成 operation | 方法级 `@ApiSchema` 也必须加 |
| 没有先注册 `SchemaApiDataSource` | 先 `defineSchemaApiDataSource()`，再谈扫描注册 |
| 只看类级别 `@ApiSchema`，忽略方法级注解 | 方法级 `@ApiSchema` 才决定 operation 是否注册 |
| 把 `parameters` 和 `requestSchema` 混在一起理解 | `parameters` 是传输层参数，`requestSchema` 是 body |
| 看到字段缺失就直接改前端 | 先确认 `isSpecialParameter()`、`detectParameterLocation()`、`extractSchemaFields()` 的分支 |
| 不验证注册目标的数据源类型 | `registerCollectionToEngine()` 只会注册到 `SchemaApiDataSource` |
