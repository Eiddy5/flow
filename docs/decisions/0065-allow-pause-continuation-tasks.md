# ADR 0065：允许 Pause 同时拥有暂停前动作与恢复后续任务

## 状态

Accepted（2026-08-20）

本决策修订 ADR 0031 中“Pause 的通用 `tasks` 必须为空”和“Pause 的定义子树只包含
`pause`”两条局部契约；Pause 的 Resume、超时和状态机语义继续有效。

## 背景

`Task.tasks()` 的通用语义是当前任务完成后的普通子任务，而 `Pause.pause()` 是进入
`PAUSED` 前必须执行的类型专有任务。原实现禁止二者同时存在，并且定义树只暴露
`pause`，导致 Pause 无法表达最常见的流程形态：暂停前创建外部待办，恢复后继续发送
结果或执行下一步业务动作。

执行器本身已经按两个阶段处理这两条关系：Pause 处于 `RUNNING` 时执行 `pause`；
Pause 恢复并成功后按普通父子规则遍历 `tasks`。模型校验、查找和持久化必须与该运行
时序保持一致。

## 决策

- `pause` 保持唯一且必填，表示进入 `PAUSED` 前完整执行的专有 Task 子树。
- `tasks` 保持 Task 基类的普通语义，允许为空或包含多个后续 Task；它们只在 Pause
  Resume 后、Pause TaskRun 成功后执行。
- `Pause.definitionChildren()` 按 `pause`、`tasks` 顺序返回完整定义树，保证模型校验、
  `Flow.allTasks()`、依赖查询和 Task 查找能看到两类子任务。
- `pause` 不参与普通 `tasks` 的串行/并行调度；它只由 Pause 的暂停阶段解释。
- Repository 继续把 `pause` 作为 Pause 专有属性保存，把通用 `tasks` 作为普通子行
  保存；恢复后的对象必须同时重建两条关系。

## 生命周期

```text
Pause CREATED/RUNNING
    -> 执行 pause 子树
    -> Pause PAUSED
    -> 外部 Resume
    -> Pause SUCCESS，生成 resume outputs
    -> 执行普通 tasks
```

Resume 前的 `tasks` 不应创建 TaskRun；Resume 后的普通任务可以读取 Pause 的 outputs，
并沿用普通 Task 的 route、dependOn 和父子 TaskRun 规则。

## 后果

- `Pause.tasks` 非空不再触发 `Pause` 模型不变量异常。
- YAML、Flow 定义序列化和 PostgreSQL Task 树可以同时表达 `pause` 与 `tasks`。
- 现有只有 `pause` 的定义保持兼容。
- 自定义业务 Task 仍由宿主 Workflow 的业务校验识别；Flow 只负责两类 Task 的通用
  物化、拓扑和运行时序。
- 必须覆盖模型树、持久化往返以及 Resume 前后调度时序测试，避免把 `tasks` 错误地在
  Pause 前执行。
