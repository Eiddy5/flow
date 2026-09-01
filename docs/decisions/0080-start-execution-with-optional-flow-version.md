# ADR 0080：通过可选 Flow version 启动 Execution

## 状态

Accepted（2026-09-01）

本决策修订 ADR 0068 中关于 Execution 不支持指定历史版本启动的条款。

## 背景

Execution 启动需要同时支持两种 Flow 选择：不指定版本时启动某个 Flow key 的最新
可用版本；指定版本时启动该 key 对应的精确正式版本。原有 `ExecutionService` 通过
多个 `create` 重载区分调用方式，调用方还需要了解不同参数形态。

## 决策

- `ExecutionService` 只提供一个启动方法：

  ```java
  create(session, key, Optional<Long> version, inputs)
  ```

- `version` 为空时通过 `latestFlow` 选择最新正式 Flow；不为空时通过精确版本查询
  选择 `key + version` 对应的 Flow。
- `version` 必须为正数；目标 Flow 必须是正式且未删除的 Flow，租户范围始终来自
  `Session`。
- 选定 Flow 后，输入规范化、Execution ID 生成、`Create` 构造和 Executor Command
  Queue 投递共用一条链路。
- HTTP 启动入口也只保留一个方法；版本作为可选的 `version` 参数传入，缺省时表示
  最新版本。
- `Create` Queue payload 和 Execution 持久化事实继续保存已有的精确 Flow 引用；本
  ADR 只统一启动入口使用的业务参数名 `key`、`version`，不修改已有 Queue 幂等键和
  Execution 运行绑定语义。
- 不保留旧的 `create(session, key)`、`create(session, key, inputs)` 兼容重载；所有
  调用方显式传入 `Optional.empty()` 或 `Optional.of(version)`。

## 理由

单一启动 Interface 将 Flow 选择条件显式表达为一个可选值，避免维护多套创建链路；
`requireFlow` 集中处理最新版本和精确版本的选择，后续输入校验、身份生成、Queue
受理和异步消费保持一致。

## 后果

- 现有调用方必须迁移到统一的四参数 `create` 方法。
- 版本查询失败、版本非法或 Flow 已删除时，在 Queue 投递前拒绝，不产生 Execution。
- Execution 表、Queue 表和 JOOQ 生成结构不需要改变。
- UC-02 需要增加“发布多个版本后按指定版本启动”的验证场景。
