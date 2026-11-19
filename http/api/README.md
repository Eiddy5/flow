# Flow HTTP API 索引

| 模块 | 说明 | API 文档 |
| --- | --- | --- |
| 插件 | Flow 通过 PaaS Web 提供插件发现、详情和已加载版本查询 | [插件 API](./flow-plugins-api.json) |

## 宿主接入

在 CSES 的 `settings.gradle` 引入 Flow 工程。以下路径假定两个仓库同级。

```groovy
includeBuild('../flow')
```

在 CSES `server` 模块引入 Flow HTTP 模块，并沿用宿主的 PaaS Web 依赖。联合构建使用 Flow 所需的 Java 25 和同一套兼容的 PaaS/Micronaut 依赖。

```groovy
dependencies {
    implementation('org.flow:http:2.0.5')
}
```

通过 CSES 的 HTTP 鉴权入口保护 `/flow/plugins` 和 `/flow/plugins/**`，然后在宿主配置中启用插件路由。PaaS 的 `@UserSession` 参数绑定只覆盖声明了该参数的接口，此处由宿主的路由级鉴权处理。

```yaml
flow:
  http:
    plugins:
      enabled: true
```

客户端沿用 CSES 的地址、端口和登录凭据，可直接请求 `GET /flow/plugins?category=tasks`。Micronaut 从 Flow 依赖中发现 `org.flow.http.plugins.PluginController` 并处理请求。PaaS 响应过滤器按 `@SkipResponseWrap` 保留契约中的原生 JSON 响应。

## 验证

在 Flow 仓库运行 `./gradlew :http:test :verifyRuntimeIsolation`，验证宿主 HTTP 路由和引擎依赖隔离。
