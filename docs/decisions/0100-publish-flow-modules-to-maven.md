# ADR 0100：统一发布 Flow Maven 模块

## 状态

Accepted

## 背景

CSES 需要通过内部 Maven 仓库消费 Flow，现有构建尚未提供上传配置。
Server 普通 JAR 依赖 Core，Core 又依赖 Gen。

## 备选方案

- 只上传 Server JAR：缺少传递模块及依赖元数据，无法完整消费。
- 上传 Shadow JAR：重复打入宿主依赖，不符合 ADR 0039 的普通 JAR 契约。
- 统一发布三个模块的 Java Component：保留模块依赖及标准 Maven/Gradle 元数据。

## 决策

采用第三种方案。根 `build.gradle` 为全部三个子模块应用 `maven-publish`，统一
group 为 `org.cses.flow`，版本取 `gradle.properties` 的 `flowVersion`。
Server artifactId 为 `flow`，其余模块使用 `core`、`gen`。

发布 Java Component、源码 JAR、POM 和 Gradle Module Metadata，排除 Shadow
变体。保留 `flow → core → gen` 的传递依赖。

内部发布地址复用 `repoUrl`，按用户确认的配置方式读取 Gradle 属性
`repoUser` 和 `repoPassword` 作为发布凭据。

## 理由

使用 Gradle 原生发布能力，集中定义坐标、版本和认证，不复制三份配置或手写 POM。

## 后果

根目录的 `publish` 发布三个模块；只执行 `:server:publish` 不会发布其依赖模块。
发布不是跨模块原子操作，应在全部上传成功后让宿主升级版本。发布前执行测试，
本地配置验证不能证明远端仓库的账号权限。操作见
[`Maven 发布手册`](../harness/maven-publishing.md)。
