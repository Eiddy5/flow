# Flow

Flow 由两个 Gradle 模块组成：`gen` 维护 PostgreSQL/JOOQ 构建输入，`server`
承载 Flow Core、Executor、Worker、插件扩展、数据库适配、HTTP 接口和可启动应用。

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
