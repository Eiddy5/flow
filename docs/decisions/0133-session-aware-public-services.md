# ADR 0133：原有服务直接接收宿主 Session

- 状态：Accepted；原有 Service 直接接收 Session，Java 25 联合构建及回归验证通过
- 日期：2026-10-19
- 关联：ADR 0121、0125、0126、0127、0132

## 背景

宿主基于 PaaS，企业和操作者来自宿主 Session。直接暴露接受 tenantId 的原生服务，
会让调用方重复提取和验证身份。用户确认沿用 main 的 Session 方式以及 FlowService、
ExecutionService、PluginService 命名，并要求插件查询也接收 Session。
用户进一步确认：原有 Service 本身就是宿主调用入口，直接接收 Session 并实现业务，
不在外层新增同名服务或委托层。本记录据此修订此前尚未提交的服务分层方案。
用户指定本地 PaaS 的 neworg 分支；实际分支名为 pass-2.0-neworg。

## 选项

1. 在原有 Service 中直接接收 Session，统一身份边界并保留已有业务实现；后台持有
   Flow/Execution 对象的状态推进继续使用运行协议。
2. 在 core 的领域服务子包增加同名 Session 服务，再委托原有服务。
3. 由每个宿主自行包装原生 API。

## 决定

采用选项 1，不增加 Flow Gradle 模块。唯一业务服务位于原有 services 包：

- org.flow.core.services.FlowService
- org.flow.core.services.ExecutionService
- org.flow.core.services.PluginService

FlowService 和 ExecutionService 在原有类中完成身份校验、仓储调用和命令投递；
PluginService 同样直接查询注册表。移除 services.flows.FlowService、
services.executions.ExecutionService 和 services.plugins.PluginService 路径，
不保留兼容别名，也不将原有服务重命名为另一个供外层调用的实现。

业务方法统一使用 `<S extends Session<U>, U extends SessionUser>`。
SessionUtil 从 session.getCompanyId() 读取企业，从 session.getUser().getId()
读取操作者；禁止从 user.companyId 推断企业。缺少企业或操作者时拒绝调用。
查询按 Session 企业限定范围。FlowService 的创建、更新同时接收 YAML 或 GenericFlow；
YAML 中的企业由 Session 决定，GenericFlow 对象的企业必须与 Session 一致。更新、
删除由服务按 Session 企业重新读取现有流程，不接受调用方传入 previous 对象。
定义校验、导入、依赖查询、可执行流程查询及 LocalFlowRepositoryLoader.load 同样
要求 Session，不再以默认租户补齐缺失身份。ExecutionService 的实例查询、暂停
状态查询和控制入口从 Session 限定目标，不保留无实际授权行为的 withACL 开关。
插件注册表仍是全局目录，所有对外插件查询统一校验 Session。

异步执行操作使用 SessionCommand 包装现有原生命令，保存 actorId，沿用内部命令的
租户、事件和操作标识。消息不保存 Session 或登录凭证。消费者通过宿主 SessionFactory
恢复具体会话与用户，调用 onSessionBound()，并校验身份一致；恢复失败记录异步失败。
执行器在锁内及读取重放来源时再次校验租户、命名空间和流程。
本次保留队列与日志中的操作者身份及 Pause 恢复人，不增加执行审计数据库模型。

后台在已有 Flow/Execution 上进行状态推进、执行清理及子执行清理时，继续调用同一
Service 中的运行方法；这些方法沿用已有对象及队列协议，不要求伪造用户 Session。
FlowEngine 生命周期不要求 Session。Repository Interface、PluginRegistry 和离线
导出工具继续承担内部数据访问与工具职责，业务操作通过上述 Service 接收宿主 Session。
services.executions 下仅保留 SessionCommandContext 和 FlowSessionContextConfigurer
等异步身份与装配组件，不提供第二个 ExecutionService。

## 理由

遵循宿主的直接 Service 规范，由既有业务服务统一处理 Session 与实际业务，避免
同名服务和重复委托层。保留当前执行链路、模块归属及宿主具体 Session/User 类型，
不引入 ThreadLocal 或第二套用户、认证、权限体系。

## 本地依赖与 Java 版本

用户确认本次改用 Java 25，直接联合构建本地 PaaS pass-2.0-neworg；本决策修订
ADR 0125 的 Java 21 下限与 ADR 0126 的 Micronaut 4 组合。Flow 本身仍不覆盖标准
Java toolchain；构建环境需使用 Java 25 或与实际依赖兼容的更高版本。

