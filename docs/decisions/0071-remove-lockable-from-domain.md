# ADR 0071：锁与并发协议不进入 Flow 领域

## 状态

Accepted

技术版本的对象归属已由 [ADR 0097](0097-carry-lock-in-aggregate-and-inherit-cas-repository.md)
修订：需要 CAS 的根允许携带 lock 元数据，但不恢复 Lockable 或领域并发行为。

本决策修订 ADR 0041、ADR 0067 及各领域决策中关于 `Lockable`、领域
`lockVersion` 和聚合乐观锁的实现条款；不改变 Flow、Execution、Task 或 TaskRun
的业务身份、生命周期和聚合边界。

## 背景

Flow Core 曾通过 `Lockable` 为 Flow 与 Execution 暴露技术乐观锁版本。该能力把
并发控制字段、领域行为、Repository CAS 更新、HTTP Command 和页面协议串成了一条
不属于领域的公共契约。并发竞争应由数据库 Schema、Entry、Repository 与事务协议
处理，不为领域对象引入锁语义。

## 决策

- 从 Core Domain 删除 `Lockable` 类型。
- Flow、Execution 以及其他领域对象不再实现或暴露 `lockVersion()`、`lock()`、
  `hasLockVersion(...)` 或 `requireLockVersion(...)`。
- Flow 发布 Command、HTTP View 和页面不再传递或展示锁版本。
- Flow 与 Execution Repository 不执行基于领域锁版本的 CAS 校验，也不通过聚合读取
  接口表达行锁；写入仍由调用方提供的事务负责。
- 领域确认的业务唯一键由 PostgreSQL 主键或唯一索引保护，Repository 负责把唯一
  冲突转换为稳定的持久化冲突。需要行锁、CAS 或特定事务隔离时，由对应 Repository
  决策并实现。
- PostgreSQL `executions.lock` 是数据库基础设施字段（字段名由 ADR 0086 修订），Entry 不把它映射进
  领域对象，也不从领域对象写回。
- `queues` 表消费时使用的数据库行锁属于消息消费互斥机制，不是领域对象能力，继续
  由 Queue Adapter 自己负责。

## 理由

领域对象只表达已确认的业务事实和状态迁移。数据库唯一键、行锁、CAS 和事务隔离都
依赖具体存储能力，由 Repository 在基础设施边界实现，可以避免技术并发策略扩散到
公共 Interface 和所有调用边界。

## 后果

- 领域、Service、Handler、Controller 和 Flow 页面不再依赖 Lockable 契约。
- 重复提交和并发写入不由领域锁版本保护；Repository 必须按业务唯一键和已确认的
  持久化协议补充正向、冲突和事务测试。
