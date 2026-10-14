# 离线 Schema 工具

从固定上游 CLI 提取的 Schema 算法，直接使用原生插件扫描和生成服务，不启动
ApplicationContext。模块不进入根 Java Library 生产依赖，也不依赖数据库后端或执行服务。

```bash
./gradlew :tooling:exportPluginSchemas
./gradlew :tooling:exportConfigurationSchema
./gradlew :tooling:test :tooling:verifyRuntimeIsolation
```

默认使用本仓库 fixture 插件 JAR，输出分别为 `build/exports/plugins-schema.json`
和 `build/exports/configuration-schema.json`。导出自己的插件目录时显式指定：

```bash
./gradlew :tooling:exportPluginSchemas \
  -PschemaPlugins=/absolute/path/plugins \
  -PschemaOutput=/absolute/path/plugins-schema.json
```

目录必须存在；空目录表示仅扫描工具 classpath 内置插件。输出文件会覆盖；生成根
Schema 失败时进程失败，不写出部分聚合资源。插件发现/容错继续遵循原生 Scanner。
配置 Schema 的范围由工具 classpath 决定，包括 core、script、本地存储及加载的插件，
不包括已移除 Web/MySQL 的配置，也不是 CSES 完整配置验证器。

Task 文档/单类型 Schema/图标导出使用 core 的
[`PluginExportService`](../core/src/main/java/org/flow/core/docs/PluginExportService.java)。
Flow 完整定义校验使用原生 `FlowService.validate(...)`；迁移管理直接使用 [原生 Java 入口](../docs/harness/java-engine.md)，无需此工具模块。
