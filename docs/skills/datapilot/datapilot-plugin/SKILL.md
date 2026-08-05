---
name: datapilot-plugin
description: |
  cloud-datapilot 插件系统：EnginePlugin/PrivateEnginePlugin 接口、插件生命周期（beforeLoad/load）、
  内置插件（DatasourceManagerPlugin/CustomFormFieldPlugin/CollectionLogPlugin）、
  插件注册方式（@DSPlugin 注解/手动 registerPlugin）。Use when the user asks to
  "插件", "plugin", "EnginePlugin", "DSPlugin", "registerPlugin", "扩展引擎".
argument-hint: "[pluginName]"
allowed-tools: Read Grep Glob Bash
paths: "**/*.java"
version: 1.0.0
---

# 插件系统

面向 SDK 消费者，覆盖 cloud-datapilot 的插件体系：接口定义、生命周期、内置插件、注册方式。

## 1. EnginePlugin 接口

```java
import org.dataPilot.DataSourceEngine;
import paas.auth.Session;
import paas.auth.User;

public interface EnginePlugin<C extends Session<U>, U extends User> {

    // 作用范围 — 返回 Set.of("*") 表示全局插件
    Set<String> getScope();

    // 引擎加载前回调
    void beforeLoad(DataSourceEngine<C, U> engine);

    // 引擎加载回调 (核心逻辑)
    void load(DataSourceEngine<C, U> engine);

    // 自动注册 @OnEvent/@OnDbEvent 注解的监听器
    default void registerAnnotatedListeners(DataSourceEngine<C, U> engine) {
        // 默认扫描并注册
    }
}
```

## 2. PrivateEnginePlugin 接口

用于需要访问特定引擎子类型的插件 (类型安全)：

```java
public interface PrivateEnginePlugin<C extends Session<U>, U extends User,
                                     E extends DataSourceEngine<C, U>>
        extends EnginePlugin<C, U> {

    // 类型安全的 beforeLoad
    void beforeLoad_(E engine);

    // 类型安全的 load
    void load_(E engine);
}
```

---

## 3. 内置插件

| 插件 | 全限定类名 | 作用 |
|------|----------|------|
| `DatasourceManagerPlugin` | `org.dataPilot.plugin.DatasourceManagerPlugin` | 注册元数据管理内部表 (`datasource_collection`, `datasource_field`, `collection_event`) |
| `CustomFormFieldPlugin` | `org.dataPilot.plugin.CustomFormFieldPlugin` | 注册自定义表单字段模型 |
| `CollectionLogPlugin` | `org.dataPilot.plugin.CollectionLogPlugin` | 开启集合操作日志 (当 `openLog=true` 时) |

---

## 4. 插件注册

### 4.1 方式一: @DSPlugin 注解 (自动扫描)

```java
import org.dataPilot.common.annotation.DSPlugin;

@DSPlugin(engineKey = "default")
public class MyPlugin implements EnginePlugin<Context<User>, User> {

    @Override
    public Set<String> getScope() {
        return Set.of("*");  // 全局作用域
        // 或 Set.of("orders", "customers") — 只对特定集合生效
    }

    @Override
    public void beforeLoad(DataSourceEngine<Context<User>, User> engine) {
        // 在引擎加载内置插件之前执行
        // 可用于: 注册自定义字段类型、预初始化配置
        engine.registerField("myType", new MyCustomField());
    }

    @Override
    public void load(DataSourceEngine<Context<User>, User> engine) {
        // 在引擎加载内置插件之后执行
        // 可用于: 注册额外数据源、集合
        engine.defineDbDataSource(myDataSourceOption);
    }
}

// Micronaut 会在启动时自动扫描 @DSPlugin 注解的 Bean
```

### 4.2 方式二: 手动注册

```java
DataSourceEngine<Context<User>, User> engine = DataSourceEngine.use("default", MyEngine.class);

// 手动注册插件
engine.registerPlugin(new MyPlugin());

// 注册多个
engine.registerPlugin(new PluginA());
engine.registerPlugin(new PluginB());
```

---

## 5. 插件生命周期

```
引擎启动 (DataSourceEngine.start())
  │
  ├── 1. 收集所有 @DSPlugin Bean
  │
  ├── 2. 遍历插件 → beforeLoad(engine)
  │     └── 注册字段类型、预初始化等
  │
  ├── 3. 加载内置插件 (DatasourceManagerPlugin, CustomFormFieldPlugin, CollectionLogPlugin)
  │     └── 注册元数据表、自定义表单模型、操作日志
  │
  ├── 4. 遍历插件 → load(engine)
  │     └── 注册数据源、集合、全局搜索源等
  │
  ├── 5. 遍历插件 → registerAnnotatedListeners(engine)
  │     └── 注册 @OnDbEvent 监听器
  │
  └── 6. 执行关联验证 (可选)
```

---

## 6. 自定义插件示例

```java
import org.dataPilot.DataSourceEngine;
import org.dataPilot.common.annotation.DSPlugin;
import paas.auth.Context;
import paas.auth.User;

@DSPlugin(engineKey = "default")
public class TenantIsolationPlugin implements EnginePlugin<Context<User>, User> {

    @Override
    public Set<String> getScope() {
        return Set.of("*");  // 全局
    }

    @Override
    public void beforeLoad(DataSourceEngine<Context<User>, User> engine) {
        // 注册自定义 company_id 注入逻辑
        engine.registerField("companyId", new CompanyIdField() {
            @Override
            public void beforeCreate(Model model, Context<User> ctx) {
                model.put("companyId", ctx.getCompanyId());
                model.put("tenantId", ctx.getTenantId());  // 扩展: 注入租户 ID
            }
        });
    }

    @Override
    public void load(DataSourceEngine<Context<User>, User> engine) {
        // 注册租户隔离的集合视图
        // ...
    }
}
```

---

## 7. 注意事项

| 陷阱 | 正确做法 |
|------|---------|
| 插件未声明 scope | 始终返回明确的 `Set<String>`，`Set.of("*")` 表示全局 |
| beforeLoad 中操作未初始化的引擎 | beforeLoad 中只能注册字段类型、预初始化配置 |
| load 中重复注册 | 检查数据源/集合是否已存在再注册 |
| 插件顺序依赖 | 使用 `@Order` 或 `order()` 控制执行顺序 |
| 插件异常未处理 | 插件异常会导致引擎启动失败 |
