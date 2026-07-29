# 工作流核心 Java 模型规范

## 包与依赖方向

- `core/domains` 只放领域对象和领域状态枚举，不得放 Factory、Reader、
  Snapshot、Reference、DTO、解析中间类型或持久化映射对象。
- `core/serializers` 保持扁平，只提供与业务类型解耦的格式能力；当前
  `YamlParser` 是 YAML 唯一语法入口。
- Repository 接口放在 `core/repositories/<业务模块>`；生产实现放在
  `infrastructure` 的真实持久化适配器中。
- JOOQ 生成类只能通过具体 Repository `entries` 子包中的 `XxxEntry` 使用；
  Entry、领域转换和 Map 写入规则见 `docs/standards/jooq.md`。
- 内存 Repository、伪事务和无连接 JOOQ Factory 只能存在于 `src/test`，
  不能注册为生产 Bean。
- Service 放在 `core/services/<业务模块>`。
- Command、Handler 和 Query 必须在各自 Core 技术目录下继续按 `flows`、
  `executions`、`externaltasks` 或真正跨领域的 `shared` 分包。
- Executor 状态机、上下文和下一任务模型统一放在与 `core` 平级的
  `org.cses.flow.executor`；Worker 调度协议、输入和结果模型统一放在同级的
  `org.cses.flow.worker`。Core 内不得建立 `executors` 或 `workers` 目录。
- CommandHandler 和 ExecutionHandler 按业务模块放在 `core/handlers` 的对应
  子目录中，不得直接平铺在 `core/handlers` 下。
- Controller 位于 Core 外部。
- 具体 Task 类型和 WorkerTaskHandler 实现是扩展单元，放在 `extensions`。

写链路固定为：

```text
Service -> CommandExecutor -> CommandHandler -> Repository/Domain
```

运行推进在 CommandHandler 内委托：

```text
ExecutionHandler -> ExecutorService -> WorkerDispatcher
```

查询链路固定为：

```text
Service -> QueryHandler -> Repository/JOOQ
```

## 领域模型

- FlowWithSource、Flow、Execution 和 ExternalTask 分别维护自己的领域规则。
- 禁止 Handler、Service、Executor 或 Worker 直接修改领域字段。
- 所有状态变化必须调用领域方法。
- `FlowWithSource` 是只保存原始 YAML 的来源草稿聚合，没有正式
  `reversion`；`Flow` 是部署时完成解析和校验后产生的完整定义聚合。
- FlowWithSource 与 Flow 是部署映射关系，不是继承关系，也不能使用
  `status + nullable version` 合并成一个领域对象。
- Flow 直接持有不可变 Input、Output 和 Task；TaskRun、Execution 和
  ExternalTask 查询也直接返回对应领域对象的隔离副本，不建立 Snapshot
  复制模型。
- Data 是 Input、Output 的基础接口，只提供 `getKey()` 和 `getType()`；
  Input、Output 是直接实现 Data 的具体不可变对象，作为所属
  Flow 或 Task 聚合内实体存在，不保存 TaskRun 的实际值。完整规则见
  [`data-domain-model.md`](data-domain-model.md)。
- Task 是 Flow 聚合内实体，没有独立 Repository、状态、审计字段或
  `reversion`；Flow 从通用只读映射一次性创建完整 Task，不存在或持久化
  `id = null` 的中间 Task。
- Task 的 `id` 跨 Flow reversion 稳定，TaskRun 以 `taskId` 引用该身份；
  完整规则见 [`task-domain-model.md`](task-domain-model.md)。
- Flow 只使用 `DEPLOYED/CLOSED`；Draft 是 FlowWithSource 的领域角色。
- `deploy` 是产生 Flow 和正式 `reversion` 的唯一业务入口；`close` 保留原
  `reversion`，不生成新版本。
- 仓储负责聚合副本隔离，Service/Query 不通过重复模型实现只读。
- Execution 是一次启动实例，不是游标。
- Execution 内的 TaskRun 列表保存真实执行历史；列表顺序是内存顺序。
- Execution 和 TaskRun 的目标字段、方法、五态状态机及聚合边界见
  [`execution-domain-model.md`](execution-domain-model.md)。
- 数据库使用独立顺序列还原 TaskRun 列表，该顺序列不进入领域对象。
- TaskRun.parentId 表示真实父 TaskRun，不表示前驱或调度原因。
- TaskRun 通过稳定 taskId 关联 Execution 绑定 Flow reversion 中的 Task 定义。
- 所有技术 ID 使用 `String`，且只通过 `StringUtil.newId()` 生成。
- 领域对象的普通业务创建统一由类型自身的 `public static create(...)` 完成；
  Core 不建立 `factories` 技术目录，也不通过工厂类或工厂接口包装领域创建。
- 领域对象构造方法不对领域类型外部公开；Repository Adapter 恢复持久化状态时
  使用静态 `rehydrate(...)`，不能调用 `create(...)` 生成新身份。
- 生产、测试及辅助模型一律不使用 Java `record`。

## Executor 与 Worker

- Executor 和 Worker 是与 Core 平级的运行组件；领域事实、Repository 端口和
  对外用例仍属于 Core。
- ExecutorContext 持有当前命令 Session、DSLContext、完整 Flow 和 Execution。
- ExecutorContext 是可重建的事务内上下文，不持久化。
- ExecutorService 是状态机，只计算状态与下一任务，不访问 Repository/JOOQ。
- 只有 `ExecutorService.handleNext()` 创建 TaskRun。
- ExecutionHandler 管理聚合加载后的生命周期和同事务中间保存。
- Worker 只执行 Task；WorkerTask 不携带 Session、DSLContext、Execution
  或可变 TaskRun。
- Session 和 DSLContext 只通过泛型 WorkerContext 传入。
- Worker 返回结果事实，由 ExecutorService 调用 Execution 领域方法合并。

## 状态与 PAUSE

- Execution 和 TaskRun 使用
  `CREATED/RUNNING/COMPLETED/FAILED/CANCELED`。
- 不定义 TaskRun WAITING。
- PAUSE 是 Task 类型；等待时 Execution 和 TaskRun 均保持 RUNNING。
- 等待来源由独立记录表达，例如 ExternalTask 的 WAITING 状态。
- resume 直接完成原 PAUSE TaskRun，再由 ExecutorService 安排下一任务。

## 事务

- 所有写操作都通过 Command，使用调用该命令的 Session。
- JOOQ DSLContext 是命令事务边界，不建立嵌套事务。
- 同步 Task 可以在一个命令内连续运行至卡点或终态。
- Worker 保存 Task 自己拥有的业务记录时仍使用当前命令 DSLContext。
- Worker 明确返回 FAILED 属于可提交的运行结果；未处理异常必须回滚。
- 修改已有 Execution 的一个命令最多增加一次聚合 lockVersion。

## 测试

- 场景测试只通过 Service 驱动写操作。
- 必须断言 Flow 绑定版本、Execution 状态、TaskRun 顺序和状态。
- PAUSE 场景同时断言 TaskRun RUNNING 与 ExternalTask WAITING。
- resume 场景必须证明没有重复创建 PAUSE TaskRun 或 ExternalTask。
- 取消场景必须证明其他 Execution 不受影响。
- PostgreSQL Adapter 完成前，验证顺序为目标测试和完整测试；完成后增加应用
  启动、数据库迁移和 HTTP 响应验证。