settings.gradle 使用 includeBuild 引入本地 PaaS，默认路径为 ../paas，也可通过
-PpaasSourcePath 或 FLOW_PAAS_SOURCE_PATH 指定。该源码必须包含 SessionUser；
不回退到尚未包含该接口的旧 Maven 制品。使用本地相同版本的 PaaS 依赖族，避免
Session 与 common/json/sql 的类协议混用；外部依赖坐标和 BOM 版本继续由 TOML 管理。
内部 Micronaut 平台对齐本地 PaaS 的 5.0.3.29，Jetty BOM 对齐其要求的 12.1.8。

core 以 api 声明 paas-session 并关闭其传递依赖，显式导出 Session/User 公共签名所需的
common、json 与 Micronaut JSON 类型。Flow 不引入 Session 的 HTTP Server
或 Redis 运行基础设施。core 通过最低优先级 PropertySource 默认设置
paas.session.enabled=false，宿主显式配置仍可覆盖，并由宿主提供 SessionFactory。

## 后果

宿主直接注入 services 根包中的三个服务，并提供 SessionFactory；业务调用、样例
和测试同步改为 Session 签名，已移除子包路径的引用必须更新。
Session 校验不是登录验证或企业权限授权，宿主继续负责认证和权限决策。
旧 Flow 的 Pulsar 命令栈与领域模型不恢复；本项目仍使用原生 JDBC 队列和 gen 表字段。

## 验证

### 历史验证：接口收敛前的 Session 接入

以下是本次直接 Service 调整前的验证证据，不代表调整后的代码已验证。

2026-10-19，Microsoft OpenJDK 25.0.4，本地 PaaS pass-2.0-neworg
`c827bf171c8261cc6b88146f2380005212b6c562`，PaaS 源码未作修改。

- 生产与测试源码、JAR 编译通过；根和 tooling 运行依赖隔离检查通过。
- 隔离 PostgreSQL 17 下完整回归：2443 项，2442 通过、1 项既有跳过、0 失败。
  跳过项为 ExecutionTest.labelsGetDeduplicatedOnNewInstance。
- 新增覆盖 Session 泛型和宿主工厂真实装配、非法身份拒绝、租户查询与控制边界、
  YAML 租户覆盖防护、命令身份序列化与真实数据库往返、后台恢复失败及锁内租户检查。
- 独立消费工程仅引用 org.flow:flow:2.0.5，通过 includeBuild 联合源码，使用自定义
  Session/User 调用三个业务服务，compileJava 通过。
- 配置及插件 Schema 导出通过；内置插件导出包含 3 个根、119 个定义。
- 此次未运行完整宿主应用和真实存储上的流程验收；宿主仍需提供数据源、StorageInterface
  和自己的 SessionFactory。测试替身没有作为生产存储或启动兜底。

Micronaut 5 适配还修正了 FlowContext 关闭后静态引用残留，保持 Jackson 2 工厂的
ISO 时间输出，并改用真实的 sealed 上传对象测试。相关回归已包含在上述统计中。

### 本次直接 Service 调整

2026-10-19，在相同 Java 25 / 本地 PaaS 分支下完成本次独立验证：

- 生产源码、测试源码、JAR、根与 tooling 运行依赖隔离检查通过。
- 15 项 Session 聚焦测试通过；最终隔离 PostgreSQL 17 完整回归共 2454 项，
  2453 通过、1 项既有跳过、0 失败；未变动模块沿用 Gradle UP-TO-DATE 结果。
- 新增真实容器测试确认三个原生 Service 各只有一个 Bean。直接创建流程、按租户查询、
  拒绝跨租户启动、读取真实插件注册表及创建命令入库全部通过；通过 gen 的 QUEUES
  表读取并验证 SessionCommand 的租户、操作者和输入，消息中不包含 Session 凭证。
- 目录导入按文件身份逐个查询当前租户；新增三项回归确认坏定义不阻断其他文件、
  File/URL 入口使用 Session 租户及非法身份不访问仓储。
- 独立消费工程改为导入 org.flow.core.services 下三个 Service，compileJava 通过。
- 配置及插件 Schema 导出通过，内置插件仍为 3 个根、119 个定义；构建 JAR 中无旧桥接类。

执行命令为 `bash docs/harness/verify-flow.sh --full-regression --no-build-cache
--console=plain build :tooling:exportPluginSchemas`，最终 BUILD SUCCESSFUL，临时数据库
容器由脚本自动清理。日志与 JUnit 汇总位于本机忽略目录 build/direct-service-validation/。

真实装配测试中的存储替身仅用于解析依赖，并断言没有文件读写调用；未启动执行引擎，
不把命令入队当作流程执行完成。本次仍未做宿主真实存储上的完整引擎运行验收。
