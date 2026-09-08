# Flow 领域职责收敛与链路消融实验报告

日期：2026-09-08。状态：评估与实验方案，尚未实施。

本轮只产出报告，不修改生产代码、测试、数据库、UC、领域词汇表或已接受的 ADR。观察对象为当前工作区，性能计数引用同一会话的[上一轮诊断](/System/Volumes/Data/workspace/java/flow/docs/harness/flow-operation-cost/README.md)。文中的目标方法、I/O 预算和实验收益都是待验证方案，不是已实现能力或测试结果。

## 1. 结论

可以开展这项实验。建议以**一个明确业务动作在一个一致性阶段内完成**为单位，形成：

```text
接收请求，完成边界解析与身份检查
    → 加载本次动作必需的领域事实
    → 调用所属领域的方法，完成规则判断和状态变化
    → 得到变化结果，以及有实际用途的领域事实
    → 保存变化后的聚合
    → 保存成功后投递必要消息，返回操作结果
```

需要分别验证两个命题：

1. **职责命题：**领域规则是否有唯一归属，应用方法是否只组合步骤，数据库技术是否止于适配器。
2. **性能命题：**重复读取、保存的数据量、树扫描和排队等待是否减少。

职责收敛使 I/O 可见、可计数、可优化，但仅移动方法不会自动减少 SQL。实验不能把业务规则搬迁、批量查询、差量保存和并发调度同时改完，再把全部收益归因于领域设计。

本报告建议保留 Flow 与 Execution 两个现有聚合根，收敛其行为；TaskRun、Generation 等继续由所属聚合管理。跨定义与运行状态的编排计算可以是无数据库访问的领域协作逻辑，不需要全部塞入一个越来越大的 `Execution` 类。

## 2. 对“一次操作”的精确定义

一次操作不是整个流程从开始运行到结束，也不是跨 Worker 回调的一次长事务。它是对一份已加载事实进行判断并完成一次可保存变化的阶段。

| 场景 | 应用单元 |
| --- | --- |
| 新建、编辑、发布或删除定义 | 本次定义动作；保存时按既有规则追加版本 |
| 接受启动、恢复、取消或退回请求 | 校验请求并持久化命令，返回受理结果 |
| 消费上述命令 | 重新加载当前事实，应用动作，保存，再发推进信号 |
| 调度下一批任务 | 基于精确 Flow 版本和当前 Execution 计算下一步，保存必要变化 |
| 调用 Worker | 保存领取状态之后执行外部工作；不跨外部调用持有业务事务 |
| 合入 Worker 结果 | 重新读取 Execution，校验结果仍适用，合入并保存 |
| 列表、详情、预览、退回计划 | 只读操作，不为了统一模板而保存或制造事件 |

因此，“每个阶段加载必需对象、每个变化聚合保存一次”是可审查目标；“整个用户操作只能一读一写”不是合适的硬约束。一个聚合读取可能需要多条 SQL，一次保存也可能包含版本分配、父子记录持久化和冲突检查。

## 3. 当前已经具备什么，还缺什么

### 已有基础

- `Flow.create/revise/initialize` 已经在内存中维护定义和生命周期，上一轮探针计数为 0 条 SQL。
- `Execution` 已拥有开始、完成、取消、恢复、退回和 TaskRun 集合管理行为；TaskRun 的修改方法大多为包内方法，由 Execution 调用。
- `Pause.validateResume`、`Route.matches`、`Loop/LoopUntil.decideAfterIteration` 已经承担各自的规则。
- `ExecutorService.process` 的现有职责是推进一个内存周期，其调用者负责保存和 Worker 调用；不需要重新建设另一个同功能引擎。
- 当前领域源码未发现直接依赖 JOOQ、Repository 或 Queue 的运行访问。缺口主要在规则归属、技术接口外露和调用结果如何传递。

### 具体收敛点

