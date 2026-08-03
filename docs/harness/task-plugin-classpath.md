# Classpath Task 插件接入手册

## 适用范围

本手册用于把一个普通 Java 插件 JAR 作为新的 Flow Task 类型放入应用启动
classpath。它不覆盖运行时安装、热卸载、插件版本或独立 ClassLoader。

## 插件 JAR 内容

一个完整的 Task 扩展必须同时包含：

1. 继承 `Task` 的具体不可变 Task 子类型，并提供静态 `create(...)` 与
   `rehydrate(...)`；该子类型恰好实现 `RunnableTask` 或 `BranchTask`。
2. 实现 `TaskExtension` 的公共无参类，声明稳定 `type`，调用具体 Task 的创建或
   重建方法，并显式编码类型专有 properties。
3. 一个 Java SPI 服务描述文件。

RunnableTask 把具体执行逻辑写在自身 `run(RunContext)` 中并返回 RunResult；
BranchTask 不写实际执行逻辑，由 Executor 直接处理。两者都不提供
WorkerTaskHandler。

Plugin 服务文件：

```text
src/main/resources/META-INF/services/org.cses.flow.core.plugins.Plugin
```

内容：

```text
com.example.flow.plugin.NotificationTaskPlugin
```

文件每行登记一个实现完整类名。ServiceLoader 实现不要同时声明为 Micronaut
Bean，否则会被视为重复装配。

## 装配与验证

1. 在应用启动前把插件 JAR 加入 runtime classpath。
2. 启动应用；服务描述文件错误、空 type 或重复 type 必须在装配阶段失败。
3. 部署包含新 `type` 的 Flow，确认它被物化为具体 Task 子类型。
4. 启动 Execution：RunnableTask 应由 Worker 直接调用其 run；BranchTask 应只由
   Executor 处理且不产生 WorkerTask。
5. 保存并重新读取 Flow Reversion，确认相同插件可以通过 `rehydrate(...)` 恢复
   类型属性。
6. 移除插件 JAR 后读取包含该类型的持久化 Flow，确认系统明确报告插件缺失，不能
   回退为通用 Task。

## 安全和生命周期约束

- YAML 只能声明已经注册的稳定 `type`，不能声明实现类名或插件 JAR 路径。
- 插件 JAR 来源必须由部署流程审核，不能由 Flow 定义下载或扫描。
- ServiceLoader 构造器不得连接网络、启动线程或注册全局可变状态。
- 插件需要容器依赖注入时使用 Micronaut Bean 装配，不使用 ServiceLoader 路径。
- TaskExtension 不生成 Task `id`、不解释 YAML，也不改变 `parentId`；这些事实仍由
  Flow 聚合决定。
- RunnableTask 的 RunContext 不包含 Execution、TaskRun 或 WorkerTask；插件不能
  直接推进流程状态。
