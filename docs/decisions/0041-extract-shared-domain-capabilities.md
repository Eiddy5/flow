# ADR 0041：抽取自闭环的共享领域能力

## 状态

Accepted

本决策补充 ADR 0002、ADR 0022 中已有的身份、审计、软删除和乐观锁语义，不改变
各聚合的业务边界、持久化结构或用户可观察生命周期。

## 背景

Flow Core 中多个领域对象都拥有稳定技术 ID，Flow 与 FlowDraft 还共同保存创建、
更新和删除审计，FlowDraft、Execution 与 ExternalTask 分别维护乐观锁版本。此前
这些事实主要散落在具体对象中：`Identifier` 只能读取 ID，`Deletable` 只有不携带
上下文的空泛 `delete()` 与删除判断，导致实现者可以返回 `null` 或提供不完整的
占位实现；审计和锁版本则没有统一的查询、校验与推进契约。

公共能力需要表达一组闭合语义，而不只是标记某个字段存在。同时，这些能力是多个
业务领域共享的属性，不应为了每个小能力建立独立文件夹或公共父类层次。

## 备选方案

### 方案一：继续由每个领域对象分别实现字段和方法

领域对象保持完全独立，但调用方无法通过稳定能力使用它们，相同的身份匹配、版本
校验和审计上下文转换会持续重复，也容易再次出现空实现。

### 方案二：建立带公共字段的领域基类

基类可以复用状态，但会把身份、审计、删除和并发强制绑定成一种继承组合。不同
对象实际拥有的能力不同，单继承也会让各自聚合的创建、重建和不变量受公共存储
形状支配。

### 方案三：在 domains 根目录提供可组合的能力接口和值对象

接口只定义完整行为和查询契约，状态仍由具体领域对象拥有并保护；对象只组合自己
真实具备的能力。跨领域共享的 ActorRef 作为值对象与这些接口平级放置。

## 决策

采用方案三。

### 目录与依赖

- `Identified`、`Auditable`、`Deletable`、`Lockable` 和 `ActorRef` 直接位于
  `org.cses.flow.core.domains`，不新增 `common`、`identity`、`audit`、`deletion`
  或 `lock` 文件夹。
- 能力接口不拥有持久化状态，不依赖 Repository、数据库对象或 HTTP 协议。
- PAAS `Session` 是当前用户、公司等可信调用上下文，只在领域行为执行期间传入；
  聚合不保存 Session，只保存由它生成的不可变 `ActorRef`。
- 一般业务 timestamp 继续由服务器调用边界读取一次并以 `long` 显式传入，Session
  不替代操作发生时间。

### Identified

`Identified` 表达一个稳定技术身份，并形成以下闭环：

- `identifier()` 查询身份；该领域式方法名不会被 Jackson 当成额外协议字段；
- `identifiedBy(...)` 判断候选 ID 是否指向当前对象；
- `requireIdentifier(...)` 在身份不匹配时明确拒绝。

Flow、FlowDraft、Execution、Task、TaskRun 和 ExternalTask 使用该能力。值对象
ActorRef 不实现该能力，因为它表达审计引用值，而不是具有独立生命周期的实体。

### Auditable

`Auditable<T>` 继承 `Identified`，并形成以下闭环：

- 查询创建人、创建时间、最后更新人和最后更新时间；
- 通过 `updateAudit(Session, updatedAt)` 从可信上下文记录一次更新审计；
- 创建审计仍由各领域对象的 `create(...)` 或业务创建入口一次建立，重建入口沿用
  已持久化事实。

Flow 与 FlowDraft 使用该能力。领域对象必须拒绝倒退的时间，已删除对象不能继续
修改审计。

### Deletable

`Deletable<T>` 继承 `Auditable<T>`，并形成以下不可逆软删除闭环：

- `delete(Session, deletedAt)` 执行删除并同时记录更新审计和删除审计；
- `isDeleted()` 判断删除状态；
- `deleter()` 与 `deletedAt()` 查询删除人和删除时间，未删除时返回空值；
- 重复删除必须拒绝，失败操作不能留下部分删除、审计或并发版本变化。

只有真实拥有软删除生命周期的 Flow 与 FlowDraft 使用该能力。Execution 不可软
删除，不能再通过返回 `false` 或 `null` 的占位方法实现它。

### Lockable

`Lockable<T>` 继承 `Identified`，仅表达技术乐观锁版本，不表示数据库行锁或业务
锁定状态，并形成以下闭环：

- `lockVersion()` 查询当前版本；
- `lock()` 推进版本并返回当前对象；
- `hasLockVersion(...)` 判断预期版本；
- `requireLockVersion(...)` 在版本冲突时明确拒绝。

FlowDraft、Execution 与 ExternalTask 使用该能力。Execution 仍遵守一个已持久化
聚合在同一命令内最多推进一次版本的既有规则；TaskRun 不独立持有乐观锁。对同时
具备审计和锁能力的 FlowDraft，一次审计更新、修改或删除只推进一次版本。

### ActorRef

ActorRef 是共享的不可变值对象。`ActorRef.from(Session)` 以当前用户 ID 为首选身份，
以 Session 中的用户或会话信息作受控补充，并保存可选显示名称。执行审计或删除前，
拥有 `companyId` 的聚合必须校验 Session 公司与自身公司一致。

### 当前能力映射

| 领域对象 | Identified | Auditable | Deletable | Lockable |
| --- | --- | --- | --- | --- |
| Flow | 是 | 是 | 是 | 否 |
| FlowDraft | 是 | 是 | 是 | 是 |
| Execution | 是 | 否 | 否 | 是 |
| ExternalTask | 是 | 否 | 否 | 是 |
| Task | 是 | 否 | 否 | 否 |
| TaskRun | 是 | 否 | 否 | 否 |

Task 的具体类型继承 Task 的身份能力。FlowId、ActorRef、Input、Output、State、
TaskRoute、Express、TemplateExpression 和 RunResult 都是值或定义，不拥有独立
生命周期，因此不为统一接口而伪装成实体。Approval 模块继续遵守 ADR 0038 的独立
边界；它不能反向依赖 server 中的 Flow Core 能力接口，其公共能力需要等未来存在
真实跨模块复用需求时再提取到双方共同依赖的内核模块。

## 理由

- 每个接口都同时包含动作、状态查询和失败校验，不再只是字段标记。
- 组合接口允许对象只声明真实能力，避免公共基类制造无意义字段和空实现。
- Session 保留完整调用上下文，ActorRef 保留稳定审计事实，两者职责不会混淆。
- 公共类型直接放在 domains 根目录，符合共享属性的规模，不引入浅目录层次。

## 后果

- 旧 `Identifier` 由 `Identified` 取代。
- Flow 与 FlowDraft 的删除调用优先传入当前 Session 和同一次服务器 timestamp；
  ActorRef 重载可用于已确认的领域内部协作、重建辅助和聚焦测试。
- Repository 仍读取具体聚合的字段，不持久化能力接口或 Session，因此不需要数据库
  迁移。
- 新领域对象只有在完整实现对应闭环时才能声明某项能力；仅拥有同名字段不足以实现
  接口。