| 当前位置 | 观察到的问题 | 建议归属或处理方式 |
| --- | --- | --- |
| [PublishFlowHandler.handle](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/services/flows/handlers/PublishFlowHandler.java:73) | 同时控制格式解析、草稿容错、校验遍历、生命周期选择和保存 | 格式转换留在 Parser；定义不变量由 Flow/Task/Input 拥有；Handler 组合这些步骤 |
| [ExecutionService.validateResume](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/services/executions/ExecutionService.java:365) 与 [handleResume](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/executor/handlers/ExecutionCommandEventHandler.java:261) | 两处分别判断状态、Task 类型和恢复数据；状态规则又部分存在于 Execution | 共用领域判断与 Pause 数据规则；请求受理和消费重投的不同处置仍由各自应用入口决定 |
| [ExecutionService.validateRewind](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/services/executions/ExecutionService.java:446) 与 [RewindPath](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/services/executions/RewindPath.java:18) | 纯领域路径规则放在 Service 区域；消费者反向调用 Service 的静态方法 | 路径与影响范围成为执行领域中的只读协作逻辑；Execution 应用合法变化；应用入口不再作为规则库 |
| [searchIterativeScope](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/executor/ExecutorService.java:534) | 名为搜索，内部却在第 580 行推进 Generation | 查询只产生下一轮意图，显式应用步骤推进代次；保持原有可观察状态顺序 |
| [ExecutorService.applyResult](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/executor/ExecutorService.java:285) | Worker 信封、输出校验和执行状态迁移在同一处组合 | Worker 信封在边界解包；Task 校验输出；Execution 负责本次结果是否合法及聚合状态变化，复用现有 RunResult |
| [ExecutionCommandEventHandler.handle](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/executor/handlers/ExecutionCommandEventHandler.java:86) | 子处理完成后不返回处理结果，入口再读一次 Execution 决定信号 | 处理步骤返回已加载/保存的结果；验证消费者能重检状态后，评估移除仅为补齐结果的回查 |
| [ExecutorEventMessageHandler.process](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/executor/handlers/ExecutorEventMessageHandler.java:213) | 一个方法横跨调度、保存、Worker 领取、调用、结果合入和再次投递 | 明确拆成调度、领取、外部执行、结果合入的阶段；领域方法内部不再穿插这些 I/O |
| [ExecutorContext.captureState](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/executor/ExecutorContext.java:143) | 每个调用点需要记得标记变化，否则保存边界难以从行为结果判断 | 在目标方案中由行为结果表达是否变化；先复用现有结果与暂存能力，不新建通用变更跟踪框架 |
| [CommandContext](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/services/CommandContext.java:17)、[FlowRepository](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/repositories/flows/FlowRepository.java:8)、[ExecutionRepository](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/repositories/executions/ExecutionRepository.java:13) | 核心持久化接口和命令上下文直接暴露 DSLContext | 在独立实验组中把数据库上下文收回 PostgreSQL 适配器，复用现有 Repository 接口而非再包一层 Repository |
| [FlowController.drafts](/System/Volumes/Data/workspace/java/flow/server/src/main/java/org/cses/flow/controller/flow/FlowController.java:42) | 视图装配反复触发完整聚合查询 | 查询需求在一个读取入口中明确，批量完成；纯视图映射不发起数据库读取 |

这些是职责和调用关系问题，不能仅按类名、代码行数或方法数量判断。已有短小领域方法应保留，复杂但内聚的编排算法也不应为了“方法短”被拆成大量无语义转发。

## 4. 各领域应拥有的最小职责

