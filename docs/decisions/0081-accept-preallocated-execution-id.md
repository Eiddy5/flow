# ADR 0081：允许使用预分配 ID 启动 Execution

## 状态

Accepted（2026-09-03）

本决策修订 ADR 0080 中 `ExecutionService` 只提供四参数 `create` 的接口条款，但不
改变 ADR 0068 确立的一次完整启动语义。

## 背景

Flow 作为嵌入式模块运行时，宿主业务可能需要把 Execution ID 持久化为自身聚合的
外部引用。审批提交链路此前在宿主数据库事务尚未提交、Approval 行锁仍被持有时调用
Flow `create`。Flow Queue 消费者随后回调宿主创建待办，并等待同一 Approval 行锁，
使提交事务长时间占用连接并触发连接泄漏告警。

如果先调用 Flow `create`、再用第二个宿主事务回填 Execution ID，异步消费者可能在
引用保存前开始运行。宿主也无法在本地事务提交后才调用原四参数 `create`，因为该方法
返回时才会得到 Flow 内生成的 Execution ID。

## 备选方案

### 方案一：继续在宿主事务内调用 Flow

能够保持原接口不变，但会让外部 Queue 消费及其回调进入宿主事务持锁时间，无法消除
当前连接占用和锁等待问题。

### 方案二：先启动 Flow，再用第二个事务回填 ID

不需要调整 Flow 接口，但存在消费者早于 ID 回填运行的竞态，Approval 与 Execution
之间可能短暂或永久失去可追踪关联。

### 方案三：预分配 ID，本地提交后再完整启动 Flow

宿主使用统一技术 ID 生成器预分配 Execution ID，在自己的事务中保存该引用；事务
提交后再把同一个 ID 交给 Flow 完成一次完整启动。采用此方案。

## 决策

- 保留 `create(session, key, Optional<version>, inputs)`，普通调用仍由 Flow Service
  使用 `StringUtil.newId()` 生成 Execution ID。
- 增加 `create(session, executionId, key, Optional<version>, inputs)`。调用方传入的
  ID 必须来自项目统一的 `StringUtil.newId()`，Flow 仍通过 `Create` 的不变量校验并
  使用该 ID 物化 Execution。
- 两个入口共用同一条 Flow 选择、inputs 规范化、`Create` 构造和 Executor Command
  Queue 投递链路。
- 预分配只产生一个尚未持久化的技术 ID，不创建 pending Execution，也不增加
  `continueExecution` 等第二阶段生命周期操作。
- 宿主 Handler 只在本地事务中完成聚合变更并返回精确 Flow key、version 和已保存的
  Execution ID；宿主 Service 必须在该事务提交后调用 Flow `create`。
- 本阶段不引入宿主 outbox、跨库事务或额外的 Flow 启动幂等协议。默认事务提交后的
  Flow Queue 受理能够成功；失败窗口由后续可靠启动方案另行决策。

## 理由

稳定 ID 在异步工作开始前写入宿主聚合，可以同时保证关联可追踪性和本地行锁先于
Flow 消费释放。Flow 仍集中负责选定版本、校验输入、构造启动 Command 和物化
Execution，新增入口没有重新暴露两阶段 Execution 状态。

## 后果

- 宿主提交事务不再包含 Flow Queue 投递及其后续回调等待，数据库连接占用时间只由
  本地审批写入决定。
- 原四参数入口保持兼容；需要先保存外部引用的调用方使用五参数入口。
- 若宿主事务已经提交而随后 Flow `create` 失败，Approval 会保留一个尚未被 Flow
  受理的 Execution ID。当前范围明确接受该窗口，未来需要通过 outbox 或一次调用的
  幂等启动机制补齐恢复能力。
- Execution 和 Queue 表结构均不变化，不需要数据库迁移或重新生成 JOOQ 代码。
