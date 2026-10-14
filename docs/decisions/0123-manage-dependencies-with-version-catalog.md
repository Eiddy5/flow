# ADR 0123：用 TOML 统一依赖管理并移除 platform 模块

## 状态

Accepted（2026-10-14）。用户要求与 CSES 的版本目录方式对齐，并明确移除现有
`platform` 模块。本决策修订此前模块清单中的依赖平台职责。

## 背景

依赖坐标散布于根和各模块的 Gradle 文件，版本又分别来自 `platform`、直接声明和
第三方 BOM。仅增加 TOML 而保留原有平台，会留下两处需要同步维护的依赖清单。
本次先统一管理方式；内部 Micronaut 版本接入另行实施。

## 备选方案

1. 增加版本目录，继续维护独立 `platform`：用户明确不采用。
2. 移除独立平台，以 TOML 管理坐标和版本，直接消费必要的第三方 BOM：采用。
3. 同时升级 Micronaut 并引入 CSES 的 PaaS 聚合依赖：超出本次范围，不采用。

## 决策

- `gradle/libs.versions.toml` 是外部依赖坐标和显式版本的唯一维护位置。根构建和
  所有模块通过 `libs` 访问，不再在模块 Gradle 文件中写外部依赖坐标或版本。
- 删除 `platform/`、`settings.gradle` 注册和所有 `project(':platform')` 引用。
  不建立替代 BOM 模块，也不发布 `org.flow:platform`。
- 根共享配置直接引用 Micronaut、Jackson 和 Jetty 的官方 BOM；它们的坐标及版本
  同样来自 TOML。Micronaut BOM 仍为 `4.10.18`，运行核心仍为 `4.10.28`。
- 保留实际需要的少量传递依赖约束：Commons Compress、Gson、jOOQ、Plexus Utils、
  Jakarta Annotation；编译期处理器另保留 SLF4J 和 Jakarta Annotation 的版本约束。
  约束通过 `libs` 取得版本，避免移除平台后发生隐式降级，并向 Java 消费者传递。
- 移除原平台对 Google Cloud、Azure、AWS 和 Allure BOM 的直接引用，删除只服务于
  已退出功能的目录条目。Micronaut BOM 自身仍可能导入厂商 BOM 元数据，不能据此
  判断已经引入对应运行库。
- 保留原有测试差异：共享测试使用 BOM 管理的 AssertJ，`tests` 和 `tooling` 使用
  显式固定版本；两个别名避免本次迁移顺带改变 AssertJ 和 Byte Buddy。
- 根 `allprojects` 中显式通过 `rootProject.libs` 访问目录，避免 Gradle 的隐式父项目
  属性查找；模块构建使用 `libs`。无需增加 `buildSrc` 或复制 CSES 的业务依赖。
- Java/项目制品版本、Wrapper 和内置 Gradle 插件属于构建配置；本次不将它们当作
  外部库迁移，也不改变发布坐标与 Java 25 要求。

## 理由

版本目录可以集中管理坐标和版本；第三方 BOM 继续维护框架内部组件的版本配套。
少量显式约束保留当前实际解析结果，无需维护独立平台及不再使用的插件版本清单。

## 后果与验证

当前注册子模块由 17 个变为 16 个。外部消费者依赖根 Java Library 或原生模块，
不再依赖本项目的平台制品。当前解析结果保持不变不代表永久锁定所有传递依赖；
以后升级目录或 BOM 时仍须比较解析结果并验证运行行为。

验证覆盖编译、处理器、生产与测试 classpath，以及 Mockito agent，共 119 个配置。
先核对仅移除本地平台时的全部组件，再核对精简 BOM 后的实际制品。构建、独立 Java
消费、真实 PostgreSQL 与完整回归结果见 [依赖管理指南](../harness/dependency-management.md)。
来源清单保留，平台删除和原生模块构建变更登记到 `upstream-changes.json`。
