# ADR 0082：在宿主本地事务之外编排 Execution 操作

## 状态

Accepted（2026-09-03）

本决策扩展 ADR 0081 的“本地提交后启动 Execution”边界，并为 Cancel、Resume 与
Rewind 采用相同的宿主事务隔离原则。Rewind 影响范围的定义仍以 ADR 0078 为准。

## 背景

宿主审批模块通过 JOOQ Command Executor 为每个 Handler 打开本地数据库事务。
Handler 若在持有 Approval 或 Todo 行锁期间调用 `ExecutionService`，Flow 会同步读取
自己的 Execution、Flow，并向持久化 Queue 写入命令。任何 Flow 数据库等待、Queue
写入延迟或异步回调形成的锁竞争，都会延长宿主连接占用时间；超过 Hikari 阈值后会
报告 apparent connection leak。

该问题不仅存在于提交审批：取消审批、自动通过或拒绝 Todo、人工决定 Todo，以及
Todo 退回都曾从事务 Handler 直接调用 Flow。单独移动 `create` 无法建立稳定的模块
边界。

Rewind 与其他动作不同：宿主需要 Flow 计算的 `affectedTaskRunIds` 才能准确失效本地
Todo 与 Decision，但实际 Rewind Queue 命令又应在本地变更提交后投递。

## 备选方案

### 方案一：保留 Handler 内的 Flow 调用

代码直接，但宿主事务时长继续受 Flow 数据库、Queue 和回调影响，无法消除本次连接
长时间占用的根因。

### 方案二：所有动作先提交 Flow，再修改宿主数据

不会在宿主事务中访问 Flow，但异步消费者可能先于宿主状态变更运行；普通 Cancel 与
Resume 也会失去“本地业务事实先提交”的顺序。

### 方案三：Service 编排，Handler 只提交宿主事实

事务 Handler 只访问宿主 Repository，并返回后续 Flow 操作所需的稳定坐标。Service
等待 Command Executor 返回后再调用 Flow。Rewind 增加无副作用预检，以便本地事务
先应用同一影响范围。采用此方案。

## 决策

- 宿主事务 Handler 不依赖 `ExecutionService` 或 `FlowService`，也不查询或写入 Flow
  数据库与 Queue。
- 宿主 Service 是跨模块编排边界。`WorkflowCommandExecutor.execute(...)` 返回代表
  本次本地事务已完成；Service 此后才执行 Create、Cancel、Resume 或 Rewind Queue
  提交。
- 提交审批继续按 ADR 0081 预分配 Execution ID，在本地事务中保存该引用，提交后调用
  五参数 `ExecutionService.create(...)`。
- 取消审批、自动 Todo 结果与人工 Todo 决定由 Handler 返回 Execution ID、TaskRun ID
  及必要 outputs；Service 根据已提交的本地结果调用 `cancel` 或 `resume`。
- `ExecutionService.planRewind(...)` 是无副作用的只读预检：它按 ADR 0078 校验当前
  Execution 和目标路径，并返回防御性 Execution 快照、目标 Task key 与
  `affectedTaskRunIds`，但不投递 Queue 命令。
- 宿主 Rewind 编排固定为四步：
  1. 短事务读取并校验当前 Approval/Todo，返回 Execution 与 TaskRun 坐标；
  2. 在宿主事务之外调用 `planRewind`；
  3. 新的本地事务加锁重验，并按计划影响范围更新 Approval、Todo 和 Decision；
  4. 本地事务提交后调用 `ExecutionService.rewind(...)` 投递实际命令。
- Rewind 应用阶段必须核对 Execution ID、source TaskRun ID、模板节点允许的目标 Task
  key，并确认影响范围包含 source Todo 对应的 TaskRun，不能盲信跨边界计划。
- 本阶段沿用 ADR 0081 的可靠性范围：不增加宿主 outbox、跨库事务、补偿任务或额外
  幂等协议，并默认本地提交后的 Flow Queue 受理成功。

## 理由

该边界使宿主连接持有时间只受本地读写与锁竞争影响，Flow 数据库和 Queue 延迟不会再
被计入宿主事务。Create、Cancel 与 Resume 的顺序一致，Handler 也只表达所属聚合的
业务变化。

Rewind 预检把“计算精确影响范围”与“投递实际命令”分开，同时保留 Flow 对运行历史
和任务拓扑的唯一解释权。宿主只消费计划并执行自己的失效规则，不复制 Flow 的
Generation 或 TaskRun 路径算法。

## 后果

- 宿主审批和 Todo Handler 不再因 Flow 慢查询、Queue 写入或回调锁等待而长期占用
  CSES Hikari 连接。
- Rewind 比普通动作多一次只读 Flow 预检和一次短本地准备事务；在低频人工操作上以
  额外读取换取清晰的事务边界和精确影响范围。
- 准备与应用之间若本地状态变化，应用 Handler 会在锁内重新校验并拒绝过期计划。
- 本地事务提交后、Flow Queue 受理前仍存在失败窗口；当前范围接受该窗口，后续若要求
  可恢复的一致性，应由宿主 outbox 或幂等补偿方案统一解决。
- `planRewind` 不改变 Execution、Generation 或 Queue，因此不需要数据库迁移或重新
  生成 JOOQ。
