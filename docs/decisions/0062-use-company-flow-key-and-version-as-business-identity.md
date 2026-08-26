# ADR 0062：以 companyId、Flow key 和 version 确定业务 Flow

## 状态

Accepted

本 ADR 修订此前把 Flow `id` 当作跨版本业务标识的约定；历史 ADR 中的
`flowId + flowReversion` 表述仅在明确指向已持久化 Flow 修订行时保留。
领域方法的命名已由 ADR 0067 进一步修订：`id()` 返回稳定字符串实体 ID；
`key` 与 `version` 继续作为真实业务字段决定业务版本。

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
- 每次发布新版本都由 Flow Domain 生成新的字符串 id，PostgreSQL Adapter 原样保存；
  该 id 不作为 Execution 的 Flow 版本引用。
- Flow 的业务查询统一通过 `companyId + flowKey + flowVersion`，Repository 的当前
  选择器契约由 ADR 0070 规定：完整版本使用 `findByFlowId`，逻辑 Flow 使用
  `findLatestByFlowId`；数据库 latest 索引按 `(company_id, key, reversion DESC)` 建立。
- YAML 顶层 `key` 在草稿修订中可以省略；省略时使用草稿 key，存在时必须与草稿
  key 一致，不能通过部署改变业务身份。
- Task YAML 的 `key` 同样可选；存在时保留外部值，缺少时在物化当前版本时生成。
  已提供 key 的 Task 继续按 key 复用跨版本技术 id。
- Executor `Create` 的当前字段、稳定 Execution id 分配和消费协议由 ADR 0068 修订。
- 已经物化的 Execution 通过 `companyId + flowKey + flowVersion` 恢复不可变的历史
  Flow；技术行 id 不参与运行时绑定。

## 后果

- 新 Flow 版本不会改变历史 Execution 所绑定的 Flow 行。
- 启动请求返回包含稳定字符串 `executionId` 的 Queue 受理信息，具体协议由 ADR 0068
  约束。
- 旧代码把数据库 `Flow.id` 或 `FlowDraft.id` 作为业务 key 的调用方必须改用 Draft/Flow
  的 `flowKey`；草稿 HTTP、Service 和 Command 入口统一按 `companyId + flowKey`
  选择。`flows.id` 可作为 Flow 实体 ID 读取，但不替代业务查询选择器。
