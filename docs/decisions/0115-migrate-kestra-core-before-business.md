# ADR 0115：先迁移 Kestra 完整核心与扩展工具链

## 状态

Accepted（2026-10-12，用户授权开始第一阶段；实施进行中，尚未验收完成）。

本记录落实 [ADR 0114](0114-evaluate-kestra-as-embedded-cses-engine.md) 的迁移路线，
工作分支为 `codex/kestra-core-migration`。具体清单、执行顺序和证据维护在
[第一阶段迁移台账](../harness/kestra-core-migration.md)。

2026-10-13 修订：第 2、5 条中的独立子工程和保留旧实现布局被
[ADR 0116](0116-replace-flow-root-with-kestra-sources.md) 取代。以下保留原决策过程；
当前是备份后由 Kestra 源码直接组成根项目，其余范围和阶段门槛继续有效。

2026-10-13 后续修订：embedded 适配模块和入口被
[ADR 0121](0121-use-native-modules-directly.md) 取代，职责归原生模块，继续同应用 Java 调用。

## 背景与备选方案

用户要求嵌入 CSES、同 JVM Java 调用，复用现有可配置数据源，完整采用 Kestra JDBC
队列，并先完成原生核心、扩展机制和 Task 导出，再加入 Route 与业务逻辑。

零散复制模型和队列会遗漏插件 processor、Worker Controller、调度状态及导出工具。
直接依赖发行 JAR 不满足源码二开的目标。采用固定上游源码和集中基础设施适配。

## 决策

1. 固定 Kestra OSS `v2.0.5`，提交 `172dd0f3760fb1255d3b22bd1fd07ce31b173fa3`。
   不复制本机 Kestra 工作区的未提交修改。保存原始许可、源码 SHA-256 清单和适配记录。
2. 在 `kestra-engine/` 迁入上游后端模块，使用独立 Gradle 构建边界。保留 `io.kestra`
   包名、上游模型、SQL、时间类型及 Java 25 要求，不为符合旧 Flow 领域规范改写上游。
   自有适配放在 `embedded` 模块的 `org.cses.flow.kestra` 下，遵守项目手写代码规范。
   构建隔离不代表运行时隔离；同宿主 ApplicationContext 的依赖兼容必须单独验收。
3. 第一阶段以原生流程和无业务语义的验证插件为依据。迁入原生 If/Switch，暂不实现
   Route、审批/待办、companyId/AuthX 或旧 Pause/退回兼容层。
4. JDBC 队列及 Repository 复用宿主 `datasources.flow.*` 和 `@Named("flow")`
   DataSource/DSLContext，连接参数由宿主配置。严禁回退 default 或创建另一套引擎连接池。
   保持上游 JDBC SQL、事务、领取及 ACK 协议；包括独立新事务的原始连接路径。
5. 新引擎制品不依赖旧 Flow core/scheduler/server，不包含 Pulsar。迁移期间旧模块留在
   原位置供行为参考；新运行链路通过验收后移除旧队列生产实现。不存在运行期双队列选择。
6. 保留上游数据库迁移资源及显式管理工具，嵌入启动禁止自动执行 DDL。
   首轮真实验证只在独立临时 PostgreSQL 中进行，不写现有业务库。正式表命名空间
   与旧 Flow 同名表冲突的处理是切换门槛。
7. 扩展协议、编译期注册、独立制品打包、发现加载、类型解析、Schema/文档/图标/
   示例导出、结构化/文件输出与开发验证工具，均属于第一阶段。
   CLI 与 Web 中承载的能力需逐项映射为保留工具或 Java 服务，不能按模块名字裁剪。
8. 宿主拥有 ApplicationContext、连接池、入站认证和端口。引擎停止只能关闭自己启动
   的角色和线程，不能关闭宿主。独立构建通过不能代替真实 CSES 装配通过。
   `EmbeddedKestraEngine` 显式启动原生六类服务；停止顺序为 Scheduler、Worker、
   SystemWorker、Controller、Executor、Indexer。上游 Context 的故障关闭请求转为
   引擎停止，不调用宿主 close。原生 Bean 生命周期为一次性，停止后重启需宿主重建
   Context；此处没有创建第二个 Context 来规避依赖兼容。
9. 显式迁移中的直接 DataSource 注入也必须具名绑定，包括基线/升级脚本、历史存储和
   advisory lock。采用宿主 `datasources.flow.schema` 选择新引擎命名空间，原生表名
   不改写。验证工具使用独立 schema；正式命名空间选择与旧数据迁移仍属于切换门槛。
10. 保留上游根 `lombok.config` 并作为编译输入；其中构造器元数据是消息反序列化协议
    的一部分。聚合 Schema 通过保留的上游 CLI 作为构建工具生成，CLI/Web 依赖不进入
    embedded 生产运行时。`script` 模块作为原生 TaskRunner SPI 支撑进入运行时。

## 理由与对既有决策的修订

上游完整运行协议必须先有可重复基线，才能判断后续业务差异来自哪里。第三方源码
保留自身风格和协议，减少合并升级时的噪声。

对新 Kestra 引擎，修订 ADR 0039 的仅 classpath 插件约束、ADR 0094/0096 的 Pulsar
选择，以及 ADR 0111 的 DataSourceEngine CRUD 实现约束；具名可配置数据源和宿主
生命周期所有权继续保留。旧 Flow 在新链路切换前仍按旧决策运行。
完整核心迁移不等于现有业务 UC 已兼容，也不授权放宽那些 UC 的预期。

## 后果

源码体积、依赖与维护范围显著增加。第一阶段必须同时验收运行、持久化、调度、扩展
和导出，不能将其中任何一项挪到业务阶段。台账中未有可复现证据的条目保持未通过。
上游 Java 25/Micronaut 4 与宿主当前栈的兼容属于待验证工程风险，不能仅修改版本号
消除。未通过第一阶段前不切换 CSES 当前依赖，也不迁移在途实例。
