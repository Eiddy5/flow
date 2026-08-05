# Flowable 人工审批与表单参考

## 调研范围与版本基线

本文仅参考 Flowable 官方文档、Javadoc 与官方 GitHub 仓库，用于回答以下问题：

- 一个流程定义如何产生多个人工审批任务实例；
- 流程如何引用已发布的表单版本；
- 表单提交数据、流程变量、审批意见分别由谁保存；
- Flow 项目应当借鉴什么，以及不应直接照搬什么。

BPMN 运行时与 API 以 Flowable `7.2.0` 为主要基线。Flowable 7 已从发行版中移除独立 Form Engine 的实现，因此表单持久化结构同时参考最后包含该实现的 `6.8.1`；Flowable 7.2 仍保留 Form API，并在 BPMN 启动、完成人工任务的命令中展示与表单服务的集成方式。

## 结论摘要

Flowable 的核心拆分不是“流程里直接保存一整份审批表单”，而是三个相互关联的模型：

1. `ProcessInstance` 表示某次流程运行；同一个流程定义可以启动任意多个实例。
2. `Task` 表示某个流程实例运行到 UserTask 后创建的人工工作项，负责待办、候选人、签收、完成和意见。
3. `FormInstance` 表示一次已经提交的表单快照；流程变量只承载路由和节点表达式真正需要的数据。

因此，请假场景可建模为：流程版本绑定一份确切的已发布请假表单版本；员工提交后创建一次请假表单快照并启动一个流程实例；Boss、HR 的任务都通过流程实例读取同一份只读快照；每个任务自己的处理人、决定和意见单独记录。

这也说明“表单提交数据必须放在审批模块”不是必然结论。在 Flowable 的独立 Form Engine 模型中，表单实例属于表单能力，人工任务属于 Task 能力。真正需要固定的是职责和关联键，而不是模块名称。

## 1. UserTask、流程实例与任务实例

