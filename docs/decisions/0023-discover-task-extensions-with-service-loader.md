# ADR 0023：使用 ServiceLoader 发现 classpath Plugin

## 状态

Superseded by [ADR 0026](0026-use-task-class-as-in-project-plugin.md)

## 背景

ADR 0013 已经建立 Task 类型扩展链路，但原实现把根 SPI 和注册表都限定为 Task，
且插件发现仍只依赖 Micronaut Bean。
这要求插件实现参与 Micronaut 注解处理和 Bean 装配，不适合作为普通 Java JAR
移植，也会使定义物化与执行能力采用不同的扩展入口。

Task 扩展必须同时提供两项能力：

- `TaskExtension`：继承通用 `Plugin`，声明稳定 `type`，并把 Flow 已决定的通用
  字段物化或重建为具体 Task 子类型。
- `WorkerTaskHandler`：执行该具体 Task，并返回 Worker 运行事实。

只扩展前者会导致 Flow 可以部署新 Task，但 Execution 运行时找不到 Handler。

## 备选方案

### 方案一：继续只收集 Micronaut Bean

保留现状，内置插件装配简单，但外部插件必须依赖 Micronaut，不能作为普通 Java
SPI 提供者接入。

### 方案二：根据 YAML type 调用 Class.forName

无需注册文件，但把不可信配置直接转换成类加载请求，绕过允许列表，也无法稳定
处理类型别名、重复注册和 ClassLoader 来源，因此不采用。

### 方案三：Bean 与 ServiceLoader 统一进入现有注册和分派链路

内置实现继续使用 Micronaut Bean；classpath 外部 JAR 使用 Java
`ServiceLoader<Plugin>` 提供所有插件扩展点的实现，
`ServiceLoader<WorkerTaskHandler>` 提供 Worker 执行 Adapter。两类来源在启动时
合并，之后继续使用只读注册表和 Worker 分派器。

## 决策

采用方案三。

- `Plugin` 是所有插件的通用根 Interface；具体扩展点 Interface 继承 `Plugin`，
  并通过 `extensionPoint()` 声明独立 type 命名空间。Task 使用
  `TaskExtension` 扩展点。
- `PluginRegistry` 在创建时合并 Micronaut 注入的 `Plugin` 与当前线程上下文
  ClassLoader 中由 `ServiceLoader<Plugin>` 发现的提供者；上下文 ClassLoader
  为空时回退到注册表自身的 ClassLoader。
- `Plugin`、`PluginLoader`、`PluginRegistry`、`TaskExtension`、
  `TaskTypeDispatcher` 与 `RegisteredTaskTypeDispatcher` 统一放在
  `core/plugins`；`extensions` 只保存具体 Task、TaskExtension 和
  WorkerTaskHandler 实现。
- `WorkerDispatcher` 使用相同规则合并 Micronaut Bean 与
  `ServiceLoader<WorkerTaskHandler>`，保证定义能力与执行能力可以由同一个外部
  插件 JAR 提供。
- ServiceLoader 提供者必须是可由 Java SPI 创建的公共实现。构造阶段不允许建立
  网络连接、启动线程或执行其他副作用；运行依赖通过现有 `WorkerContext` 等窄
  接口获取。
- 外部 JAR 分别通过以下服务描述文件注册实现，每行一个完整类名：
  - `META-INF/services/org.cses.flow.core.plugins.Plugin`
  - `META-INF/services/org.cses.flow.worker.WorkerTaskHandler`
- 同一实现不能同时作为 Micronaut Bean 和 ServiceLoader 提供者重复装配。同一
  扩展点内规范化后的 type 重复使应用启动失败；不同扩展点可以复用相同 type。
  多个 Handler 同时支持同一 Task 时仍在分派处明确失败。
- YAML 中的 Task `type` 只查询 `PluginRegistry` 的 TaskExtension 扩展点，不允许
  作为类名或 JAR 路径使用。
- 注册表和 Handler 集合在应用装配完成后保持只读，不支持运行时修改。

## 范围限制

本决策只支持应用启动时已经位于 classpath 的插件 JAR，不包括：

- 运行时插件目录扫描、下载、安装或卸载。
- 独立或子优先 ClassLoader、依赖隔离和热关闭。
- 插件版本选择、依赖解析、签名和权限模型。
- 远程插件仓库。

引入以上能力时必须在当前注册表之外增加插件包管理层，并单独记录架构决策；不能
让 Flow YAML 承担插件来源选择。

## 理由

- 保留现有 Task 领域对象、身份生成、不变量和持久化重建链路；Flow 只消费
  `core/plugins` 的分派门面，不在领域包中声明插件运行 Interface。
- 通用 Plugin 根 Interface 与注册表不依赖 Task；新增扩展点无需复制发现、注册、
  规范化和重复检查逻辑。
- 外部插件只依赖 Java SPI 与项目稳定扩展 Interface，不依赖 Micronaut 注解。
- 定义与执行两侧使用一致的发现模型，避免形成只能部署、不能运行的半插件。
- 所有类型仍经过唯一注册表，重复类型、未知类型和缺失实现继续明确失败。

## 后果

- 内置 AUTO、PAUSE、PARALLEL 插件继续作为 Micronaut Bean，无需增加服务描述
  文件。
- Core 不依赖任何具体 extension；新增扩展不会把发现、注册或分派逻辑带回
  `extensions`。
- 外部 Task 插件 JAR 必须同时提供具体 Task、TaskExtension、WorkerTaskHandler
  及两个服务描述文件，并在应用启动前进入 classpath。
- ServiceLoader 配置错误在注册或分派器创建时使应用装配失败，不会延迟到某次
  Flow 执行。
- 外部插件需要依赖注入时，仍应选择 Micronaut Bean 装配；ServiceLoader 路径只
  提供无容器的可移植最小能力。
- ADR 0013 中“当前只收集 Micronaut classpath Bean”的范围由本 ADR 修订；其
  YAML、Flow 物化和注册表职责划分继续有效。