| 归属 | 拥有的事实与规则 | 组合方式 |
| --- | --- | --- |
| Flow | 定义身份、草稿与正式状态、定义合法性、Task 稳定身份、发布与删除限制 | 复用 create/initialize、revise、发布校验和 delete；先收敛重复构造路径 |
| Task 与具体编排 Task | 单节点定义、输入输出契约、子节点结构、条件命中、循环结束、Pause 恢复参数 | 复用 Task 能力和具体类型的方法；节点声明自己的规则，不保存 Execution |
| Execution | 执行绑定、有效运行路径、TaskRun 集合、聚合生命周期、恢复/取消/退回的合法性 | 通过明确业务动词操作子实体，保护跨 TaskRun 的不变量 |
| TaskRun | 某次运行的状态、输入输出、错误、父运行关联和轮次事实 | 自身维护单次迁移，由 Execution 控制聚合内修改，不引入独立保存调用 |
| Generation | 当前与历史代次、退回影响集合、代次推进与归档 | 复用 start/advance/complete，由 Execution 或所属 TaskRun 管理 |
| State | 状态值、状态历史及合法路线的基础表达 | 状态属于哪个对象、能否恢复或取消，仍由该对象判断 |
| Input、Output、Condition、表达式 | 值约束、规范化、条件和表达式求值 | 接收明确数据，不自行读取数据库或宿主服务 |
| 执行领域的协作逻辑 | 定义因果路径、可见输出、下一个可运行节点、作用域收敛和退回范围 | 优先纯化现有 ExecutorService、RewindPath；计算与应用分开，不加入数据库和 Worker 调用 |
| 应用层 | 认证与输入边界、加载哪些事实、调用顺序、保存时机、错误处置、消息投递 | 方法表达一个用例阶段；通过领域结果组合下一步 |
| PostgreSQL Adapter | 查询、批量装配、存储映射、版本分配、CAS、原子父子保存、数据库异常 | 只保存已确定的领域事实；不判断审批、路径命中或哪个状态该发生 |
| Worker 与宿主扩展 | 外部调用、日志、业务系统动作，以及受控的任务结果 | RunnableTask 可产生外部副作用；这部分不能冒充无 I/O 的领域计算 |
| 宿主业务 | 审批权限、单据状态、待办与业务回滚规则 | 在宿主组合自己的领域行为与 Flow 公开操作，不进入 Flow 的通用状态机 |

“领域方法不访问 I/O”在本方案中特指定义、运行状态及编排决策的方法。项目中的 RunnableTask 虽然拥有 Task 定义身份，实际 `run` 是外部执行能力，必须由 Worker 边界调用。

跨 Flow 定义与 Execution 的规则不必制造第三个聚合。可以在执行领域内保留小而明确的协作逻辑，接收只读定义和当前运行事实，再通过 Execution 的行为应用结果。避免让 Execution 直接依赖具体宿主 Task 类型、数据库对象或 Worker 传输信封。

## 5. 全链路的目标操作形态

下表是目标职责划分，方法名称沿用已有能力或描述行为，不是本轮新增接口清单。读写数量以无冲突的一次应用阶段为单位，不计独立的消息消费与重试阶段。

