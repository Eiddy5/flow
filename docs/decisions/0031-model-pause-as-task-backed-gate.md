# ADR 0031：将 Pause 建模为带前置 Task 的外部恢复编排门

## 状态

Accepted（2026-08-05；2026-09-09 修订字段命名）

2026-09-09：暂停前任务字段统一为 `onPause`，恢复输入字段统一为 `onResume`。
Java 字段、访问方法、Builder、YAML、JSON、Schema 和管理页面使用相同名称；
不保留旧 `pause` / `resume` 字段别名，已有定义和持久化 properties 需使用新字段。
外部恢复操作仍使用 `ExecutionService.resume(...)`，生命周期语义不变。

本决策（2026-08-21 修订输出契约）修订 ADR 0016、0024、0026 和 0029 中关于 Pause 具体类名、定义字段、
定义包含关系以及 Resume 直接完成 TaskRun 的条款。统一 Resume 入口、Task 直接
作为 Plugin、OrchestrationTask 不进入 Worker、Pause TaskRun 独占 `PAUSED` 且
Execution 始终保持 `RUNNING` 的决定继续有效。

## 背景

现有 `PauseTask` 只复用 Task 的 `outputs` 与 `tasks`。它能形成一个外部等待点，
但不能表达进入等待前必须执行的确定工作，例如调用审批系统创建审批单；外部回调
数据也只有 Output 类型，无法复用 Input 已有的必填、默认值和具体类型约束。

已经确认 Pause 是 Flow 自有的编排 Task。它本身没有 `run`，流程推进仍由 Executor
状态机负责；但定义必须完整说明暂停前动作、恢复输入以及可选的超时结果。项目已经
采用“具体 Task 类即 Plugin”，因此不能为 Pause 恢复 `PausePlugin`、
`TaskExtension` 或类型分发器。

## 备选方案

### 方案一：继续使用 outputs 和 tasks

改动较小，但会把恢复输入的约束降级为 Output，并把“进入暂停前执行”与普通 Task
完成后的子任务混为同一个关系。

### 方案二：增加 ResumeDefinition 和 TimeoutDefinition

可以组合字段，但这些包装对象没有独立身份或生命周期，会使一个简单定义多出两层
结构，并与已确认的直接 `onResume`、`duration`、`behavior` 属性冲突。

### 方案三：Pause 直接拥有专有字段

具体类直接声明 `onPause`、`onResume`、`duration` 和 `behavior`。Task 基类只补充定义
树遍历语义；插件注册、Schema 和属性序列化继续从真实类推导。

## 决策

采用方案三。

### 类型与字段

- 具体类型一次性更名为 `org.cses.flow.extensions.flow.Pause`，不保留
  `PauseTask` 别名或兼容类，也不迁移旧定义数据。
- `Pause` 直接标注 `@Plugin`，继承 `Task` 并只实现 `OrchestrationTask`；不增加
  伴生插件、Dispatcher 或专用目录。
- `Pause` 直接拥有：
  - `Task onPause`：必填且唯一，表示进入 `PAUSED` 前必须完整执行的 Task；允许任意
    RunnableTask 或 OrchestrationTask 及其完整子树。
  - `List<Input<?>> onResume`：外部 Resume 回调的数据定义；非 null，允许为空。
  - `String duration`：可选的正 ISO 8601 表达式。
  - `Pause.Behavior behavior`：可选的超时行为，必须与 duration 同时存在或同时
    缺失。
- Pause 不接受通用 `tasks` 作为自身定义子树。`Task.tasks()` 对 Pause 必须为空。
- Pause 复用 Task 的 `outputs` 作为独立的用户定义结果契约；`onResume` 只定义外部
  Resume 回调输入，不从它派生 outputs，也不要求两者字段一致。

### 定义包含关系

- `Task.definitionChildren()` 表达完整定义包含关系；普通 Task 默认返回
  `tasks()`，Pause 固定返回唯一的 `onPause`。
- Flow 的身份复用、唯一性、查找和依赖检查统一遍历 `definitionChildren()`。
- `onPause` 是类型专有字段，不改变 `tasks` 的普通“完成后子任务”语义。
- YAML 物化根据已注册具体 Task 类的真实字段递归处理 Task 与 Input 定义，不增加
  Pause 类型分支或伴生属性编解码器。
- 关系库仍用子行保存通用 `tasks`；`onPause` 作为插件专有字段随所属 Pause 的
  `properties` JSONB 保存。恢复后仍必须得到完整具体 Task，并进入统一定义树。

### Pause 与 Resume 生命周期

- Executor 进入 Pause 后先让 Pause TaskRun 保持 `RUNNING`，再无条件执行
  `onPause` Task。`onPause.route` 不参与选择；该动作失败时沿用统一执行失败处理。
- `onPause` 的完整子树正常收敛后，Executor 才把 Pause TaskRun 变为 `PAUSED`。
  Execution 在整个等待期间保持 `RUNNING`。
