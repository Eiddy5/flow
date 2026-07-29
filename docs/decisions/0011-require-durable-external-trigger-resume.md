# ADR 0011：外部触发必须跨 Server 生命周期持久化恢复

## 状态

Accepted

## 背景

PAUSE 会把 Execution 和 TaskRun 保持为 RUNNING，并创建 WAITING ExternalTask。
原 UC 在同一个 ApplicationContext 中启动 Execution 后直接调用
`ExternalTaskService.complete`，只能证明进程内续跑，不能证明应用重启后能够从
PostgreSQL 恢复等待链路。

UC 数据改为默认保留后，这个缺口表现为数据库中长期存在 RUNNING Execution。
外部回调、人工操作和定时信号实际发生时不应依赖启动 Execution 的原 server
实例仍然存活。

## 备选方案

### 方案一：同一个 ApplicationContext 内完成

实现简单，但测试可能依赖内存对象，无法证明 PostgreSQL Entry 的完整重建能力。

### 方案二：使用有顺序依赖的多个 JUnit 测试阶段

可以严格证明启动与完成不在同一个测试用例，但必须显式编排完整 UC，且阶段之间
只能通过数据库传递领域状态。

### 方案三：独立外部触发器启动新的 ApplicationContext

启动场景先提交等待状态并关闭原 ApplicationContext。独立触发器使用新的 server
上下文，通过公开 ExternalTaskService 提交结果；Repository 只能从 PostgreSQL
重建 ExternalTask、Execution、TaskRun 和绑定的 Flow。

## 决策

采用方案二与方案三的组合：S9 拆成有明确顺序的三个测试阶段，每个阶段使用独立
ApplicationContext，外部触发阶段采用方案三。

- 外部触发不得复用启动 server 的 Service、Repository 或领域对象。
- 新 server 通过 ExternalTask id 和 companyId 定位 WAITING ExternalTask。
- `CompleteExternalTaskHandler` 在一个 PostgreSQL 命令事务中完成 ExternalTask，
  重新装载 RUNNING Execution 和绑定 Flow，并继续推进原 PAUSE TaskRun。
- S9 的启动、外部触发和终态验收必须属于不同测试用例。阶段间只共享外部关联
  companyId；Execution、TaskRun、ExternalTask、Flow 和 Service 均从 PostgreSQL
  重新取得。
- UC 测试可以在启动阶段观察 RUNNING/WAITING 稳定态，但完整测试类收尾时不得
  遗留 RUNNING Execution 或 WAITING ExternalTask。
- 正常线路最终为 COMPLETED；取消和失败场景保持 CANCELED 或 FAILED，不伪装为
  正常完成。
- 测试使用确定性的外部输出生成器模拟外部参与者；生产环境的 HTTP、消息或定时
  传输协议不由本 ADR 规定。

## 理由

三个测试阶段满足“不能在同一测试用例中完成”的验收约束；新的
ApplicationContext 是应用重启恢复的最小可靠证明，同时继续复用既有公开
Service、CommandExecutor、Repository 和事务边界。显式顺序仅编排完整 UC，
领域运行状态仍只存在 PostgreSQL 中，不需要增加重复运行表。

## 后果

- `assignment`、`executions`、`task_run` 和 Flow 定义必须完整支持持久化重建。
- 新增 Task 类型若可能跨 server 等待，生产 server 必须能够解析该持久化类型。
- UC 数据可以保留用于观察，但只保留终态 Execution；中间等待事实可从已完成
  Assignment 和 TaskRun 历史复核。
- 外部传输 Adapter 后续可以调用同一个 ExternalTaskService，不得复制恢复逻辑。