| 操作 | 加载的事实 | 领域动作 | 保存与事件 |
| --- | --- | --- | --- |
| 新建本地编排 | 当前请求中的定义；此时可无数据库对象 | Flow 创建、Task 组装、定义检查 | 显式保存前 0 I/O；没有订阅需求时不强制产生事件 |
| 保存新草稿或修订草稿 | 当前草稿或不存在的事实；已解析定义 | Flow 初始化或 revise，保留草稿源码语义 | 保存一个 Flow；回显使用保存结果 |
| 发布 | 待发布定义、已有草稿（仅 source 未提供时）、上一正式版本 | 校验发布条件，绑定稳定 Task ID，形成正式定义 | 保存一个 Flow；若需要发布事实，保存后补齐其持久化版本 |
| 删除草稿或正式定义 | 被明确选择的当前 Flow | Flow.delete | 保存一个删除状态；保留历史版本语义 |
| 启动受理 | 可启动的当前或指定版本 Flow | Flow 输入规范化、启动资格判断 | 持久化 Create 命令，返回受理结果 |
| Create 消费 | 同 ID Execution 是否存在、精确版本 Flow | Execution 创建；重复请求对比既有绑定与输入 | 新建时保存 Execution；重复投递也可能需要补发推进信号 |
| 下一个调度阶段 | 当前 Execution、其绑定的只读 Flow | 计算运行计划；Execution 应用新增运行、暂停、收敛或代次变化 | 有变化才保存；按需安排 Worker 或下一次推进 |
| Worker 领取 | 最新 Execution、当前目标运行及定义 | Execution 校验并开始精确 TaskRun | 先保存领取状态，再交 Worker |
| Worker 结果合入 | 最新 Execution、精确 TaskRun 与输出定义 | Task 校验输出；Execution 应用成功、警告、失败或终止 | 保存一次；冲突后只重读并重新合入已有结果 |
| 普通 Resume 受理 | Execution、绑定 Flow、精确 Pause TaskRun | 共用恢复资格和 Pause 数据规则 | 只投递命令；非法请求在受理阶段明确拒绝 |
| Resume 消费 | 最新 Execution、绑定 Flow | 同一规则基于新事实判断；Execution 恢复 TaskRun 和必要祖先 | 有变化才保存；已处理的重投按消费协议处置 |
| resumeWhenPaused | 相同领域事实，另有明确受理模式 | 可接受暂停前区间，但不得跳过必需前置任务 | 保留现有等待语义，不能当作普通 resume 的静默放宽 |
| Cancel | 当前 Execution | Execution 判断取消资格、进入 KILLING、后续收敛 | 按受理/消费阶段分开；不直接批量 SQL 修改 TaskRun 状态 |
| 退回候选和计划 | Execution、精确 Flow、宿主指定候选 Task keys | 只读计算合法前驱与影响范围 | 0 保存、0 事件，不改变 Generation |
| Rewind 消费 | 最新 Execution 和精确 Flow | 重新计算/确认路径；Execution 使受影响运行失效，重开必要祖先并维护 Generation | 保存一个 Execution；无关并行分支保持原事实 |
| 列表、详情、运行状态轮询 | 展示需要的列与关联数据 | 必要时调用只读计算；不要求恢复每个完整聚合 | 查询入口一次声明读取需求，映射过程不隐式追加查询 |
| 插件目录、类型元信息、YAML 预览 | 类型注册信息或请求源码 | 格式解析和元信息读取 | 维持其独立只读路径，不强行套入聚合保存模板 |

定义树的增删移动如果需要成为宿主可调用能力，应是“定义组装/修订”的内存行为。当前页面已经在本地完成这些动作；不能为每个节点属性新增一个必定落库的命令，重新制造细碎 I/O。

## 6. 怎样让方法干净，而不是把大业务换个地方堆放

### 方法的审查标准

每个方法应能够回答以下问题：

| 问题 | 合格表现 |
| --- | --- |
| 它维护谁的规则？ | 能指向一个领域概念，或一个明确的应用协调阶段 |
| 输入是否足够？ | 所需事实由调用方给定；不会执行到一半才查库补字段 |
| 它是查询还是变更？ | 查询不修改状态；变更用业务动词，并明确副作用 |
| 前置条件和结果是什么？ | 非法动作有稳定失败语义；合法动作保持领域不变量 |
| 多步修改如何失败？ | 先完成会拒绝的校验和计算，再应用变化；失败对象不进入保存或发布流程 |
| 是否在同一抽象层级？ | 应用方法组合业务步骤；不会同时展开 SQL、YAML 字段清理和状态迁移细节 |
| 组合是否增加无意义层次？ | 直接复用已有领域方法；不添加只转发的 Manager、Facade 或通用 Handler |

领域行为可以受控修改对象，不要求全部改为返回新对象的函数。先明确状态所有权与失败语义，比增加复制、泛型 Result 或事件总线更直接。方法长短仅作提示，不设置机械行数门槛。

### 三条代表链路

**定义发布：**边界解析 source → 应用层加载上一正式 Flow → Flow 执行发布校验与身份绑定 → Repository 追加保存 → 使用保存结果返回。YAML 容错不进入 Flow，版本历史查询不进入 Flow，发布后也不让 View 映射自行查库。

**恢复 Pause：**应用层加载 Execution 和绑定 Flow → 领域协作逻辑选择精确 Pause 并调用其数据校验 → Execution 校验有效路径并恢复运行 → 保存 → 投递推进信号。选择定义、校验数据和修改运行各有所有者；外层方法不展开各自规则。