Flowable 把 UserTask 定义为一个等待人工参与的 BPMN 活动：执行到该节点时创建 `Task`，由 `TaskService` 查询和处理。[UserTask 官方文档](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs/#user-task)

`Task` 是运行时人工任务实例，不是流程设计中的节点本身。它包含 `executionId`、`processInstanceId`、`processDefinitionId`、`taskDefinitionKey`、`formKey`、`assignee`、`owner` 等信息。[Task Javadoc](https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/task/api/Task.html) Flowable 创建任务时会把当前 execution、process instance 和 process definition 的标识写入任务。[TaskHelper 创建任务源码](https://github.com/flowable/flowable-engine/blob/flowable-7.2.0/modules/flowable-engine/src/main/java/org/flowable/engine/impl/util/TaskHelper.java#L245-L255)

关系可概括为：

```text
ProcessDefinition(version N)
  └─ ProcessInstance A
       ├─ TaskInstance: Boss 审批
       └─ TaskInstance: HR 审批

ProcessDefinition(version N)
  └─ ProcessInstance B
       ├─ TaskInstance: Boss 审批
       └─ TaskInstance: HR 审批
```

同一个 UserTask 节点在不同流程实例中会产生不同任务；循环、驳回重走或多实例场景也可能在同一个流程实例里产生多次任务实例。因此业务上必须引用任务实例 ID，不能只用节点 key 表示一次审批。

任务完成后，当前运行时任务会被删除，历史任务被标记结束，然后流程 execution 继续向后推进。[TaskHelper 完成与历史处理源码](https://github.com/flowable/flowable-engine/blob/flowable-7.2.0/modules/flowable-engine/src/main/java/org/flowable/engine/impl/util/TaskHelper.java#L334-L468)

需要注意：Flowable 的 UserTask 是“通用人工工作项”，并不天然等于审批。审批、确认、补充材料、人工复核都可以用 UserTask 承载。

## 2. 启动表单、任务表单与版本解析

Flowable 区分两种表单入口：

- Start Form：配置在开始事件上，用于流程启动之前收集业务数据。[None Start Event 官方文档](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs/#none-start-event)
- Task Form：配置在 UserTask 上，用于完成某个人工任务时补充或提交该任务的数据。[UserTask 官方文档](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs/#user-task)

经典 BPMN Form API 中，`formKey` 只是由应用解释的引用，并不天然保证固定到某个表单版本。[FormData Javadoc](https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/form/FormData.html)

独立 Form API 则把发布物建模为 `FormDefinition`，包含：

- `id`：某个确切部署版本的定义 ID；
- `key`：跨版本保持一致的逻辑标识；
- `version`：版本号；
- `deploymentId`：所属部署。

[FormDefinition Javadoc](https://developer-docs.flowable.com/javadocs/flowable-oss-javadoc/2025.1/org/flowable/form/api/FormDefinition.html) 查询接口既支持指定版本，也支持取最新版本。[FormDefinitionQuery Javadoc](https://developer-docs.flowable.com/javadocs/flowable-oss-javadoc/2025.1/org/flowable/form/api/FormDefinitionQuery.html)

Flowable 还提供 `sameDeployment` 语义：表单可以限定从流程所在的同一应用部署中解析；否则可按 key 解析最新定义。[UserTask 表单引用说明](https://documentation.flowable.com/latest/reactmodel/bpmn/reference/user-task) [Start Event 表单引用说明](https://documentation.flowable.com/latest/reactmodel/bpmn/reference/start-event)

在启动流程时，Flowable 会解析表单定义、校验提交值、把需要的数据映射为流程变量，启动流程实例，然后创建与该流程实例关联的 `FormInstance`。[StartProcessInstanceCmd 源码](https://github.com/flowable/flowable-engine/blob/flowable-7.2.0/modules/flowable-engine/src/main/java/org/flowable/engine/impl/cmd/StartProcessInstanceCmd.java#L145-L243)

对当前 Flow 项目而言，`sameDeployment` 只能作为思路参考。项目已经明确 Flow Reversion 在发布时绑定表单 V1，因此应保存不可变的 `formDefinitionId`，或等价的 `formId + version`，而不是只保存 `formKey` 并在运行时查“最新版本”。否则表单发布 V2 后，旧 Flow Reversion 的含义会漂移。

## 3. 表单提交数据保存在哪里

Flowable 存在两套不同年代、不同职责的表单机制，不能混为一谈。

### 3.1 经典 BPMN FormService

经典 `org.flowable.engine.FormService` 提供 `submitStartFormData`、`submitTaskFormData` 等方法；提交任务表单时还可以直接完成任务。[Engine FormService Javadoc](https://developer-docs.flowable.com/javadocs/flowable-oss-javadoc/3.17.0/org/flowable/engine/FormService.html)

默认处理器会把表单属性转换为 execution/process variables；未声明的字段也可能直接成为变量。[DefaultFormHandler 源码](https://github.com/flowable/flowable-engine/blob/flowable-7.2.0/modules/flowable-engine/src/main/java/org/flowable/engine/impl/form/DefaultFormHandler.java#L89-L100) 提交时，Flowable 同时记录历史表单属性，再根据调用方式完成任务。[SubmitTaskFormCmd 源码](https://github.com/flowable/flowable-engine/blob/flowable-7.2.0/modules/flowable-engine/src/main/java/org/flowable/engine/impl/cmd/SubmitTaskFormCmd.java#L48-L76)

这里的主状态实际上是流程变量；历史表单属性更接近审计明细，并不等同于一份结构完整、可长期解释的业务表单快照。[Flowable History 官方文档](https://www.flowable.com/open-source/docs/bpmn/ch10-History)

### 3.2 独立 Form Engine 的 FormInstance

独立 Form Engine 明确提供 `FormInstance`：它保存确切的 `formDefinitionId`、`taskId`、`processInstanceId`、`processDefinitionId`、提交人、提交时间和 JSON 表单值。[FormInstance Javadoc](https://developer-docs.flowable.com/javadocs/flowable-oss-javadoc/3.17.0/org/flowable/form/api/FormInstance.html)

6.8.1 的实现只把表单模型中已声明字段收集为 JSON values，并把结果、表单定义、任务和流程关联写入实例。[AbstractSaveFormInstanceCmd 源码](https://github.com/flowable/flowable-engine/blob/flowable-6.8.1/modules/flowable-form-engine/src/main/java/org/flowable/form/engine/impl/cmd/AbstractSaveFormInstanceCmd.java#L102-L194) 对应数据表包括：

- `ACT_FO_FORM_DEFINITION`：已部署、带 key/version 的表单定义；
- `ACT_FO_FORM_INSTANCE`：已提交的表单实例；
- `ACT_FO_FORM_RESOURCE`：表单定义和实例 JSON 等二进制资源；
- `ACT_FO_FORM_DEPLOYMENT`：表单部署。

[Form Engine Liquibase 结构](https://github.com/flowable/flowable-engine/blob/flowable-6.8.1/modules/flowable-form-engine/src/main/resources/org/flowable/form/db/liquibase/flowable-form-db-changelog.xml#L9-L130)

Flowable 7.2 的 `completeTaskWithForm` 集成仍清楚展示了双写意图：先按确切的表单定义校验并生成任务变量，保存 `FormInstance`，随后完成任务。[CompleteTaskWithFormCmd 源码](https://github.com/flowable/flowable-engine/blob/flowable-7.2.0/modules/flowable-engine/src/main/java/org/flowable/engine/impl/cmd/CompleteTaskWithFormCmd.java#L107-L165)

可借鉴的关键点是两个用途分离：

- `FormInstance` 保存完整提交快照，服务于展示、审计和历史解释；
- process/task variables 只保存流程判断、表达式和下游任务真正需要的数据。

不应把完整表单 JSON 无差别复制成流程变量，也不应只靠流程变量还原历史表单。

## 4. 处理人、候选人、意见与完成结果

Flowable 的人工任务能力由 `TaskService` 负责，包括：

- `assignee`：当前办理人；
- candidate user/group：有资格领取或办理任务的候选用户、候选组；
- claim/unclaim：候选人签收或释放任务；
- complete：提交变量并完成任务；
- comment：记录与任务、流程实例关联的意见。

[TaskService Javadoc](https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/TaskService.html)

签收会校验任务当前是否已被其他人领取，并更新 assignee、claim time 和审计状态。[ClaimTaskCmd 源码](https://github.com/flowable/flowable-engine/blob/flowable-7.2.0/modules/flowable-engine/src/main/java/org/flowable/engine/impl/cmd/ClaimTaskCmd.java) 评论会记录操作用户、时间、taskId、processInstanceId 与文本。[AddCommentCmd 源码](https://github.com/flowable/flowable-engine/blob/flowable-7.2.0/modules/flowable-engine/src/main/java/org/flowable/engine/impl/cmd/AddCommentCmd.java)

典型存储职责为：

| 数据 | 运行时/历史表 | 含义 |
| --- | --- | --- |
| 当前人工任务 | `ACT_RU_TASK` | 待办、assignee、流程/执行关联、formKey |
| 候选人及其他身份关系 | `ACT_RU_IDENTITYLINK` | candidate、owner、assignee 等 |
| 已结束任务 | `ACT_HI_TASKINST` | 完成时间、处理人、持续时间等 |
| 历史身份关系 | `ACT_HI_IDENTITYLINK` | 任务参与者审计 |
| 评论和事件 | `ACT_HI_COMMENT` | 审批意见、任务事件 |
| 当前变量 | `ACT_RU_VARIABLE` | 路由和运行所需状态 |
| 历史变量/明细 | `ACT_HI_VARINST`、`ACT_HI_DETAIL` | 变量历史和属性审计 |

[Flowable 7.2 PostgreSQL 运行时建表脚本](https://github.com/flowable/flowable-engine/blob/flowable-7.2.0/modules/flowable-engine/src/main/resources/org/flowable/db/create/flowable.postgres.create.engine.sql) [Flowable 7.2 PostgreSQL 历史建表脚本](https://github.com/flowable/flowable-engine/blob/flowable-7.2.0/modules/flowable-engine/src/main/resources/org/flowable/db/create/flowable.postgres.create.history.sql)

“批准/拒绝”通常不是 Task 的固定内建字段，可以作为 outcome、完成变量或业务层决定保存；评论与决定也不应覆盖原始请假表单快照。

## 5. 对当前 Flow 项目的建议

### 5.1 表单与流程版本

- 表单模块继续独立负责设计、发布和版本生命周期。
- Flow Reversion 在设计完成并发布时绑定确切的 `formDefinitionId`，表达“该 Flow Reversion 使用表单 V1”。
- 表单 V2 发布后不影响已发布的 Flow Reversion；需要升级时创建新的 Flow Reversion 并重新绑定。

### 5.2 一次请假申请的数据模型

建议把员工首次提交形成的业务对象称为 `FormSubmission` 或 `ApplicationSubmission`，而不是因为它将进入审批流程就直接称为“审批数据”。它至少应包含：

- submission ID；
- exact form definition ID；
- execution/process instance ID；
- applicant、submittedAt；
- immutable submitted values；
- 可选的业务状态与业务单号。

Boss、HR 的所有审批任务通过 execution ID 或明确的 submission ID 读取同一份只读提交。不要给每个节点复制一份请假表单，也不要让节点重新解析“最新”表单版本。

### 5.3 表单快照与流程变量

提交请假申请时同步产生两类数据：

- 表单侧保存完整且可审计的提交快照；
- Flow Execution 只接收路由需要的投影，例如请假天数、请假类型、所属部门。

如果两个模块共用数据库和事务，可以在一个应用事务中完成；如果跨服务，则需要幂等键、可靠事件或 outbox，避免表单已提交但流程未启动，以及流程已启动但找不到表单实例。

### 5.4 人工任务边界的两种可行选择

当前架构存在两种一致的方案：

**方案 A：保持现有 ADR，审批是外部业务能力。**

`PAUSE` 继续只是通用外部等待点。审批模块拥有审批工作项、候选人、签收、权限、意见、决定和审计，通过 `executionId + taskRunId` 关联 Flow，完成后调用 Flow 的 resume 接口。表单提交快照仍可归表单/申请模块所有。

这是对现有 `0016`、`0017` 决策改动最小的方案，也最适合当前阶段。

**方案 B：正式引入 Flowable 风格的 USER_TASK。**

在流程运行时内或紧邻运行时建立一个通用 HumanTask 子系统，提供任务实例、身份关系、待办查询、claim、complete、comment 和历史。它应是通用人工工作能力，而不是专门写死为“请假审批”。

该方案不是给现有 `PAUSE` 或 `TaskRun` 多塞几个审批字段；它会改变现有领域边界，必须先修订 ADR，再实现新的任务类型和持久化模型。

### 5.5 推荐落点

在当前约束下，建议先选方案 A：

```text
FormDefinition V1
       │ bound by exact id
       ▼
Flow Reversion 1 ──start──► Execution
                                │
                 FormSubmission│（一份、只读）
                                │
                   ┌────────────┴────────────┐
                   ▼                         ▼
             Boss ApprovalWorkItem     HR ApprovalWorkItem
             决定/意见/处理人           决定/意见/处理人
                   │                         │
                   └────── resume Flow ─────┘
```

模块职责建议为：

- Form：表单设计、发布版本、渲染/校验、提交快照；
- Approval/Human Work：人员分配、权限、待办、签收、决定、意见和审计；
- Flow：流程定义/版本、Execution、TaskRun、路由和暂停/恢复。

如果所谓“审批模块”实际上只保存员工提交的表单数据，而不负责办理人、决定和意见，那么它更准确的名字应是“申请/表单提交模块”；否则模块名称会让边界产生误导。

## 6. 不建议直接照搬的点

- 不要只保存 `formKey` 并在运行时总取最新版；这与已确认的 V1 固定绑定相冲突。
- 不要把所有表单字段都变成流程变量；这会扩大运行时状态、暴露敏感信息，并削弱历史快照的可解释性。
- 不要把 Boss/HR 的审批结果写回并覆盖原始请假表单；决定和意见属于各自任务历史。
- 不要把 Flowable UserTask 简化理解成现有 `PAUSE`；前者背后有完整的任务、身份关系、查询、历史和评论模型。
- 不要因为 Flowable 可以把 HumanTask 放进引擎，就直接推翻现有模块边界；是否内建人工任务，是需要 ADR 明确记录的架构选择。

## 最终判断

Flowable 最值得借鉴的不是具体表名，而是“流程运行、人工工作、表单提交”三种状态分离，同时通过稳定 ID 关联。

对当前请假场景，最稳妥的设计是：Flow Reversion 固定引用表单 V1；员工每次提交生成一个不可变的 FormSubmission 并启动一个 Execution；Boss 和 HR 的任务共享读取这份提交；人员、决定、意见由审批工作项保存；Flow 只接收完成信号和路由所需变量。
