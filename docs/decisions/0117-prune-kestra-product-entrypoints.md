# ADR 0117：第一轮移除独立产品入口与 MySQL 后端

## 状态

Accepted（2026-10-13，用户批准第一轮清理）。局部修订 ADR 0116 的模块保留范围；
ADR 0115 的完整核心、扩展与 Task 导出要求继续有效。

## 背景

当前交付目标是同 JVM 嵌入宿主、使用现有可配置 PostgreSQL 数据源的原生引擎。
完整 CLI 引入 Web 后端与多种数据库，仅为了 Schema 导出而保留这些模块扩大了构建
和测试范围。共享测试中的 `Problems` 是其对 Web 的唯一代码依赖。

## 备选方案

1. 仅取消 embedded 对产品入口的依赖：此前已经做到，但完整模块仍留在构建内。
2. 先保全工具能力，再删除独立入口和未采用的数据库后端：采用。
3. 同时删除 gRPC Controller、Indexer、内存测试后端和原生条件任务：会触及运行协议
   和后续 Route 替换边界，超出本轮范围。

## 决策

1. 删除 `cli`、`webserver`、`jdbc-mysql` 的源码、测试、资源与构建注册。
   删除共享测试中的 Web 专用 `Problems` 和 `tests → webserver` 依赖；一并移除
   引用 CLI 日志格式化类的发行版 GCP 日志 profile，宿主继续提供自己的日志配置。
2. 新建 `tooling`，从 CLI 源码提取 `PluginSchemaBundleGenerator`；配置 Schema
   生成器及其测试仅改包名迁入。工具直接使用 Registry、JsonSchemaGenerator，
   不创建 ApplicationContext，不启动引擎、HTTP 或数据库后端。
3. 保持插件聚合资源中的四类根、共享定义、插件坐标和文件渲染器映射。根 Schema 生成失败时立即报错，避免命令成功却写出残缺资源。
   配置 Schema 只描述工具实际 classpath 中发现的配置，不承诺等同完整发行版。
4. Task Markdown/Schema/SVG 导出继续使用已有 `PluginExportService` 与原生
   `DocumentationGenerator`，不复制生成算法。Flow 解析继续使用 `FlowParsingService`，
   完整定义校验使用 `FlowService.validate(...)`。迁移计划、执行、checksum repair、锁仍为原生 Java 服务。
   删除的是命令入口，宿主准备工具从已有 Context 调用这些服务。
5. 管理操作显式执行：`pendingScripts()` 会创建历史表，不能称为零 DDL 预览；
   `runOrFailIfLocked()` 在锁冲突时失败。宿主普通启动仍不自动迁移。
6. 保留 `worker-controller`/gRPC、`indexer`、H2/内存测试后端、script、原生条件和
   其它 SPI。AI/MCP/dashboard/遥测/脚本容器实现等内部裁剪留待后续轮次。
   不加入 Route 或业务，不改变 `datasources.flow` 的配置、事务和所有权。
7. `upstream-manifest.json` 永久保留原始 3,508 项；`upstream-changes.json` 按源文件
   记录删除、迁移目标/哈希和适配哈希。校验器拒绝未登记缺失/变更和已删除路径回流。
   原始上游根构建 `upstream-build/` 是来源材料，其中历史模块引用不参与当前构建。

## 理由

让构建范围与嵌入交付目标一致，同时保留源码二开、插件协议和必要管理入口。生成器
有独立用途和清晰输入/输出，适合独立工具模块；其余能力已有原生服务，不再增加同义包装。

## 后果与验收

注册模块由 24 个变为 22 个：19 个原生模块、embedded、plugin-fixture、tooling。
原 Web HTTP API、完整发行 CLI 与 MySQL 不再提供。core 内部仍有 HTTP 客户端、
AI/MCP、Dashboard 等代码；本轮不宣称已经得到最小依赖集。

验收包括：构建及工具依赖隔离、迁移前后聚合 JSON 全量结构比较、配置 Schema 原生
测试、共享测试编译、真实 PostgreSQL 下显式迁移/队列/调度/插件导出/生命周期回归。
证据和具体恢复点见 [第一轮记录](../harness/kestra-prune-round1.md)。
完整核心阶段及真实 CSES 接入仍按原迁移台账收口。
