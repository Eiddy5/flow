# ADR 0124：按模块职责声明 Micronaut 依赖

## 状态

Accepted（2026-10-14）。用户确认按源码和装配需求收窄 Micronaut 依赖，移除多余
依赖及全局配置，并执行编译和测试验证。本决策修订 ADR 0123 中全项目引入
Micronaut BOM 的条款，继续沿用 TOML 版本目录和原生模块边界。

## 背景

原构建在根 `allprojects` 中配置整组 `micronaut` 自定义依赖，并向所有模块添加
Micronaut 注解处理器和测试框架。只有继承该自定义配置的模块才直接引入整组运行
依赖，但所有模块都承担了框架处理器和测试依赖。实际源码的需求并不相同：
`model` 和插件 `processor` 没有框架职责，存储与脚本实现由 core 装配，配置 Schema
工具只读取 Bean 元数据，运行模块则分别使用注入、配置、数据库或管理端点。

仅搜索 Java import 不能覆盖全部运行需求。校验器、指标 Registry、Tracing 和
响应式集成等 Bean 可以由运行依赖自动提供，删除其装配库可能仍然编译成功。

## 备选方案

1. 保留全局整组依赖，仅去掉少数模块的配置继承：无法表达处理器及测试边界，不采用。
2. 各模块声明实际 API、实现、运行装配及处理器需求，版本仍统一管理：采用。
3. 同时切换内部 Micronaut 或引入 PaaS 聚合：改变框架来源和运行行为，留待后续。

## 决策

- 删除根共享的 `micronaut` 自定义配置及其 `extendsFrom`，不在 `allprojects`
  中加入 Micronaut BOM、注入/校验处理器或 Micronaut Test。保留现有通用 Java、
  Lombok、JUnit、日志及序列化构建配置，本次不改造无关依赖。
- `core` 通过 `api` 导出官方 Micronaut BOM，以及公开 Java 契约使用的 Context、
  Data Model 和 HTTP 类型。依赖 core 的模块沿项目依赖获得配套版本；独立的
  `annotationProcessor` 和 `testAnnotationProcessor` 配置按需显式导入该 BOM。
  BOM 只提供版本约束，不等于把全部 Micronaut 运行库加入 classpath。
- 实现内部使用的校验、Jackson、重试和指标库归 core；保留实际装配所需的
  Prometheus、OpenTelemetry 和响应式基础集成。运行库没有直接 import，不构成
  删除理由，须结合配置、Bean 使用方和运行验证判断。
- 生产 HTTP 请求使用已有的 Apache HttpClient。移除生产侧没有用途的 Micronaut
  HTTP Client、Reactor HTTP Client 和 HTTP Tracing 集成；保留协议类型和通用
  Tracing。测试中的 HTTP Client 与 HTTP Server 在相应测试依赖中显式声明。
- 各运行模块按源码声明注入、Context、校验、JDBC/jOOQ、管理端点和指标依赖。
  只为实际生成 Bean/配置元数据的源码配置注入处理器；需要校验元数据的源码另加
  校验处理器，不以“该模块依赖 core”为由自动加入两类处理器。
- `jdbc` 使用 JDBC 事务集成 `micronaut-data-tx-jdbc`，保留 jOOQ 的
  `MicronautTransactionProvider` 所需 `DataSourceTransactionManager` 和连接支持，
  不再引入未使用的生成式 Repository/异步层 `micronaut-data-jdbc`。根技术测试
  仅按需声明 `micronaut-data-connection-jdbc`，避免宽泛测试依赖掩盖生产装配缺失。
- `model`、`processor`、`storage-local`、`script`、`plugin-fixture` 不直接声明
  生产 Micronaut 依赖或 Micronaut 主源码处理器。插件 SPI 仍使用本项目的
  `processor`，它是标准 Java 注解处理器，与 Micronaut Bean 处理器职责不同。
- `storage-local`、`script` 的集成测试仍使用 Micronaut Test 和测试注入处理器。
  `tests` 是测试支持库，其主源码拥有测试 Bean，因此保留主源码注入处理器；
  `tests` 与 `tooling` 自身的普通单元测试不配置 Micronaut 测试处理器。
- `tooling` 显式声明读取配置及 Bean 定义所需的 Context API，但不启动 Context，
  不生成自身 Bean 元数据，不进入根生产依赖。
- 原来由全局 Micronaut BOM 隐式提供版本的共享非 Micronaut 库，改由 TOML 的
  显式版本或对应官方 BOM 管理，保持调整前的解析版本。原来的两组 AssertJ 版本
  继续分别保留。此次不升级 Micronaut，不接入内部 PaaS 库。

## 理由

源码、生成元数据、测试装配和运行时自动装配具有不同的依赖边界。按职责声明可以
让模块构建说明真实需求，并使纯模型和插件处理器免于运行框架的编译处理。
通过 core 导出公开契约及框架版本约束，调用方仍能直接消费原生模块，不增加平台
模块或新的宿主适配层。

## 后果与验证

删除模块的直接声明不代表其传递依赖全部消失。例如 storage-local 和 script 仍
通过 core 使用引擎协议，最终运行环境仍包含 Micronaut。依赖调整应比较主编译、
运行、处理器及测试配置，分别检查版本变化与预期制品删除，不能把 BOM 元数据
减少当作运行体积减少。

验证须包含清理旧生成文件后的全模块编译、测试编译、插件 SPI 和配置 Schema
导出、依赖隔离、独立 Java 消费、真实 PostgreSQL 装配及完整模块回归。已有失败
与新增失败分别记录；命令及实际执行结果维护在
[依赖管理指南](../harness/dependency-management.md)，本决策本身不作为验证通过证据。
