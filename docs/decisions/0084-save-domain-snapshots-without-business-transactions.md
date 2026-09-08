# ADR 0084：取消业务事务并完整保存领域快照

## 状态

Accepted（2026-09-06），依据宿主 Workflow D02 修复要求。
修订 ADR 0082 的宿主事务边界和既有 Execution 锁定读取、周期事务条款；
不改变 Queue 的传输事务、Flow 版本分配和业务 UC。
技术版本来源和仓储会话生命周期已由
[ADR 0086](0086-use-scoped-execution-lock-for-cas.md) 修订。

## 背景

同步 Worker 回调 Workflow 后，Workflow 再调用 Flow 读取或恢复 Execution。
原周期事务持有 Execution 写锁，回调的另一个连接读取时等待该锁，而外层又等待回调返回。
宿主部署有多个副本，进程内锁也无法阻止并行命令使用旧快照覆盖已经完成的业务结果。

## 选择

- 不采用扩大事务或 `FOR UPDATE/FOR SHARE`：它们仍会把跨服务回调放进等待环。
- 不采用进程内串行锁：无法协调不同实例。
- 采用普通读取、领域行为、完整快照保存和存储版本冲突检查。

## 决策

1. CommandExecutor 和两个 Execution 事件 Handler 使用普通 `createDSLContext()`，不打开
   覆盖领域处理或 Worker 回调的业务事务。Handler 先加载领域，调用领域方法，再保存。
2. Flow 定义与 Task 快照的追加使用同一个 SQL，防止子记录保存失败后留下半个定义。
   Execution 和有序 TaskRun 一次读取、一次 SQL 保存。存储负责字段映射及子集合替换，
   不用 SQL 条件执行状态流转。完整 Map 必须保存显式 NULL，JSON 使用已有生成映射。
3. Repository 读取技术版本并在保存时比较；过期快照写入失败，父子记录均不改变。
   当前使用 ADR 0086 的显式 `lock` 和独立仓储会话，不再使用 PostgreSQL `xmin`。
   版本只留在 Entry/Repository，不进入 Execution。
4. Worker 开始前保存领域状态；Worker 返回后重读最新完整领域，再调用领域方法合入结果。
   快照冲突时重新应用已经取得的结果，不能重跑 Worker 或覆盖其他分支的 Resume/Cancel。
5. 精确 PAUSED TaskRun 在 Execution 为 RUNNING、RESTARTED 或 PAUSED 时都可以恢复；
   终态或 KILLING 不接受恢复。此规则允许并行分支独立推进。
   前置 Worker 自动决策使用 `resumeWhenPaused`，先受理同一个 Resume 命令，等待必需的
   前置任务成功并进入 PAUSED 后再应用；普通 `resume` 仍严格要求当前 PAUSED。
6. 外部命令先保存后发布内部事件；同一命令重投时，对已有非终态 Execution 再次发布推进
   信号，补偿已保存但发布失败的窗口。重复创建仍校验相同 Flow、输入和 Execution ID。
7. Queue 的消息领取/ACK 仍由适配器自己的事务实现。消息行竞争与 Execution 领域写入分离；
   本决策不改造传输协议，不引入宿主 outbox，也不承诺外部副作用 exactly-once。

## 结果与验证

跨服务回调不再等待外层 Execution 业务事务。不同仓储实例保存旧快照时收到明确冲突；
一个 SQL 的子行失败不会留下半个 Execution。代价是并发冲突需要重新加载领域，完整保存
随 TaskRun 数量增长。公开接口验证必须覆盖真实 PostgreSQL、并行暂停/恢复、终止和回退；
测试报告记录实际结果，既有 UC 预期不得根据当前代码下调。
