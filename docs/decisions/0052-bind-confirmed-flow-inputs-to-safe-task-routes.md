# ADR 0052：将已确认 Flow Input 绑定到安全 Task Route

## 状态

Accepted（2026-08-14）

本决策修订 ADR 0006、0019、0037 和 0051 中关于 Route 只能读取父 Task
outputs、只支持字符串相等、运行时 Input 尚未绑定，以及 Executor `Create` 不携带
Input 的旧约束。Loop Until 仍只读取当前轮 outputs，不读取 Flow inputs。

## 背景

宿主审批设计器需要从已发布表单中选择字段，声明为 Flow 级 Input，并让 Parallel
中的审批分支按这些值选择 Route。配置人员只能选择字段、运算符和常量值，不能输入
Java 类名、脚本或表达式代码。审批提交后，Input 必须跨异步启动、Pause、服务重启和
后续恢复保持不变。

原 Route 只能使用 `outputs.<key> == "value"`。这会迫使宿主增加一个仅为转发表单
值的业务 Task，或者在 Flow 外实现分支，两者都会让流程拓扑与真实执行不一致。

## 备选方案

### 方案一：由宿主在 Flow 外选择分支

无需改变 Flow，但 Execution 不再拥有完整路径，Parallel、汇合和恢复也会形成第二套
状态机。

### 方案二：增加一个业务 Task，把表单字段复制到 outputs

可复用旧 Route，却把表单业务和审批字段写进 Flow 插件，并产生没有业务动作的中转
Task。

### 方案三：Flow 声明并校验 typed inputs，Route 读取不可变输入快照

Flow 继续拥有编排；宿主只负责把已确认字段值映射到稳定 Input key。Express 仍是
受限值对象，不成为脚本执行器。

## 决策

采用方案三。

### 定义与发布

- Flow 的 `inputs` 是唯一 Input 目录；每项继续使用 ADR 0019 的 `key`、
  `displayName`、`type`、`required`、`defaultValue` 和具体类型约束。
- 条件 Route 可以引用直接父 outputs，也可以引用 Flow inputs。Input Route 形如
  `inputs.<key> <operator> <literal>`。
- 支持的运算符固定为 `==`、`!=`、`>`、`>=`、`<`、`<=`。有序比较只允许数值
  DataType；STRING、CHARACTER 和 BOOLEAN 只允许 `==`、`!=`。
- literal 只允许转义字符串、boolean 或有限十进制数。方法调用、属性写入、数组、
  算术、逻辑组合、动态变量和脚本均非法。
- 部署时必须确认 Input key 已声明，并确认 literal、运算符和 Input DataType 相容；
  失败不能生成 Flow Reversion。
- outputs Route 的直接父范围校验继续有效；Loop Until 的两段 outputs 路径和当前轮
  隔离继续由 ADR 0036、0037 约束。

### 启动与不可变输入

- `ExecutionService.create(session, flowId, inputs)` 在 Queue 受理前使用精确 Flow
  Reversion 校验未知 key、required、defaultValue、DataType 和具体 Input 约束。
- `continueExecution(session, executionId, inputs)` 只在 pending Execution 仍为
  `CREATED` 时确认输入并投递 `Create`；执行一旦启动，后续推进不能替换输入。
- 不保留旧的无输入兼容构造或兼容持久化路径；没有必填 Input 的 Flow 仍由调用方传入
  空 Map，带必填 Input 的首次启动会明确失败。
- Executor `Create` Command 持有已规范化且不可变的 Input Map，使异步 Queue 消费
  使用受理时的精确值，而不是重新读取宿主表单。Command Handler 将该值绑定到
  `Execution.inputs` 后，内部 Executor Event 不再重复携带 Flow inputs。

### 运行、持久化与恢复

- `Execution.inputs` 是按精确 Flow Reversion 规范化后的运行时输入唯一持久化来源，
  存储在 `executions.inputs` JSONB；它随 Execution 一起锁定读取、复制和恢复。
- `ExecutorContext` 只接收精确 Flow 与 Execution，Route 直接读取
  `context.execution().inputs()`；Context 不保存另一份 Flow input 快照。
- TaskRun 的 `inputs` 只保存当前 TaskRun 的业务输入，例如父 outputs、依赖 outputs
  和循环元数据，不再写入保留键 `flowInputs`。
- 从 PostgreSQL 恢复 Execution 时，Context 从 `Execution.inputs` 获得输入。因此
  Pause resume、并行分支继续推进和 Server 重启不需要宿主再次提交字段值。
- Worker 通过 `$flow.inputs` 读取 Execution 级 Flow inputs，通过
  `$flow.taskInputs` 读取当前 TaskRun inputs；两者均为调用期只读变量，不进入另一方
  的持久化边界。

### 宿主边界

- Flow 不知道表单、审批金额、申请类型等业务字段。宿主负责把表单字段选择映射成
  稳定 Input key，并在发起时提交对应值。
- 宿主可提供级联选择器并生成 Flow 原生 YAML；配置人员不得直接输入表达式或代码。
- YAML 的解析、Task 插件物化、Route 类型检查和拓扑校验只属于 Flow。审批宿主可以
  原样调用 `FlowService.saveDraft/deploy`，但不得复制 Flow 解析器或另建编译模型。
- Flow 只校验结构和值类型，不查询 Form 服务，也不保存表单定义或表单数据 ID。

## 不变量

- `FIN-001`：运行时只接受当前 Flow Reversion 已声明的 Input key。
- `FIN-002`：Input 在首次执行前完成规范化；校验失败不创建 Execution 或 TaskRun。
- `FIN-003`：同一 Execution 的 Flow inputs 绑定到 `executions.inputs` 后不可替换，
  恢复后值与类型保持一致。
- `FIN-004`：Input Route 只能使用受限根、单一路径、受限运算符和 scalar literal。
- `FIN-005`：Route 的 literal 和运算符必须与声明 DataType 相容。
- `FIN-006`：Route 求值只读，不保存上次结果，不执行调用方代码。
- `FIN-007`：未匹配分支不创建 TaskRun；多个匹配分支继续采用 Parallel 语义。

## 验证

- 定义测试覆盖未知 Input、类型不匹配、非法运算符和未声明引用。
- Executor 技术测试使用 DOUBLE Input 启动 Parallel：`> 1000` 只选择第一分支，
  `<= 1000` 只选择第二分支，并确认从 Execution 持久化行可重建 Context。
- Queue Command 测试确认 Input 随 `Create` 序列化、恢复且不可变。
- 宿主审批测试确认页面生成的原生 YAML 原样进入 Flow，并由 Flow 自己物化
  Pause、Parallel、Loop 和 Route；同时确认表单选中值随审批启动进入 Flow。

## 后果

- `flow_tasks.route` 仍保存字符串；`executions.inputs` 增加 JSONB 对象列，开发期基线
  与生成 JOOQ 必须同步更新。
- TaskRun inputs 不再重复保存 Flow Input 快照；Execution 是恢复输入的唯一来源，
  宿主应只声明路由真正需要的字段。
- 当前不支持空值判断、默认分支、逻辑组合、字段对字段比较、集合或日期类型。
- 更复杂规则必须新增结构化协议和 ADR，不能逐步放宽为任意代码表达式。
