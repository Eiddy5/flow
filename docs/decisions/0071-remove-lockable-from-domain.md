# ADR 0071：暂不在 Flow 领域建模锁能力

## 状态

Accepted

本决策只修订 ADR 0041、ADR 0067 及各领域决策中关于 `Lockable`、领域
`lockVersion` 和聚合乐观锁的当前实现条款；不改变 Flow、Execution、Task 或
TaskRun 的业务身份、生命周期和聚合边界。

## 背景

Flow Core 曾通过 `Lockable` 为 Flow 与 Execution 暴露技术乐观锁版本。该能力把
并发控制字段、领域行为、Repository CAS 更新、HTTP Command 和页面协议串成了一条
尚未稳定的公共契约。当前阶段先收敛领域模型和业务状态，不为领域对象引入锁语义。

## 决策

- 从 Core Domain 删除 `Lockable` 类型。
- Flow、Execution 以及其他领域对象不再实现或暴露 `lockVersion()`、`lock()`、
  `hasLockVersion(...)` 或 `requireLockVersion(...)`。
- Flow 发布 Command、HTTP View 和页面不再传递或展示锁版本。
- Flow 与 Execution Repository 当前不执行基于领域锁版本的 CAS 校验，也不通过
  聚合读取接口表达行锁；写入仍由调用方提供的事务负责。
- PostgreSQL `flows.lock_version`、`executions.lock_version` 列和 JOOQ 生成代码
  本阶段暂时保留，Entry 不再把它们映射进领域对象或从领域对象写回。后续重新设计
  并发语义时，再单独决定列清理、迁移和 Repository 协议。
- `queues` 表消费时使用的数据库行锁属于消息消费互斥机制，不是领域对象能力，继续
  由 Queue Adapter 自己负责。

## 理由

领域对象先只表达已确认的业务事实和状态迁移，避免把尚未确定的并发策略固化到
公共 Interface 和所有调用边界。保留数据库列可以让当前开发期 Schema 与既有数据
兼容，同时把未来的并发设计隔离为一次独立决策。

## 后果

- 领域、Service、Handler、Controller 和 Flow 页面不再依赖 Lockable 契约。
- 重复提交和并发写入暂不由领域锁版本保护；后续恢复并发控制时必须重新定义领域
  与 Repository 的契约，并补充对应的正向、冲突和事务测试。
- 现有历史 ADR 和测试报告中的 `lockVersion` 文字作为历史记录保留，当前实现以本
  ADR 为准。
