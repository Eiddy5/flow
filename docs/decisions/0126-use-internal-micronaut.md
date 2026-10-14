# ADR 0126：仅接入内部 Micronaut 并保持 Java 21 下限

## 状态

Accepted（2026-10-14）。用户确认沿用 main 分支的内部 Micronaut 接入方式，不引入
其他 PaaS 模块，继续通过 TOML 管理依赖，并保持 Java 21 下限。本决策修订
[ADR 0123](0123-manage-dependencies-with-version-catalog.md) 和
[ADR 0124](0124-scope-micronaut-dependencies-by-module.md) 中保留官方 Micronaut
来源的条款；模块依赖边界、原生 Java 使用方式和
[ADR 0125](0125-use-build-selected-java-version.md) 的 JDK 选择机制保持不变。

## 背景

main 通过内部仓库消费 `io.micronaut.*` 坐标及内部 Micronaut BOM，这与引入
`paas-all`、`paas-service-all` 或某个 PaaS 基础设施模块是不同的依赖选择。
内部平台 BOM 包含 PaaS BOM 的导入；它们只提供约束，但本项目也不需要这些
业务版本约束，因此应在导入平台时显式排除。

main 使用的平台 `5.0.3.51` 对应内部核心 `5.0.4.2`。已检查的核心 JAR 普通类
包含 Java 25 字节码，不能沿用该版本满足 Java 21 下限。内部平台 `4.10.6.52`
对应核心 `4.10.12.20`，已检查的核心普通类为 Java 17 字节码；这只说明核心
制品的字节码边界，仍需验证所用扩展、处理器和完整运行链路。

## 备选方案

1. 引入 PaaS 聚合库获得内部框架：会带入业务模块和基础设施，不符合用户范围。
2. 直接复制 main 的 Micronaut 5 版本：与最低 Java 21 的要求不兼容，不采用。
3. 沿用 main 的仓库和 BOM 接入方式，选择兼容 Java 21 的内部版本并独立约束
   非 Micronaut 依赖：采用。
4. 继续使用全部官方 Micronaut 制品：不满足本次内部框架接入要求，不采用。

## 决策

- Micronaut 平台版本在 `gradle/libs.versions.toml` 中统一声明，当前选择
  `io.micronaut.platform:micronaut-platform:4.10.6.52`。框架核心及扩展的配套版本
  由 BOM 管理，不在模块中散布版本，不恢复本地 `platform` 或 `buildSrc`。
- 根构建通过与 main 同源的 `repoUrl` 配置内部仓库。`exclusiveContent` 仅将
  `io.micronaut` 及其子组中四段及以上数字版本路由到内部仓库；这些版本以新增
  数字段标识内部发布。仍采用官方三段版本的 Micronaut 扩展从 Maven Central
  解析，非 Micronaut 依赖也继续使用官方仓库。
- 每处显式 Micronaut BOM 声明均排除 `org.paas`、`org.paas.nlp` 和
  `org.paas.service`，避免导入它们的 BOM。生产依赖隔离同时禁止 PaaS 运行库，
  不新增 JSON、Session、SQL、Redis、Pulsar 或其他 PaaS 模块。
- 同时排除平台以 BOM 方式导入的 `org.redisson:redisson-micronaut-40`。本项目
  没有 Redis 职责；该导入的父级约束还会意外改变 Testcontainers 和 AssertJ
  的版本，不能作为本项目测试依赖的版本来源。
- 延续 ADR 0124 的模块职责：core 导出公开框架 API 与 BOM；各模块只声明实际
  使用的框架组件、处理器和测试依赖。不恢复全局 Micronaut 运行依赖、处理器
  或测试框架配置。框架元数据处理器使用与运行时配套的版本。
- 内部平台对第三方依赖也有版本约束。使用 TOML 和既有约束保留本项目已选定的
  非 Micronaut 版本，避免本次框架来源变更顺带切换内部数据库驱动等第三方制品。
  core 额外导出官方 Micrometer `1.15.12` 和 Netty `4.2.18.Final` BOM，版本仍在
  TOML 中维护，保留切换前这两组第三方组件的解析版本。
- 最低支持 Java 21；构建、测试及工具执行仍跟随环境或标准 Gradle Java 配置，
  不固定 toolchain、source、target 或 release，也不修改真实的目标版本元数据。
- 外部消费者必须在自己的构建中配置内部 Micronaut 仓库。Gradle 仓库配置不会
  随 Java Library 的依赖元数据传递；发布制品与 `includeBuild` 消费均需考虑
  消费方独立解析传递依赖时的仓库配置。
- 当前以 Gradle composite build 完成直接消费验收，未配置或执行发布。Gradle
  Module Metadata 保留平台排除规则，临时生成的 Maven POM 不保留这些平台
  import 排除；后续发布需验证目标消费者，不能把本次结论扩展为 Maven POM 消费已通过。

## 理由

内部框架可以通过原坐标和 BOM 独立使用，不需要以 PaaS 业务模块作为入口。
限定仓库范围和排除 PaaS BOM 能使内部来源边界明确；保留第三方依赖版本可将
兼容性验证聚焦于 Micronaut 变化。最低 Java 版本应由真实制品和运行验证决定，
不能仅依据版本名、平台 POM 或单个核心 JAR 推断。

## 后果与验证

构建和消费需要能够访问内部仓库；离线构建要求先缓存全部所需制品。官方和内部
Micronaut 组件按平台配套使用，不能为了统一来源强制把官方版本发送到不代理它的
内部仓库，也不能让内部发布版本回退到公共仓库。

验证应包含所有配置的依赖解析、PaaS 与其他禁用依赖隔离、清理旧生成元数据后的
全模块编译和测试编译、Schema 导出、独立 Java 21 消费、真实 PostgreSQL 装配
以及完整模块回归。需分别记录既有失败与本次新增失败，并检查运行依赖及处理器的
字节码要求。实际 Java 21 编译、独立消费、依赖比较、字节码核对及完整回归结果见
[依赖管理指南](../harness/dependency-management.md)。完整回归保留 3 项既有文档检查
失败，无新增失败；不将该命令记为全绿，也不将本次源码验收视为已发布制品验收。
