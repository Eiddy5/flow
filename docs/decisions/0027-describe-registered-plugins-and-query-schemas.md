# ADR 0027：描述已注册插件并按需生成定义 Schema

## 状态

Accepted

本 ADR 修订 ADR 0026 中“`@Plugin` 不携带元数据”和“注册表只保存类型到具体类
映射”的局部条款。ADR 0026 关于项目内发现、canonical class name、严格反序列化、
Task 构造、运行能力和持久化的其余决定继续有效。

## 背景

ADR 0026 已能在 YAML 反序列化时选择具体 Task，但编排工具仍不知道应用当前注册了
哪些 Task，也无法获知某个 Task 接受哪些公共字段和专有字段。若页面继续维护
AUTO、PAUSE、PARALLEL 等硬编码清单，每新增一个项目内插件都要同步修改前端，插件
不能形成“注册、发现、选择、配置”的自闭环。

当前阶段支持 Flow 自带插件，以及由宿主应用或普通依赖在构建期贡献到同一个
ApplicationContext 的 Task 插件；不增加运行时插件目录、独立 ClassLoader、安装
卸载或插件版本机制。插件元信息只描述定义能力，不承载 Demo 布局、画布行为或
运行时 UI 组件。

## 备选方案

### 方案一：页面维护 Task 清单和字段表单

实现直接，但页面和插件类会形成两套类型及字段事实，新增插件无法自动进入编排工具。

### 方案二：在 `@Plugin` 中手写完整字段 Schema

插件可自描述，但 Java 字段、Jackson 绑定、Bean Validation 与手写 Schema 容易
漂移，插件作者需要重复维护同一份定义契约。

### 方案三：注册表聚合插件元信息，查询时从具体类生成 Schema

启动时只完成类发现、校验和不可变目录聚合；查询详情时根据真实 Jackson、Validation
和 Swagger 注解生成标准 JSON Schema，并缓存成功结果。

## 决策

采用方案三，并保留“全局 Registry + Java package + 类元信息”的两级组织，只使用
插件类本身已经提供的事实。

### 元信息与注册表

- `@Plugin` 增加可选 `title` 和 `description`。空 title 回退为具体类 simple name，
  空 description 统一为 `""`；不提供 alias、icon 或 UI 元数据。
- `PluginMetadata<T>` 描述一个已注册具体类：`type` 是具体 Class，`baseClass` 是
  `Task` 等能力基类，并保存规范化后的 title 和 description；`packageName` 直接由
  `type.getPackageName()` 派生，不由插件作者重复声明。
- 第一版注册表只支持 `Task` 这一种插件能力；带 `@Plugin` 但不是 Task 的类会在启动
  校验中被拒绝。未来增加其他能力时，需要显式扩展 `RegisteredPlugin` 的聚合结构。
- `RegisteredPlugin` 描述一个真实 Java package，按 `packageName` 聚合对应的全部
  `PluginMetadata<Task>`；它不保存 Plugin Bean 实例，也不创建没有插件的虚拟分组。
- `PluginRegistry` 是全局只读接口，只公开插件包列表、精确类型元信息查询和能力受限
  的类解析。`DefaultPluginRegistry` 在 Micronaut 启动期消费 `Collection<Plugin>`，
  完成 ADR 0026 的全部注册校验后，按具体类的 `Class#getPackageName()` 一次性构造
  不可变快照。
- package 按完整 package name、Task 元信息按 canonical class name 确定性排序；
  不依赖 DI 顺序、title 或页面顺序。插件必须属于具名 Java package。
- 注册失败仍阻止应用启动。类型解析继续只接受精确 canonical class name，不恢复
  AUTO、PAUSE、PARALLEL 等短类型或任何别名。

### 定义 Schema

- `PluginSchemaGenerator` 使用 Victools JSON Schema Generator 4.38、Jackson、
  Jakarta Validation 和 Swagger 2 模块生成 JSON Schema Draft 7。
- Schema 描述服务实际接受的具体 Task 定义：包含 Task 公共字段和插件专有字段；
  排除系统字段 `id`；根 `type` 必填且 `const` 固定为具体类 canonical name；未知
  字段通过 `additionalProperties: false` 拒绝。
- Task 已有默认值的 `inputs`、`outputs`、`route`、`dependOn` 和 `tasks` 不因
  `@NotNull` 被错误标记为必填。插件字段可用 Bean Validation 声明约束，并用
  `io.swagger.v3.oas.annotations.media.Schema` 补充 title、description 和 example。
- `tasks` 继续表示可放置任意 Task 的递归位置，不把当前注册类型展开成 `oneOf`。
  编排工具选择子 Task 后，单独查询该具体类型的 Schema。
- Schema 只在插件详情查询时生成。成功结果按具体类在进程生命周期内并发安全地缓存；
  生成异常不缓存，也不影响应用启动，在发起该详情查询时直接暴露并由框架统一处理。

### 查询边界与 HTTP

- `PluginService` 是 Core 对外的全局只读查询入口。列表查询只读取注册快照；详情查询
  组合 `PluginMetadata` 和惰性 Schema。
- `GET /api/plugins` 返回按真实 Java package 分组的注册插件及 Task 元信息；分组和
  每个 Task 元信息都显式返回 `packageName`；
  `GET /api/plugins/{canonicalType}` 返回单个插件元信息和完整 Schema。
- HTTP DTO 把 Java `Class` 转为 canonical name 字符串，不暴露 `Class` 对象。
- 第一版不按 Session、tenant 或 companyId 隔离目录，不提供分页、搜索、筛选或专用
  插件异常映射；认证授权和异常响应继续由服务框架处理。

### Demo 消费

Flow Studio Demo 是该公共能力的第一个消费者：启动时读取插件目录，任务选择控件不再
维护独立类型清单；选择器按真实 package name 分组，扁平类型选择与已选摘要也显示
该包路径。选中具体类型时请求详情 Schema，并在 Demo 内按 Schema 生成插件专有字段
控件。AUTO、PAUSE、PARALLEL 仍可作为 Demo 的显示标签和行为分支，但不会参与服务端
类型解析。

## 理由

- 具体插件类继续是类型、字段和校验约束的唯一事实来源，目录与 Schema 都由注册结果
  推导，新增插件不需要修改中心枚举或 Controller 分支。
- 真实 package path 已能区分 Flow、CSES 宿主和其他构建期依赖，不需要增加一个与
  Java 类型信息重复的来源注册接口，也不提前引入 ClassLoader、版本和安装生命周期。
- 启动时校验注册结构、查询时生成文档契约，使错误边界与影响范围匹配：不能运行的
  插件阻止启动，仅文档生成失败的插件只影响其详情查询。
- Core 只提供结构化事实，Demo 自己决定表单布局，避免插件机制被某一个展示页面反向
  定义。

## 后果

- 新增 Task 后会自动进入全局目录；插件作者可以只依赖默认标题，也可以在 `@Plugin`
  和字段 `@Schema` 中补充可读说明。
- 插件列表分组字段统一为 `packageName`；目录消费者按真实包路径分组和标识来源。
- 类改名或换包仍会同时改变 YAML 类型、持久化类型、目录 type 和 Schema 中的 type
  const；换包还会改变目录分组，是显式破坏性变更。
- 插件目录是进程级不可变快照；运行时动态安装、刷新和卸载仍不在当前能力内。
- Schema 是定义输入契约，不是前端组件协议。需要图标、组件或画布能力时必须另行设计，
  不能把 Demo 私有布局字段追加到当前元信息模型。
