# ADR 0135 Flow 通过宿主 PaaS Web 暴露插件查询

## 状态

Accepted。补充 ADR 0117 的宿主 HTTP 接入和 ADR 0134 的全局插件查询边界。

## 背景

CSES 的 Flow 编辑器需要通过 HTTP 获取引擎已加载的插件类型，以及对应的配置 Schema、示例和输出字段。CSES 已提供 PaaS Web、HTTP Server 和用户鉴权。Flow 的注册表由同一应用上下文提供。

## 选项

1. CSES 编写插件 Controller 并转调 Java 服务。接口契约和详情组装会进入宿主项目。
2. Flow 提供由宿主加载的 HTTP 模块。Flow 持有路由及查询契约，宿主提供服务端运行环境和鉴权。采用。

## 决定

- 在 `http` 模块提供原生 Micronaut `PluginController`，注册到宿主的 HTTP 服务。宿主选装该模块，并在设置路由鉴权后启用 `flow.http.plugins.enabled`。
- 提供插件目录、类型详情和已加载版本三个 GET 接口。Flow 复用 `PluginService` 和引擎的 Schema 生成器，使用注册表现有插件类型的全限定名。生成的配置 Schema 保留 `$defs`。
- 用 PaaS `@SkipResponseWrap` 保留契约约定的 JSON 数组和对象。HTTP 模块编译期依赖该注解，运行时由 PaaS 宿主提供响应过滤器和 Netty。
- 宿主处理 HTTP 认证和授权。插件查询使用全局目录，请求不携带 Flow Session 或租户参数。

## 理由

插件响应由引擎当前加载的实现生成，宿主可以直接复用同一套类型、Schema 和示例。HTTP 适配按宿主需要接入，引擎的 Java 调用路径与 HTTP 服务端依赖隔离。

## 后果与验证

- 同 JVM 接入步骤和接口契约见 [HTTP API](../../http/api/README.md)。
- `:http:test` 通过真实 HTTP 请求验证 Micronaut 路由、PaaS 响应格式、插件详情和宿主鉴权边界。`:verifyRuntimeIsolation` 验证根引擎的依赖边界。
