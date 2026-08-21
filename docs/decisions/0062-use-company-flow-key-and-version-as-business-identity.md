# ADR 0062：以 companyId、Flow key 和 version 确定业务 Flow

## 状态

Accepted

本 ADR 修订此前把 Flow `id` 当作跨版本业务标识的约定；历史 ADR 中的
`flowId + flowReversion` 表述仅在明确指向已持久化 Flow 修订行时保留。

## 背景

Flow 的业务查询需要跨版本稳定。草稿的业务身份由创建请求提供的 `key` 和租户
`company` 确定；数据库生成的 `id` 只标识一行存储记录，不能作为业务选择器。
同一个公司内同一个 `key` 只能存在一个草稿。后续发布新版本时 key 不变；每个已
发布版本拥有新生成的技术 id，version 递增。因此，`companyId + key + version`
才能在租户内唯一确定一个业务 Flow 版本。

## 决策

- `FlowDraft.id` 是数据库行标记；`FlowDraft.flowKey` 是创建时必须提供的持久化
  Flow 业务 key。草稿的唯一约束为 `(company_id, flow_key)`，包括已软删除的行；
  key 在草稿生命周期内不可改变。
- 每次 `Flow.deploy` 都生成新的 `Flow.id`；发布新版本不复用上一版本的技术 id。
- Flow 的业务查询统一通过 `companyId + flowKey + flowVersion`，Repository 提供
  `findByKey` 和 `findLatestByKey`；数据库 latest 索引按
  `(company_id, key, reversion DESC)` 建立。
- YAML 顶层 `key` 在草稿修订中可以省略；省略时使用草稿 key，存在时必须与草稿
  key 一致，不能通过部署改变业务身份。
- Task YAML 的 `key` 同样可选；存在时保留外部值，缺少时在物化当前版本时生成。
  已提供 key 的 Task 继续按 key 复用跨版本技术 id。
- Executor 的 `Create` 是纯数据 record，只包含四个字段：
  `company`、`flowKey`、`flowVersion`、`inputs`。它不携带 Execution id、Session、
  Actor 或事务资源。
- `Create` 消费时先按四元组中的 Flow 三字段加载精确且未删除的 Flow，规范化 inputs，
  再生成并持久化新的 Execution，并在同一消费事务中投递首个 `ExecutorEvent`。
- 已经物化的 Execution 为了绑定不可变的历史修订，仍可保存并通过技术
  `Flow.id + version` 恢复 Flow 行；这只是运行时恢复路径，不是业务 Flow 选择器。

## 后果

- 新 Flow 版本不会改变历史 Execution 所绑定的 Flow 行。
- 启动请求的调用方不再能预先得到由自己生成的 Execution id；HTTP/API 只返回 Create
  的队列受理信息，Execution id 在消费者创建后可查询。
- 需要审计启动人的场景由已解析 Flow 的 creator 恢复消费 Session；Create 本身不再复制
  调用方 Session 快照。
- 旧代码把 `Flow.id` 或 `FlowDraft.id` 作为业务 key 的调用方必须改用 Draft/Flow
  的 `flowKey`；草稿 HTTP、Service 和 Command 入口统一按 `companyId + flowKey`
  选择。按 id 的方法仅供持久化适配器和已绑定的内部恢复路径使用。
