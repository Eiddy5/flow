# Flow

Flow 由 `gen`、`core` 和 `server` 三个 Gradle 模块组成：`gen` 维护
PostgreSQL/JOOQ 构建输入，`core` 承载非 HTTP Flow 能力，`server` 承载数据库适配、
HTTP 接口、正式管理页面和可启动应用。

正式 Flow 管理页面位于 `/flow/index.html`。页面通过正式 `@UserSession` Controller
访问 `datasources.flow.*` PostgreSQL 数据源；用户会话由 PAAS／宿主认证提供，
Flow 不再配置或注入默认登录用户。

目录职责见 [`docs/project-structure.md`](docs/project-structure.md)，当前模块决策见
[`ADR 0032`](docs/decisions/0032-restore-single-server-runtime-module.md)。

## Micronaut 5.0.4 Documentation

- [User Guide](https://docs.micronaut.io/5.0.4/guide/index.html)
- [API Reference](https://docs.micronaut.io/5.0.4/api/index.html)
- [Configuration Reference](https://docs.micronaut.io/5.0.4/guide/configurationreference.html)
- [Micronaut Guides](https://guides.micronaut.io/index.html)

---

- [Shadow Gradle Plugin](https://gradleup.com/shadow/)
- [Micronaut Gradle Plugin documentation](https://micronaut-projects.github.io/micronaut-gradle-plugin/latest/)
- [GraalVM Gradle Plugin documentation](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html)

## Feature micronaut-aot documentation

- [Micronaut AOT documentation](https://micronaut-projects.github.io/micronaut-aot/latest/guide/)

## Feature serialization-jackson documentation

- [Micronaut Serialization Jackson Core documentation](https://micronaut-projects.github.io/micronaut-serialization/latest/guide/)
