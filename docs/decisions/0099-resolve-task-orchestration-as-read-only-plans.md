# ADR 0099：三类 Task 与职责单一的编排解析

## 状态

Accepted（2026-09-14，用户确认参考 Kestra 的能力分离，并明确要求简化解析返回值；
直接迁移调用方，不保留兼容协议）。
修订 ADR 0029、0036、0074、0078、0095、0098 中的 Task 能力与调度职责；用户运行结果不变。

## 背景

OrchestrationTask 曾通过并行、暂停、持有子作用域及循环标志让 Executor 解释具体类型。
循环条件另有具体类型分支，循环搜索还会修改 Generation；新增 SubFlow 又将独立运行
与当前运行的子节点编排混在同一能力中。继续加标志会扩大通用接口的具体任务职责。

## 备选方案

1. 保留标志，仅将循环方法移到另一接口：Executor 仍解释任务策略。
2. 照搬 Kestra 的全部接口：引入当前没有使用场景的拓扑、错误分支、finally 与重启配置。
3. 采用行为式编排与独立执行能力，复用当前运行事实、输入绑定和持久化边界。

选择方案三。

## 决策

- 每个具体 Task 恰好实现 RunnableTask<T>、OrchestrationTask<T>、ExecutableTask<T> 之一。
  插件注册、模型校验、运行接纳和 TaskOutputs 同步识别三类；不存在旧方法转发或兼容默认实现。
- RunnableTask.run 执行业务操作。OrchestrationTask 仅声明 resolveNexts 与 resolveState；
  Sequence、Parallel、Route、Loop、LoopUntil、Pause 根据自身定义计算决定。
- OrchestrationContext 位于 core/runner，为行为接口提供不暴露聚合修改入口的搜索上下文。
  复用有效 occurrence、退回 generation、顺序输出传递和并行输入隔离。它不引用 Executor、
  Repository、Session 或 Queue，不保存或修改 Execution、TaskRun、Generation。
- resolveNexts(OrchestrationContext) 只返回 List<ResolvedNextTask>。ResolvedNextTask 只有
  Task 和对应 TaskRun 两个字段，表示可启动的直接子任务；不携带其他节点状态、循环请求或
  收敛标志。空列表仅表示当前没有可启动任务，不等同于作用域完成。
- resolveState(OrchestrationContext) 独立读取运行事实，只返回 Optional<State.Type>；
  空表示继续等待。它不接收 resolveNexts 的结果，也不携带 error 或 Output。
  删除 OrchestrationPlan、OrchestrationState 和 ExecutorContext 中的状态计划容器。
- OrchestrationContext 只负责直接子任务的顺序/并行解析、有效出现位置与完成查询，不递归
  调用其他 Task 的行为。Executor 遍历活动编排节点，独立调用状态解析与下一步解析，
  通过 Execution 方法应用结果；本轮发生推进后继续下一周期，再判断稳定暂停。
- Loop 次数和 LoopUntil 条件、上限留在具体类型。循环体完整收敛后才求值；下一轮由
  返回 TaskRun 的 iteration 表达，Executor 接纳时更新父 Generation，不再额外返回换轮请求。
  轮次原因仍记录 INITIAL、FIXED_COUNT_NOT_REACHED、CONDITION_NOT_SATISFIED；Executor
  只在写入历史原因时识别 LoopUntil，不解释其条件或上限。
- LoopUntil 至少执行一轮，达到上限仍未满足时抛出带原因的 WorkflowException。
  Executor 在任务解析边界捕获异常并记录失败，沿用既有 fail-fast；单独返回 FAILED
  时由 Executor 提供节点失败说明。进入终态后不再接纳同批其他待执行任务。
- 编排完成只保留节点已有 outputs，包括 Pause 恢复值，不通过状态解析生成业务输出。
  需要计算业务结果时使用具体 RunnableTask；SubFlow 返回值继续走 ExecutableTask 完成链路。
- Route 未命中返回 SKIPPED 决定且不创建子节点。Pause 先执行 onPause，收敛后暂停，
  恢复后完成；RunContext 仍只保存变量树，不能承担调度状态修改。
- SubFlow 实现 ExecutableTask，直接保存 flowKey、flowVersion，继续复用 Task.inputs。
  createExecution 生成含精确目标和已绑定参数的运行请求；该请求是调度边界结果，不是 YAML
  配置包装。completeExecution 从成功或警告子运行生成具体结果。当前固定一子运行并等待完成。
- SubFlowExecutionHandler 使用 ExecutableTask 协议查询目标、二次校验子 Flow 输入，原子保存
  父调用与子运行，再发布消息；Executor 统一应用父调用结果。Origin、parentTaskRunId、
  同租户与精确版本约束不变，不增加表或结果副本。
- 定义遍历继续使用 Task.definitionChildren。Flow 引用校验与 RewindPath 仍按明确结构类型
  约束并行因果关系、循环端点及 Branch 祖先，不把这些定义规则混回执行能力标志。

## 理由与后果

新增编排类型通过两个方法分别解析下一步和状态，公共接口不承担具体类型的业务结果。共享搜索仍只有一份，
状态变化和持久化所有权不变。自定义编排 Task 必须直接迁移至新行为接口，旧方法不保留。
本次不增加错误分支、finally、批量子调用、可选等待、子取消传播或重试/重启恢复。

## 验证

本次简化重点回归 UC04、05、06、07、09、10、13，共 55 场景；其他场景随完整回归执行。
技术验证包括三类能力互斥、Output 元数据、只读搜索、循环轮次与并行解析、暂停恢复结果、
业务任务输出和子运行回传。实际 PASS/FAIL/NOT_COVERED/BLOCKED 以本轮测试报告为准；
历史覆盖缺口不因测试命令成功自动变为 PASS。