**循环推进：**查询当前轮次和子作用域结果 → Loop/LoopUntil 判断继续或结束 → 计算下一步意图 → Execution 显式应用代次与运行变化 → 保存。当前 `searchIterativeScope` 中的代次修改必须成为显式应用步骤，查询本身可重复调用而不改变结果所依赖的状态。

这三条链路复用现有概念即可。跨对象算法只在确有独立规则时作为领域协作方法存在，不创建“处理所有 Flow 业务”的总方法，也不为每个 if 拆一个可插拔接口。

## 7. 事件与保存的关系

### 区分三种东西

| 类型 | 含义 | 归属 |
| --- | --- | --- |
| 命令 | 请求做某件事，例如 Create、Resume、Cancel、Rewind | 应用入口与命令传输 |
| 领域事实 | 本次合法行为实际发生了什么，例如某次运行已恢复 | 由领域行为结果表达；名称在实施时按真实订阅需求确定 |
| 执行推进信号 | 提醒运行器重新读取并推进 Execution | 现有 ExecutorEvent 与 Queue Adapter |

现有 `ExecutorEvent` 只携带执行身份与 CREATED/UPDATED/TERMINATED，属于推进信号，并不是完整领域事实记录。复用它承担现有调度作用，不能把它误当作所有领域事件的通用模型。

领域事实可以通过现有或最小的具体返回结果承载。没有实际观察者的普通草稿编辑，不必为了统一风格新增事件类。也不需要因为有事件就改成事件溯源、逐事件保存或引入新的消息中间件。

### 时序与失败处理

```text
领域行为成功，得到变化与内存事实
    → 聚合保存成功
    → 以保存后的身份/版本构造需要投递的消息
    → Queue 接受消息
    → 消费者重新加载当前事实后执行下一阶段
```

| 情况 | 必须保留的处理 |
| --- | --- |
| 领域校验失败 | 不保存，不对外发布本次变化 |
| 保存发生 CAS 冲突 | 不发布这次未提交变化；读取新快照后重新判断/应用 |
| 保存成功但消息投递失败 | 承认两个操作之间的窗口；使用现有命令重投补发，验证每条链路，而不是宣称天然原子 |
| 命令重复投递、领域已无变化 | 不等于可以省略推进信号；前一次可能恰好在保存后投递失败 |
| Worker 返回时已被取消或退回 | 基于最新有效状态处置旧结果，不覆盖其他分支或旧代次 |
| 领取状态已保存、Worker 尚未调用时进程退出 | 验证工作意图能否恢复；不能仅因领域保存成功就认定任务一定执行 |

Flow 的内部版本由 Repository 分配。领域方法先产生的事实不能提前伪造最终版本；需要版本的外部消息在保存后使用返回对象补齐。

本轮保持 ADR 0084 的现有消息协议，不增加覆盖 Worker 的事务。若后续要求聚合保存与可恢复消息意图原子提交，需要单独设计短暂的存储提交边界，并比较复用现有数据库队列与其他方案；不能仅调用 `save` 后接 `publish` 就声称解决了可靠性。

## 8. 数据库技术如何退出业务组合

目标是现有 Repository 对调用方只呈现“读取什么领域事实、保存哪个聚合、如何报告冲突”。`DSLContext`、JOOQ Configuration、生成表类型、xmin、JSONB 映射、连接池和批量 SQL 由 PostgreSQL Adapter 负责。

当前 `CommandContext` 的 Session 与 Command 可以继续承担调用上下文；其中 DSL 和配置的安装/恢复不应成为每个业务方法都要了解的约定。先在技术隔离实验中把这些细节移入已有适配器，保留可信 Session、具名 flow 数据源和并发冲突语义，不另建一个包装现有 Repository 的通用 UnitOfWork 框架。

技术隔离实验应把 SQL、查询数量和保存顺序保持不变。它验证的是上层可否不依赖 JOOQ，而不是查询速度。当前规范明确要求传入 DSL，因此该目标是待确认的接口演进，不能宣称已经符合现行所有规范。

