---
name: design-clear-processing
description: 为 Flow 项目设计或重构结构清晰的状态驱动处理模块。适用于 Executor、Worker、调度器、Handler 等包含 process、execute、dispatch、advance 或循环推进逻辑的模块；当用户要求梳理处理流程、精简复杂结构、消除过度抽象、明确上下文与状态归属，或重构 Executor/Worker 时使用。
---

# 清晰处理模块设计

使用“单周期业务管线法”设计深模块：**叙事 → 管线 → 归属 → 接缝 → 验证**。
先让主流程可以顺序阅读，再隐藏状态、恢复、持久化和分发细节。不要从候选类或设计模式
开始设计。

## 1. 明确本轮范围

先读取当前模块的入口、上下文、状态对象、相邻 Adapter、测试，以及仓库中的
`AGENTS.md`、`CONTEXT.md` 和相关标准。把现有代码作为行为证据，不把现有结构当成
目标设计。

根据用户动词确定授权：

- “梳理、设计、解释、审查”：只输出设计和差距，不修改代码。
- “重构、修改、实现”：保护现有用户改动，先确认行为，再实施并验证。

## 2. 叙事：定义一个处理周期

先用一句不含类名的业务语言定义一次入口调用，例如：

> 读取当前运行事实，收敛已有结果，生成下一步工作，按能力分发，并返回本轮变化。

再写出纯业务时间线，每行只写一个动词阶段：

```text
检查是否可处理
处理恢复
判断结束
收敛终止
生成下一步
分发可执行工作
处理编排工作
```

明确入口采用哪一种周期语义：

- **单周期**：一次调用只产生一轮变化，由消息、回调或外层驱动下一轮。
- **推进到稳定点**：入口内部重复同一管线，直到等待外部结果或进入终态。

不得把两种语义混在同一个不明确的循环里。优先采用单周期；只有当前事务确实要求
连续同步推进时，才在管线外增加明确命名的稳定点循环。

## 3. 管线：先写主方法

在设计其他类型前，先写 5 至 9 个阶段组成的目标入口伪代码：

```java
public ProcessingContext process(ProcessingContext context) {
    if (!context.canBeProcessed()) {
        return context;
    }

    context = handleRestart(context);
    context = handleEnd(context);
    context = handleKilling(context);

    if (context.canScheduleNext()) {
        context = handleNext(context);
    }

    context = handleWorkerTasks(context);
    context = handleFlowableTasks(context);
    return context;
}
```

根据真实业务调整阶段，不机械复制示例。保持以下约束：

- 让主方法只表达业务阶段，不出现 Repository、循环下标、Task 类型分支或字段拼装。
- 让所有阶段处于同一抽象层次，并按发生顺序排列。
- 让不适用的阶段原样返回 Context，把状态判断藏进对应阶段。
- 让每个阶段只有一个业务问题和一个改变原因。
- 使用业务动词命名；避免 `doProcess`、`manageData`、`applyStuff` 等空泛名称。
- 把阶段方法保持为实现细节；除非调用者确实需要，不扩大公开 Interface。

如果无法用一屏读懂入口，停止增加类型，先继续压缩管线。

## 4. 归属：固定状态和上下文职责

列出每个阶段允许读取、产生和改变的事实。状态迁移必须由拥有状态的领域对象保护，
处理模块只选择并调用业务动作。

在 Flow 运行模型中默认遵守：

- `Execution` 是聚合根，拥有 Execution 状态和全部 `TaskRun`。
- `TaskRun` 只能经 `Execution` 创建和改变。
- Executor 决定推进顺序，但不另存一套持久化状态。
- Worker 执行 Task 并返回结果，不能直接修改 Execution 或 TaskRun。
- `ExecutorContext`、`WorkerContext` 等 Context 只累积本轮输入和效果，不是恢复依据。
- Repository 只属于聚合根；瞬时 Context 和分发信封没有 Repository。

为 Context 中的每个字段回答：

1. 它是本轮输入还是本轮效果？
2. 能否从聚合稳定推导？
3. 是否与另一个集合表达同一事实？
4. 进程重启后是否需要持久化？若需要，它就不应只存在于 Context。

删除无法回答这些问题的字段。

## 5. 接缝：最后才决定类型

只在以下至少一个条件成立时新增类型：

