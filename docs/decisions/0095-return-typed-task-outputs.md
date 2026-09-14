# ADR 0095：由 Task 类型定义并返回具体 Output

## 状态

Accepted（2026-09-09，按本次用户确认的输出契约）。修订 ADR 0019、0024、0031 和
0061 中 Task 可配置输出列表、RunResult 和任务返回值的条款；Flow 自身的输出定义不变。

## 背景

2026-09-14：运行能力及编排输出入口由 [ADR 0099](0099-resolve-task-orchestration-as-read-only-plans.md) 修订；旧接口方法不保留。

用户在编写具体 Task 时已经知道其输出结构。将同一结构再次配置在 Flow 的 Task
节点中会产生两个来源；任务执行得到的具体输出应进入后续 RunContext。

## 备选方案

1. 保留 RunResult 和流程中的 Task.outputs：重复维护字段列表与 Map 键。
2. 仅将 RunResult 改名为 Output：仍然没有具体 Task 的输出类型。
3. Task 泛型绑定具体 Output，以 Java 字段定义结构，以实例承载本次结果。

## 决策

采用方案三。

- `RunnableTask<T extends tasks.Output>.run(RunContext)` 返回非 null 的具体结果。
- `OrchestrationTask<T>.outputs(RunContext)` 在编排完成时收集结果；默认返回 null
  表示不覆盖已有结果，尤其保留 Pause 由外部恢复写入的回调值。
- 删除 `RunResult`。各具体 Task 使用自己的结果 POJO/record；没有业务输出时使用
  `VoidOutput.from()`。`VoidOutput` 不声明任何字段，不保存状态或错误，不提供失败工厂。
  无输出任务失败时抛出 `WorkflowException`，Worker 调用边界将其转为
  `WorkerTaskResult.failed(...)`，错误只进入结果信封；其他异常仍由原调用边界处理。
- `Output.state()` 为空时采用 SUCCESS；允许 SUCCESS、WARNING、FAILED、KILLED。
  FAILED 必须提供非空白 `error()`，其他状态不得提供错误；状态机仍由 Executor 所有。
- `Task.outputs` 不再是可配置或可绑定字段。`Task.outputs()` 是从代码中具体泛型
  Output 派生的只读标量元数据，用于现有条件类型检查和 HTTP 展示。
- `TaskOutputs` 复用现有 ClassMate 解析泛型继承和字段；业务字段名称支持字段上的
  `JsonProperty`，忽略 `JsonIgnore`、transient、state 和 error。数据对象使用 PAAS JSON
  可序列化的 getter POJO 或 record。复合对象可作为结果数据传输，但不扩展现有仅支持
  基础类型、三级路径的条件表达式；HTTP 标量列表也不展示复合字段。
- `TaskOutputs.values` 使用 PAAS JSON，将业务字段编码为普通映射；控制状态、错误与
  null 未赋值字段不进入业务映射。`WorkerTaskResult` 保留为队列信封。
- Runnable 与编排完成共用 Executor 的结果应用路径。输出保存到 TaskRun 后，继续
  由 RunVariables 按任务 key 和已有作用域规则投影为 `outputs.<taskKey>.<field>`。
  不把 Output 对象写入旧 RunContext，不创建全局可写上下文。
- 声明 `VoidOutput` 的任务不投影到 `RunContext.outputs`，不产生空的任务 key 条目；
  TaskRun 状态记录继续保留。其他具体 Output 即使本次业务字段为空，仍保留其上下文条目。
- Pause 的回调结构仍由其 `onResume` Input 声明，输出元数据从该声明推导，不再另配
  一份 Task.outputs；恢复产生的实际值继续保留在自身 TaskRun.outputs。
- 持久化 Task 时输出列保留派生元数据快照；恢复 Task 时不将该列重新绑定为配置。
  本次没有数据库 Schema 变更，也不迁移旧 YAML 中的 Task.outputs 配置。
- 插件详情返回只读 `outputs` 标量列表，与可配置 `schema` 分开。管理页只展示这些
  字段，不能新增、删除或修改任务输出；Flow 自身的输出编辑仍保留。

## 理由

具体返回类型同时表达开发期结构和运行结果；不再维护 RunResult 的第二套 Map
返回协议。继续使用现有任务记录、队列信封、PAAS JSON 与 RunVariables，保持暂停、
并行和循环的作用域边界。

## 后果与验证

- 宿主 Task 需要声明具体 Output 泛型并返回相应对象；旧 RunResult 调用必须迁移。
- 旧 Task YAML 中的 outputs 字段会被严格绑定拒绝；Flow 根 outputs 保持原协议。
- 验证覆盖具体 Output 的序列化、控制状态分离、非法状态、无可配置输出的引用校验、
  Runnable → 编排 → 后续任务上下文，以及已有暂停回调和持久化往返。
- 编译、定向测试和业务回归应分别报告；JDK 25 结果不证明 Java 21 依赖兼容。