数据库仍负责租户条件、唯一性约束、完整恢复、版本分配和并发保护。这些保护可以与领域校验同时存在；它们不替代“哪条路径应执行、哪个任务可恢复”的业务判断。

查询用途与运行用途也要分开：展示列表可以采用专用摘要，运行命令需要完整领域事实。避免为列表恢复整包聚合，也避免把列表摘要当成可保存的 Execution。

## 9. 消融实验设计

这里的“消融”是对耦合来源进行受控移除并比较。先固定现状，再逐项改变，最后对组合方案撤回单项以判断贡献。由于本轮明确只产报告，下面所有候选组均为**未运行**。

### 已有基线证据

| 指标 | 上一轮观察值 | 证据限制 |
| --- | --- | --- |
| 内存 Flow 创建、修订、正式初始化 | 0 条 SQL | 验证调用链没有存储访问，不是新方案收益 |
| 50 个草稿均有正式版本的列表 | 101 条 SQL | 真实代码、Mock JDBC |
| 已有正式版本的草稿保存 / 发布 | 5 / 6 条 SQL | 正确构造命令的 Controller 调用，未含 HTTP 绑定 |
| 1000 TaskRun 中仅修改一条再保存 | 1 条 SQL、103,212 字符、13,019 个参数 | SQL 构造规模，不代表实际数据库耗时 |
| 1000 个平铺任务恢复 | 1,001,000 次父子关系检查 | 真实恢复方法的遍历计数 |

### 实验组

| 组 | 唯一主要变量 | 保持不变 | 判断依据 |
| --- | --- | --- | --- |
| B0 现状 | 无 | 冻结代码、配置、数据和入口 | 记录基线，不重复把现状问题当收益 |
| D1 领域收敛 | 恢复、退回、结果应用等规则归入所有者 | 调用顺序、SQL、保存阶段、命令受理语义、调度周期与外部消息行为 | 领域规则不再由多处分别实现；功能等价；SQL 不应凭空下降 |
| D2 查询变更分离 | 把搜索方法中的状态修改变为明确的规划结果与应用步骤 | D1 的规则归属、同周期变化顺序、SQL 和保存阶段 | 同一快照可重复查询而不改变 Generation；应用后的事实与原实现等价 |
| P 技术隔离 | Repository 接口与应用上下文移除 DSL 细节 | 同样的查询和保存实现、租户与 CAS 语义 | 上层可用现有持久化契约运行而无需 JOOQ；I/O 计数基本相同 |
| L 结果复用 | 一个应用阶段内返回并使用已经加载/保存的结果，取消仅为回显或传递结果的回查 | Worker 前后重读、消费时重检、版本语义与响应内容 | 每条删掉的读取有来源及并发理由；无新隐式查询 |
| Q 批量读取 | 草稿列表批量取得正式版本及 Task，先保留原响应内容 | 业务与领域行为、列表内容和排序 | N 从 1 到 50 时 SQL 不线性增加；目标最多约 3 次批量读取，待实现验证 |
| T 树装配 | 数据库条目按 parentId 一次分组再恢复 | 查询内容、节点身份、顺序、完整性检查 | 消除每个节点重扫整表；分组与遍历 O(N)，额外排序成本单列 |
| W 保存优化，第二阶段 | 在完整聚合保存契约下减少未变化子行更新 | 原子父子保存、显式 NULL、历史内容与 CAS | 比较实际更新行数、WAL、锁时间；不能只看 SQL 条数 |
| E 执行调度，第二阶段 | 分离同步 Worker 对消费通道的占用 | 持久受理、精确 TaskRun 领取、重投与取消语义 | 比较慢任务下其他执行的等待；同时验证崩溃恢复与重复副作用 |

W 应进一步区分两个实验：在 SQL 中跳过相同数据的 UPDATE，只减少实际写行；减少传入的未变子记录，才可能减少参数和传输。后者需要确定原始快照差异来源，不能要求领域对象输出表字段或 SQL 补丁，也不能把前者的收益误记为后者。

