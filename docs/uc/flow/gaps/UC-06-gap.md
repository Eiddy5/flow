# UC-06 项目能力缺口

## 对应 UC

- UC：`UC-06 用户使用 Pause 结果选择 Flow 路径`
- 文档：[UC-06 用户使用 Pause 结果选择 Flow 路径.md](../UC-06%20用户使用%20Pause%20结果选择%20Flow%20路径.md)
- 涉及场景：全部场景

## UC 目的

验证上游 TaskRun 的真实输出只驱动符合条件的运行分支，并在无匹配、非法表达式、
缺失变量和实例替换时保持路径及数据隔离。

## 目标业务与安全场景

审批结果为 APPROVED 或 REJECTED 时只执行对应分支；未知、缺失或非法条件不能误
执行业务动作，也不能读取另一个 Execution 的输出。

## 项目现有能力

- Task 已保存 route 字符串，YAML 缺省值为 DIRECT。
- Task 支持嵌套 tasks，Flow 可以按稳定 taskId 读取递归 Task。
- PAUSE TaskRun 可以保存经过声明校验的 outputs。
- Execution 以有序 TaskRun 保存真实运行路径。

## 原不满足项与证据（已修复）

- `ExecutorService.handleNext` 不读取 `Task.route()`、TaskRun.outputs 或
  `Task.tasks()`。
- 当前调度只按顶层 Task 的定义顺序计算 nextIndex。
- 项目没有 route 表达式解析器、发布期校验器或运行期求值器。
- 没有定义无匹配时 Execution 的稳定状态和返回协议。
- 多个 route 同时成立时采用单选还是并行尚未确认。
- 没有 `Uc06ConditionalRouteTest`。

## 风险

- 当前嵌套条件分支会被忽略，Execution 可能直接完成或错误继续顶层 Task。
- 不安全或不稳定的表达式求值可能读取越界数据或误执行分支。
- 无匹配和多匹配语义不明确会让相同输入产生不一致路径。
- 为未选择分支伪造运行记录会污染真实执行历史。

## 已满足的最小能力

- 确认 route 支持的最小语法、变量作用域、类型比较和错误协议。
- 发布时校验表达式，运行时只读取当前上游 TaskRun 的声明输出。
- 调度器能够选择子 Task，并明确无匹配及多匹配的稳定行为。
- 未选择分支不创建 TaskRun，路由失败不会产生部分运行记录。

## 当前状态与后续角色

- 状态：`RESOLVED`
- 处理结论：ADR 0006 固定第一阶段 route 语法、父输出作用域、区分大小写比较、
  多匹配并行与无匹配收敛协议；保存和运行链路均已实现。
- 复验证据：`Uc06ConditionalRouteTest` S1～S7。
- UC Agent：保留正向、反向和变异场景，不将当前“忽略 route”写成正确结果。
- 架构角色：已通过 ADR 0006 确认无匹配和多匹配协议。
- 开发角色：已实现校验、求值和子 Task 调度。
- Test Agent：已创建唯一测试类并生成报告，结果见
  [UC-06-2026-07-27-1948.md](../../../test-reports/flow/UC-06-2026-07-27-1948.md)。