- 拥有独立生命周期或状态机。
- 保护不能由局部代码表达的不变量。
- 穿过真实的模块接缝，例如 Worker 消息或 Repository Interface。
- 存在两个真实 Adapter 或多种必须替换的实现。
- 显著缩小调用者必须理解的 Interface。

否则优先使用私有方法、局部变量或已有类型。尤其不要因为代码中出现“批次”“计划”
“信号”“工作项”等名词，就自动创建 `Batch`、`Plan`、`Signal`、`WorkItem` 类。

对每个候选抽象执行三个测试：

- **删除测试**：删除它后，复杂度是消失了，还是扩散到多个调用者？消失则删除。
- **改名测试**：名称去掉后只剩若干 List 的包装器，通常不值得存在。
- **独立变化测试**：它是否会独立于调用它的方法演进？不会则留在方法内部。

在生成下一步任务时，先用局部集合准备全部结果，成功后一次加入 Context：

```java
List<TaskRun> nexts = new ArrayList<>();
List<ExecutorWorkerTask> workers = new ArrayList<>();

for (Task task : nextTasks) {
    TaskRun taskRun = createTaskRun(task);
    nexts.add(taskRun);

    if (task instanceof RunnableTask runnableTask) {
        RunContext runContext = runContextFactory.create(taskRun);
        workers.add(new ExecutorWorkerTask(
            createWorkerTask(taskRun, runnableTask),
            runContext
        ));
    }
}

context.withTaskRuns(nexts).withWorkerTasks(workers);
```

不要仅为这两个局部集合创建 `WorkBatch`。只有批次将来拥有独立身份、重试、提交或
恢复语义时，才重新评估。

## 6. 隔离运行上下文

为每个可执行 Task 创建独立的 `RunContext`，不要跨 Task 复用可变上下文。

区分本地运行对象和可传输数据：

- Executor 内可以使用 `ExecutorWorkerTask(WorkerTask, RunContext)` 配对。
- 远程 Worker 只接收可序列化的上下文数据，并在 Worker 端重建 RunContext。
- 不把 `Session`、`DSLContext`、Execution 或 TaskRun 可变对象直接塞入远程消息。

设计 Worker 时先尝试以下单周期叙事：

```text
校验工作信封
创建独立运行上下文
开始任务执行
调用 RunnableTask
规范化成功或失败结果
返回 WorkerTaskResult
```

让 Executor 合并结果并推进状态；不要让 Worker 变成第二个流程编排器。

## 7. 验证：用场景攻击结构

在写代码前至少交付：

1. 一句话周期定义。
2. 可顺序阅读的入口伪代码。
3. 阶段职责表，包含前置状态、产生效果和禁止职责。
4. Context 字段清单及存在理由。
5. 状态所有权和真实接缝。
6. 保留与拒绝的候选抽象，以及删除测试结论。
7. 当前实现到目标结构的最小迁移顺序。

用以下场景验证设计：

- **正向**：普通成功路径能沿主方法从上到下解释。
- **分支**：可执行任务和编排任务先全部形成运行事实，再按能力处理。
- **终止**：Killing 等状态不会继续生成普通下一步工作。
- **恢复**：只根据持久化聚合重建 Context，仍能推出同一下一步。
- **异常**：未处理异常遵守项目事务语义，不被随意翻译成业务失败。
- **并发**：旧 lockVersion 或重复结果不重复生成 TaskRun 和分发效果。
- **变异**：删除整批准备、状态所有权或独立 RunContext 后，测试必须失败。

实现重构时优先通过公开入口测试整个模块，不为每个私有阶段建立脆弱测试。保留必要的
状态机领域测试，以及 Worker/Repository 等真实接缝的 Adapter 测试。

## 8. 结构异味

发现以下任一情况时暂停编码并重新执行“叙事 → 管线”：

- 主方法混合业务阶段、状态字段判断、持久化和对象拼装。
- 为解释设计必须先介绍五个以上新名词。
- Context 复制了聚合状态或成为长期内存数据库。
- 每个私有步骤都有对应公开方法。
- 一个类型只是包装一两个 List，且没有独立不变量。
- Worker 可以改变 Execution，或 Executor 执行具体 RunnableTask 业务。
- 在未确认异步、重试或多 Adapter 需求前提前建立扩展层。
- 为追求复用把本来清晰的线性步骤改成通用规则引擎。

借鉴外部实现时只借鉴清晰的结构形状，不复制与本项目冲突的状态、异常、事务或消息
语义。
