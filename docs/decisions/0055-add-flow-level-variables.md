# ADR 0055：增加 Flow 级别变量 Map

## 状态

Accepted（2026-08-13）

## 背景

Flow 需要保存一组由流程定义提供、并可被同一 Flow Reversion 下所有任务读取的流程级
配置。现阶段需求只需要简单的 `Map<String, Object>`，不需要为每个变量增加类型、默认值、
生命周期或独立管理接口。

`Flow.inputs` 表示 Execution 启动时由外部提交的 typed 输入，不能与流程定义变量混用；
TaskRun 的 `inputs` 和 `outputs` 也只保存一次运行中的交接事实。

## 备选方案

### 方案一：把变量放入 TaskRun inputs

可以复用现有 JSONB，但会在每个 TaskRun 重复保存同一份 Flow 配置，并把定义数据误认为
运行交接数据。

### 方案二：新增 Flow Variable 独立表和类型模型

可以支持更强的查询与管理能力，但超出当前简单 Map 的需求，增加 Schema、版本和维护成本。

### 方案三：Flow Reversion 持有 JSONB Map

变量随不可变 Flow Reversion 保存，通过 Flow 定义、route 和 RunContext 读取；后续若需要
可变 Execution 变量，再单独引入 Execution 级模型。

## 决策

采用方案三。

- Flow 定义支持顶层 `variables` 映射；缺省值为空 Map。
- Core 领域接口使用 `Map<String, Object>`，Flow 创建、复制和持久化重建时保留该快照。
- PostgreSQL `flows.variables` 使用 `jsonb NOT NULL DEFAULT '{}'::jsonb`，并约束顶层为
  JSON object；不新增变量表。
- Route 支持受限的 `variables.<key>` 条件，部署时校验引用的顶层 key 已声明。
- Worker 的 `RunContext` 通过保留键 `$flow.variables` 提供同一份只读 Map；不把变量复制到
  TaskRun inputs。
- Flow Reversion 和同一 Execution 内的 Flow Variable 均为只读；本 ADR 不提供 Task 写入
  或 Execution 覆盖能力。

示例：

```yaml
variables:
  environment: prod
  retryLimit: 3
```

```yaml
route: variables.environment == "prod"
```

## 后果

- Flow 版本快照、route 求值和 Worker 调用可以使用同一份流程级配置。
- 变量值暂不具备独立 DataType 校验；需要类型、密钥保护、可变运行值或按变量查询时，
  应新增后续 ADR，不在当前 Map 接口上继续堆叠隐式规则。
- `Flow.inputs` 继续承载启动参数，`Flow Variable` 与 `Global Context` 的定义来源保持可区分。