分页摘要、增量轮询、缓存不可变定义、压缩调度周期以及合并“保存再发布”涉及读取策略、响应内容或时序边界，放在独立后续组。第一轮不混入，否则无法解释职责收敛究竟产生了什么效果。

### 对照顺序

1. 先比较 B0 与 D1，再比较 D1 与 D1+D2，分别确认规则归属和查询纯化保持行为与 I/O 阶段。下面用 D 表示通过验证的 D1+D2。
2. 以同一个 D 版本分别比较 D+P、D+L；Q 和 T 可以从同一冻结基线独立比较。
3. 组合已通过的 D/P/L/Q/T，再分别撤回可独立撤回的因素做对照。依赖项不能独立撤回时，比较共同前置版本，不虚构不能构建的实验组。
4. W 和 E 涉及较大存储/运行协议影响，单独开展，不计入第一轮“搬迁领域逻辑”的收益。

评估报告必须同时记录功能差异、I/O 差异与维护成本。只证明 SQL 下降，不能证明领域归属更清楚；只证明方法更短，也不能证明性能提高。

## 10. 数据集、指标与结果判定

### 最小数据集

| 维度 | 建议取值 | 目的 |
| --- | --- | --- |
| 草稿与正式版本数量 | 1、10、50 | 检查列表是否仍逐条查询 |
| 定义 Task 数量 | 1、100、1000；平铺与嵌套分开 | 检查任务树恢复与领域计算成本 |
| Execution 历史长度 | 1、100、1000 个 TaskRun | 检查完整读取、状态合入与保存增长 |
| 运行组合 | 串行、并行 Pause、Route、固定/条件循环、嵌套退回 | 检查领域归属调整是否保留原语义 |
| 并发与 Worker | 单执行与多个执行；受控立即返回与慢 Worker | 区分计算、数据库与排队瓶颈 |

先一次放大一个维度，不运行所有维度的笛卡尔积。性能用受控任务隔离宿主波动；宿主真实业务正确性另行验证，不能用受控任务结果替代。

### 固定条件

- 记录 commit、工作区差异、Schema、JDK、依赖、连接池与消费配置。上一轮本机诊断使用 JDK 25.0.4，不能把不同运行环境的时间直接比较。
- 每组使用同样的领域事实、历史长度和租户数据；会追加 Flow 版本的动作必须在独立重置后的样本上比较，不能后一组天然拥有更多历史。
- 冷、热缓存分开；预热后至少进行 5 轮独立采样，保持每轮请求数一致，报告轮间波动和 p50/p95。实际样本量不足时不据此推断尾延迟。
- 读取、保存、队列领取/ACK、消息发布和重试分别计数。对异步入口分别记录“命令受理耗时”和“推进到目标状态耗时”。
- 不在生产流量上双跑有外部副作用的 Worker。影子比较限于同一加载快照上的无 I/O 计算与结果对比。

### 指标

| 类别 | 记录内容 |
| --- | --- |
| 职责 | 领域规则拥有者、重复业务判断的位置、查询中的状态变更、上层数据库技术依赖 |
| 方法可组合性 | 能否只传领域事实调用，是否隐式查库/发消息，是否要额外调用标记方法才会正确保存 |
| 数据库 | SQL 次数、总时间、返回与写入行数、参数和数据量、连接等待、锁等待、WAL |
| 运行 | 领域计算耗时、分配量、Task/TaskRun 遍历量、Worker 耗时、队列等待与积压 |
| 正确性 | 最终状态、路径、输入输出、版本、代次、审计语义、外部消息和副作用次数 |

Domain 的直接数据库调用预算为 0。对一个聚合的一次正常变化，目标是一个明确的保存调用；查询 SQL 的实际数量由 Adapter 解释。重复命令、并发冲突和异步阶段的额外读取必须单列，不通过排除这些场景制造漂亮数字。

## 11. 功能等价与故障验证

