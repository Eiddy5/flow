# ADR 0116：备份旧 Flow，直接以 Kestra 源码架构替换根项目

## 状态

Accepted（2026-10-13，用户明确要求源码级替换）。修订
[ADR 0115](0115-migrate-kestra-core-before-business.md) 中的子工程与新旧并行布局；
完整核心、扩展和 Task 导出的阶段门槛继续有效。

第 5 条的 embedded 定位后续被 [ADR 0121](0121-use-native-modules-directly.md) 取代；
当前由原生模块直接提供 Java 入口，根 Java Library 聚合运行依赖。

## 背景

用户要求将现有项目备份，直接采用 Kestra 的架构代码二次开发。此前将原生模块放入
`kestra-engine/` 独立构建、旧 Flow 继续占据根目录的做法不符合这一要求。
保持同 JVM、现有可配置数据源、原生 JDBC 队列和先核心后业务的实施顺序。

## 备选方案

1. 保留旧 Flow，使用子工程或发行 JAR 提供引擎：不符合本次明确要求。
2. 备份旧项目，以固定版本 Kestra 后端源码直接替换根工程：采用。
3. 将 Kestra 协议重新实现为旧 Flow 模型：丢失上游架构与升级依据，不采用。

## 决策

1. 替换前完整复制工作区，包含 `.git`、所有未提交文件、忽略文件、本地配置和构建
   产物；逐文件 SHA-256 校验后才进行替换。备份位于仓库外，恢复步骤见
   [备份记录](../harness/kestra-source-backup.md)。
2. 22 个原生后端模块直接位于根目录，共用根 `settings.gradle`/`build.gradle`。
   `kestra-engine/` 不再存在。旧 Flow 的 core/scheduler/processor 实现、server、gen、
   buildSrc、版本目录、Pulsar 代码和旧构建产物退出当前工程，保留在备份与 Git 历史。
3. 保留固定提交 `172dd0f3760fb1255d3b22bd1fd07ce31b173fa3` 的 `io.kestra`
   源码、资源、SQL、测试及模块依赖；继续从源码编译，不依赖外部 Kestra 成品引擎。
   采用原生 Wrapper 9.7.1、Java 25 和根 Lombok 配置。
4. 根构建保留后端依赖平台与编译/测试约定，省略原产品发布、上传和 UI 打包配置。
   原始构建另存 `upstream-build/`，上游模块内已登记的四处适配保持不变；新增
   Wrapper、编辑配置和 JAR 启动脚本纳入来源哈希。
5. `embedded` 是根构建中的宿主基础设施模块，只负责具名数据源、显式迁移、
   生命周期、插件加载与 Task 导出；原生执行与状态逻辑位于 Kestra 原模块。
   不创建第二个 ApplicationContext，不建立平行的 Flow 模型或执行器。
6. Repository、队列、日志、迁移历史与锁使用宿主 `datasources.flow` 的同一可配置池，
   不回退 default。启动无隐式 DDL；本轮真实验证只使用一次性 PostgreSQL。
7. 扩展机制、插件 JAR、Task 目录、Schema/文档/图标/示例和运行 Output 均保留在
   第一阶段。Route、审批等业务逻辑在完整核心验收后加入，当前保持原生条件任务。
8. 旧 ADR、UC 和报告保留为历史与业务需求依据；旧领域规范不用于改写原生 ID、
   时间、序列化、SQL、事务或插件协议。当前目录和架构入口更新为新根工程。

## 理由

让待二开的源码直接成为项目本体，后续修改定位到上游原模块，保留可审计的来源和
有限适配。外部完整备份提供恢复路径，避免新旧运行实现混在当前构建中。

## 后果与验收边界

本次是源码与构建根替换，不是旧数据转换或真实 CSES 上线。没有修改 CSES 的当前
依赖和运行状态。源代码替换完成也不代表第一阶段全部通过；Worker 故障恢复、复杂
控制流、调度补偿、其它 SPI 与实际宿主兼容等仍按
[迁移台账](../harness/kestra-core-migration.md) 收口。

旧 Java API、表结构和 Maven 坐标不作为当前原生工程的兼容承诺；业务适配应在完整
核心验收之后明确映射。当前技术验证必须从根目录重新构建运行，旧子工程报告仅作
历史证据，不能冒充新根工程的结果。