- 外部调用仍只提交 `executionId + taskRunId` 和回调数据，不能提交目标状态、取消、
  失败、停止或下一 Task。
- Resume 回调按 `onResume` 中每个具体 Input 的类型、required、defaultValue 和专有
  规则校验；额外 key、非法值或缺失必填值原子拒绝，Pause TaskRun 保持
  `PAUSED`。
- 合法 Resume 先把目标 TaskRun 从 `PAUSED` 恢复到 `RUNNING` 并保存规范化结果，
  再由 Executor 状态机完成该 TaskRun 和后续流程推进。Resume 不直接把 TaskRun
  改为 `COMPLETED`。
- `dependOn` 的 Pause 专有含义本轮不扩展，继续使用现有 Task 通用规则。

### duration 与 Behavior

- duration 保存规范化的正 ISO 8601 文本，例如 `PT5M`、`P1DT2H30M`、`P1M`。
  `P1M` 表示自然月，不在定义阶段猜测为固定 30 天或毫秒值。
- `Pause.Behavior` 是 Pause 内部枚举，并直接映射 `State.Type`：
  - `RESUME -> RUNNING`
  - `WARN -> WARNING`
  - `CANCEL -> CANCELLED`
  - `FAIL -> FAILED`
- duration 与 behavior 都缺失时，只能由外部显式 Resume 唤醒。
- 超时触发 RESUME 时使用空回调数据进入同一 Input 校验；校验失败的目标结果是
  FAILED。WARN、CANCEL、FAIL 不反向推导或填充 onResume 输入。
- 本轮落地定义、映射和手动 Resume 状态路线；超时扫描、触发时刻计算和对应状态
  推进由后续 Executor 运行决策实现。

## 不变量

1. Pause 是 Task 和 Plugin，且只拥有 OrchestrationTask 运行能力。
2. 每个 Pause 恰好包含一个非 null onPause Task；`tasks()` 始终为空。
3. onResume 非 null、key 不重复，每个元素都由具体 Input 自己校验定义和值。
4. Pause outputs 由 Task 的通用 outputs 字段独立配置；onResume 与 outputs 可以拥有
   不同的 key、DataType 和数量。
5. duration 与 behavior 同时存在或同时缺失；duration 必须为正 ISO 8601 表达式。
6. Pause TaskRun 只有在 onPause 子树收敛后才能进入 PAUSED。
7. 外部 Resume 不能指定运行状态；成功路线必须经过 PAUSED -> RUNNING。
8. Pause 等待和恢复前后 Execution 均保持 RUNNING。

## 验证场景

- 正向：任意 RunnableTask、Parallel 或嵌套 Pause 作为 onPause；空或多项 onResume；
  onResume 与 outputs 独立配置且可以不一致；
  `PT5M`、`P1M` 与全部 Behavior 映射；YAML 和 PostgreSQL 往返保持完整定义。
- 反向：缺少 onPause、非空通用 tasks、重复 onResume key、额外或非法回调值、只配置
  duration/behavior 一侧、零值或负 duration、旧 `PauseTask` 类型。
- 变异：若 onPause 未执行就进入 PAUSED、若 Resume 直接变为 COMPLETED、若
  Execution 随 Pause 进入 PAUSED，测试必须失败。
- 恢复：重启后从持久化 Flow Reversion 找到嵌入的 onPause 定义，并以同一
  executionId/taskRunId 从 PAUSED 恢复到 RUNNING 后继续推进。

## 理由

- 真实 Pause 类同时成为类型、字段、Schema 与属性序列化的唯一事实来源，符合
  Task 即 Plugin 的扩展边界。
- 把 onPause 与通用 tasks 分开后，定义能够准确表达动作发生在进入等待之前。
- Resume 复用 Input 后，类型、必填、默认值和子类型规则无需在 Pause 重复实现。
- 保留 ISO 8601 文本能够无损表达自然月；枚举直接映射 State.Type 使超时目标成为
  编译期契约。
- 先恢复到 RUNNING 再交回 Executor，使外部调用方不能越过统一状态机决定流程。

## 后果

- 所有 Java、YAML、Demo、测试和持久化类型地址需要从 `PauseTask` 一次性迁移为
  `Pause`；旧 outputs 继续作为 Task.outputs 保留，旧通用 tasks 不能继续挂在
  Pause 下。
- Task、Flow、定义物化和查找必须识别类型专有定义子任务；Repository 必须无损保存
  properties 中的嵌套具体 Task。
- Resume Handler 需要调用 Pause 的 onResume 输入校验，TaskRun 的状态转换需要支持
  `PAUSED -> RUNNING`，Executor 再完成恢复后的 Pause。
- `WARNING`、`CANCELLED`、`FAILED` 进入 State.Type 作为 Behavior 目标；在超时运行
  决策落地前，不新增对应 Executor 触发入口。