现有确认场景以 [Flow UC 索引](/System/Volumes/Data/workspace/java/flow/docs/uc/flow/README.md) 为依据。未来实施时：定义链路关联 UC-01，启动查询取消关联 UC-02，恢复关联 UC-04，并行与条件关联 UC-05/06，多阶段关联 UC-07，日志关联 UC-08，循环关联 UC-09，嵌套退回关联 UC-10。本轮只定位验证依据，没有执行或审计 UC 覆盖。

消融组必须保留以下事实，不能为了通过实验修改预期：

- 每次成功 Flow 保存仍追加版本，草稿源码容错与正式发布校验仍不同；删除当前正式版本不能使旧版本重新成为最新可启动版本。
- Execution 绑定原始精确 Flow 版本；Task 稳定身份、TaskRun occurrence、Generation 历史和无关并行分支保持正确。
- 普通 Resume 的严格受理与 `resumeWhenPaused` 的前置等待不同；新的非法请求和同一命令重投的返回语义也不同。
- Worker 输出仍经定义校验；失败、警告、终止、Route SKIPPED、循环继续/结束等结果保持原语义。
- CAS 冲突、并行 Resume/Cancel、连续退回、旧 TaskRun 结果、重复 Create 输入冲突都必须有真实 PostgreSQL 验证。
- 在保存前、保存后投递前、消息接受后 ACK 前、Worker 领取后调用前、Worker 返回后结果保存前注入失败，检查恢复行为与副作用次数。

对比时优先使用同一恢复快照和可控输入。若只能规范化自动生成的 ID 或时间，必须保留身份关联、occurrence、代次和相对顺序；不能删除这些字段后仅对比最终 SUCCESS。新增加的内存事实允许与旧实现形式不同，但对外命令、信号、结果与副作用必须按约定等价。

任何语义不一致先判该组失败，再单独评估它是旧实现缺陷还是候选引入的问题；不能把修复旧问题混入职责实验后宣称完全等价。

未来需要新增/修改确认场景时由 UC Agent 处理，随后由 Test Agent 验证。本轮不生成 UC 或滚动测试报告，也不启动实验分支。

## 12. 实施顺序与既有决策关系

建议从 **Pause 恢复** 做第一个完整切片：它同时覆盖定义校验、运行状态、应用受理、异步消费、保存和信号，足以验证这套组织方式；先不混入复杂退回或 SQL 改写。取消可作为较小的参照切片。通过后依次覆盖定义保存/发布、退回、Worker 结果应用和整个编排周期。

| 项目决策 | 本方案的关系 |
| --- | --- |
| [ADR 0073](/System/Volumes/Data/workspace/java/flow/docs/decisions/0073-keep-flow-lifecycle-rules-in-domain.md) | 延续领域拥有生命周期、Repository 持久化事实的方向；版本条款以 0083 为准 |
| [ADR 0083](/System/Volumes/Data/workspace/java/flow/docs/decisions/0083-allocate-flow-version-in-repository.md) | 保留 Repository 分配版本及每次追加保存；不把版本历史读取搬入 Domain |
| [ADR 0084](/System/Volumes/Data/workspace/java/flow/docs/decisions/0084-save-domain-snapshots-without-business-transactions.md) | 第一轮保留完整快照、CAS、Worker 前后分段与现有消息重投；W/E 如改变协议需单独修订 |
| [ADR 0085](/System/Volumes/Data/workspace/java/flow/docs/decisions/0085-rewind-across-nested-orchestration-scopes.md) | 保留定义因果关系、精确失效范围与无关并行分支，不用简单时间顺序替代 |
| [CommandExecutor 规范](/System/Volumes/Data/workspace/java/flow/docs/standards/command-executor.md) | 当前 DSL 传参和命令/队列边界是现行规则；P 组属于拟议接口变化，确认实施后同步文档 |

第一轮的成功标准是：**规则回到明确所有者，应用方法只组合显式步骤，领域计算不触发存储，技术适配器能够独立优化，同时原有业务与并发语义得到保留。** 性能收益必须由各独立实验组给出，当前不填写预计提升百分比。
