# Flow 操作链路与数据库开销排查

排查日期：2026-09-08。依据当前工作区源码，包括排查前已经存在的未提交改动。

已确认存在列表查询放大、运行历史整包读写和任务树重复扫描。运行器还存在单实例内部事件串行处理、同步等待 Worker 的吞吐限制。它们会影响多条操作链路；现场各项耗时占比尚未测定。

## 证据范围

- 使用真实 Controller、FlowService、CommandExecutor、Handler 和 Repository，通过 JOOQ Mock JDBC 记录实际生成和发出的 SQL；没有连接数据库。
- 使用真实 ExecutionRepository 保存合成执行快照；分别记录首次保存及仅修改一个 TaskRun 状态后的 SQL 大小和参数数量。
- 调用真实 FlowRepository 任务树恢复方法，统计遍历数据库条目时的父子关系检查次数。
- 这些是调用次数和算法开销证据，不是 PostgreSQL 执行计划、连接池等待、真实事务竞争、HTTP 耗时或业务验收结果。未提供独立测试库连接，因此未运行真实数据库负载测试。

## 领域职责现状

`Flow.create`、`revise`、`initialize` 已经是内存领域行为。本次连续执行创建、修改和正式定义初始化，SQL 数量为 0。

当前公开应用入口是 `FlowService.save(PublishFlowCommand)`；一个 `draft` 标志控制草稿保存或正式发布。Handler 将解析、校验、读取已有对象、领域行为和保存串起来，Controller 又追加回显查询。程序调用者因此容易把“创建一个领域对象”和“保存一个新版本”视作同一个操作。

领域中还有 `Flow.create/deploy` 静态入口，但当前生产 Handler 走的是解析后 `initialize`，修改已有草稿则走 `revise`。需要收敛已有构造入口，不需要再增加一套通用命令框架。

页面的新建弹窗立即调用保存接口；节点增删、移动及编排结构修改则操作本地定义数组，再生成 YAML。后端目前接收整份定义，`revise` 替换任务集合，没有对应逐节点编辑的领域入口。因此，“每个编排动作都访问数据库”不符合当前页面实现；问题是领域定义能力的调用方式与保存边界不够清楚。

证据：[Flow.create](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/domains/flows/Flow.java:105)、[Flow.initialize](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/domains/flows/Flow.java:310)、[PublishFlowHandler](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/services/flows/handlers/PublishFlowHandler.java:73)、[页面新建](/System/Volumes/Data/workspace/java/flow/server/src/main/resources/flow/flow.js:2723)、[页面编辑节点](/System/Volumes/Data/workspace/java/flow/server/src/main/resources/flow/flow.js:3378)。

## 操作链路

以下 HTTP 路径省略 Controller 的公共前缀；箭头表示当前调用顺序。

| 入口 | 动作 | 结果及主要开销 |
| --- | --- | --- |
| 打开页面 | 并行读取会话、类型、插件、草稿列表、执行列表 → 打开第一个草稿 | 页面等待两个完整业务列表，随后又读一次草稿详情 |
| 新建弹窗 → `POST /flows` | 组装空任务定义 → 保存命令 → 查草稿 → 内存初始化 → 分配版本并追加保存 → 回显 | 创建交互与第一次持久化绑定 |
| 画布增删、移动、配置节点 | 修改本地定义 → 重新生成 YAML → 标记未保存 | 本地操作，不逐节点发 SQL |
| `POST /flows/preview` | YAML 解析 → 定义回显 | 不访问 Repository；此入口不是完整发布校验 |
| `POST /flows`、`PUT /flows/{flowKey}` | 解析 → 查草稿 → `initialize/revise` → 查最大版本 → 追加保存 → 查正式定义回显 | 既有草稿实测 4 或 5 条 SQL |
| `GET /flows` | 查全部当前草稿 → 每条查询最新正式定义及其任务 | 随草稿数量逐条增加查询，且没有分页 |
| `GET /flows/{flowKey}` | 查草稿 → 查最新正式定义 → 恢复正式任务树 | 有正式版本时按源码为 3 条 SQL |
| `POST /flows/{flowKey}/deploy` | 查草稿源码 → 解析校验 → 查最新正式版本及任务 → 内存初始化 → 追加保存 → 再查草稿回显 | 已发布过的 Flow 实测 6 条 SQL |
| 发布未保存修改 | 先完成草稿保存请求 → 再完成发布请求 | 两次解析和保存；已有正式版本的两个 Controller 调用合计 11 条 SQL |
| 读取指定历史版本、定义 | 精确版本查询 → 恢复任务树；definition 接口再解析 source | definition 接口恢复任务树后主要使用源码，存在多余装配 |
| 删除草稿、正式定义 | 查目标 → `Flow.delete` → 分配新版本 → 追加删除快照 | 领域删除标记与历史保留；草稿按源码 3 条 SQL，正式定义 4 条 |
| `POST /flows/{key}/executions` | 读正式 Flow → 校验输入 → 持久化 Create 命令 → 消费时创建执行并推进 | 返回受理不等于所有节点完成；后续开销在消费链路 |
| 执行列表、详情 | 查 Execution 并恢复全部 TaskRun → 映射视图 | 列表没有分页；详情也带全部运行历史 |
| 暂停恢复、取消 | 读取并校验 → 命令入队 → 消费时重新判断并调用领域方法 → 保存、推进 | 接口返回当前快照；并发重读有正确性用途 |
| 退回候选、计划、执行 | 读 Execution 和绑定的 Flow → 计算路径 → 提交 Rewind → 消费时再次判断、保存、推进 | 全量恢复之外，还有路径重复计算；计划不是可以跨并发修改复用的写入许可 |
| 内部执行事件 | 读 Execution + Flow → 调度 → 保存 → 每个 Worker 前重读并保存 → 同步运行 → 重读合并结果并保存 → 必要时发下一事件 | 历史越长，每次检查点的读写越大 |
| 页面运行轮询 | 当前选中执行为 CREATED/RUNNING 时，上一请求完成后等待 1200 ms，再读其详情 | 反复传回该执行的完整 TaskRun 历史；不是每 1200 ms 读取整个租户列表 |

运行入口：[ExecutionController](/System/Volumes/Data/workspace/java/flow/server/src/main/java/org/cses/flow/controller/execution/ExecutionController.java:51)、[ExecutionService](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/services/executions/ExecutionService.java:106)、[执行推进](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/executor/handlers/ExecutorEventMessageHandler.java:213)。

## 已复现的放大

### 1. 草稿列表：1 + N + M 条 SQL

N 为草稿数量，M 为其中具有正式版本的数量。Core 查询草稿始终为 1 条 SQL；Controller 对每个草稿调用 `latestFlow`，每个已存在的正式版本还要读取其任务表。

| 草稿数 | 都没有正式版本 | 都有正式版本 |
| ---: | ---: | ---: |
| 1 | 2 | 3 |
| 2 | 3 | 5 |
| 10 | 11 | 21 |
| 50 | 51 | 101 |

`DraftView` 嵌入完整 `FlowView`，列表回显因此恢复所有正式任务树。入口：[FlowController.drafts](/System/Volumes/Data/workspace/java/flow/server/src/main/java/org/cses/flow/controller/flow/FlowController.java:42)、[draftView](/System/Volumes/Data/workspace/java/flow/server/src/main/java/org/cses/flow/controller/flow/FlowController.java:160)、[DraftView.from](/System/Volumes/Data/workspace/java/flow/server/src/main/java/org/cses/flow/controller/flow/FlowModels.java:97)。

### 2. 保存、发布：额外回显读取

样本为一个已有草稿、一个 Log 任务；有/无既有正式版本分别统计。直接调用正确构造的命令，不覆盖 HTTP 字段绑定。

| 操作 | 没有正式版本 | 已有正式版本 |
| --- | ---: | ---: |
| Core 保存草稿 | 3 | 3 |
| Controller 保存草稿 | 4 | 5 |
| Core 发布已保存草稿 | 4 | 5 |
| Controller 发布已保存草稿 | 5 | 6 |
| Core 直接提供 source 发布 | 3 | 4 |

例如已有正式版本的草稿保存：查草稿 1 + 查最大版本 1 + 追加写入 1 + 回显正式版本及任务 2 = 5。发布：查草稿源码 1 + 读最新正式版本及任务 2 + 查最大版本 1 + 追加写入 1 + 重读草稿 1 = 6。

页面 `deployFlow` 对未保存修改先调用 `saveDraft`，因此成功业务路径合计为 5 + 6 = 11。这是两个已测调用的加总，不是已完成的浏览器端到端耗时测量。正式定义合并稳定 Task ID 需要上一版本；不能把这次读取全当作浪费。

入口：[保存回显](/System/Volumes/Data/workspace/java/flow/server/src/main/java/org/cses/flow/controller/flow/FlowController.java:51)、[发布回显](/System/Volumes/Data/workspace/java/flow/server/src/main/java/org/cses/flow/controller/flow/FlowController.java:99)、[页面发布](/System/Volumes/Data/workspace/java/flow/server/src/main/resources/flow/flow.js:3149)。

### 3. 执行保存：一条 SQL 写入全部子记录

保存过完整快照后，仅将第一个 TaskRun 从 CREATED 改为 RUNNING，再次保存：

| TaskRun 总数 | SQL 条数 | SQL 字符数 | 绑定参数数 |
| ---: | ---: | ---: | ---: |
| 1 | 1 | 2,313 | 32 |
| 10 | 1 | 3,222 | 149 |
| 100 | 1 | 12,312 | 1,319 |
| 1000 | 1 | 103,212 | 13,019 |

Repository 对所有 TaskRun 构造 VALUES，冲突时更新所有字段，并对缺失的子记录执行删除检查。一条语句保证原子性，却不代表数据库只处理一条记录。参数值中的 JSON 内容还会增加实际传输、序列化和存储成本，未包含在 SQL 字符数中。

每次保存是随历史数量增长的 O(N) 工作；若执行增长期间发生 O(N) 次保存，累计会形成 O(N²) 的处理量。实际保存次数取决于 Task 类型、分支和事件推进，不在此将节点数直接等同于保存次数。

入口：[ExecutionRepositoryImpl.save](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/infrastructure/repositories/executions/ExecutionRepositoryImpl.java:98)。

### 4. 正式任务树恢复：O(N²) 扫描

`readTasks(entries, parentId, restored)` 每次递归都扫描所有 entries；即使当前节点是叶子，也重新扫描一次。

| Task 数量（平铺叶子） | 实测父子关系检查数 |
| ---: | ---: |
| 1 | 2 |
| 10 | 110 |
| 100 | 10,100 |
| 1000 | 1,001,000 |

这会叠加到每次正式 Flow 读取、列表回显和事件处理中。一次按 parentId 分组、按既有顺序恢复即可移除重复扫描，同时必须保留重复 ID、孤儿和循环检查。

入口：[FlowRepositoryImpl.readTasks](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/infrastructure/repositories/flows/FlowRepositoryImpl.java:242)。

## 由源码确认、尚未测量现场耗时的热点

1. **运行列表和详情负载过重。** `ExecutionRepository.findAll` 使用一条含子集合的 SQL，读取租户所有 Execution 和全部 TaskRun，没有分页。页面首次打开等待该接口；当前执行轮询反复加载整份历史。这里是数据量问题，不是 SQL N+1。[仓储查询](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/infrastructure/repositories/executions/ExecutionRepositoryImpl.java:61)、[页面初始化](/System/Volumes/Data/workspace/java/flow/server/src/main/resources/flow/flow.js:178)、[轮询](/System/Volumes/Data/workspace/java/flow/server/src/main/resources/flow/flow.js:4573)。
2. **内部事件消费通道同步等待 Worker。** 当前 DefaultExecutor 通过 Pulsar 方法监听进入内部处理器，同一消费者仍同步等待 Worker；多个应用实例竞争消费。
3. **原数据库队列事务成本已退出当前链路。** 本文原有 PostgresQueueStore/轮询器证据来自切换前版本；ADR 0094 改用 Pulsar，ADR 0096 已删除旧实现。该历史成本不能作为当前 Pulsar 链路的实测结论。
4. **事件推进反复恢复同一个完整领域。** 事件开始读取 Execution 和精确版本 Flow；每个 Worker 前重读 Execution，返回后再重读并合入结果。全量持久化与历史增长相乘。但 Worker 返回后的重读和存储版本冲突检查用于避免覆盖并行 Resume/Cancel，不能直接删除。[事件处理](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/executor/handlers/ExecutorEventMessageHandler.java:168)、[结果合并](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/executor/handlers/ExecutorEventMessageHandler.java:285)。
5. **退回路径重复计算。** 同一个 plan 调用先 validate，再通过 affectedTaskRunIds 重复 validate；候选之间还进行两两路径比较。先消除同一快照内的重复工作；跨宿主提交、排队和消费的重新校验仍必要。[计划](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/services/executions/ExecutionService.java:265)、[重复校验](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/services/executions/ExecutionService.java:505)、[候选比较](/System/Volumes/Data/workspace/java/flow/core/src/main/java/org/cses/flow/core/services/executions/ExecutionService.java:525)。

默认队列的 100 ms 等待发生在空轮询或失败后；成功消费后立即尝试下一条。本地发消息也会唤醒订阅。不能据此认定每个节点固定增加 100 ms。

## 建议的收敛顺序

1. **先处理列表和回显。** 列表改为分页摘要；批量取得正式版本元信息，不逐条恢复完整任务树。详情按选中项加载。保存、发布复用已经得到的结果，避免仅为回显再查库。若必须保持旧列表契约，先批量查询并恢复数据，再逐步改摘要接口。
2. **去掉任务树恢复的全表重复扫描。** 一次分组即可，不需要新增缓存服务。保留现有数据完整性检查。
3. **明确内存定义与保存边界。** 复用现有领域构造、修改和校验入口，允许宿主在内存组装完整编排后显式保存。新建 UI 可以先创建本地草稿。只有确有调用方需要逐节点领域编辑时，再补最少的编辑方法，避免一个属性一个命令、一次改动一次落库。
4. **将执行展示从运行聚合读取中分开。** 列表只读状态摘要；运行轮询读状态和必要变化，历史按需读取。运行器需要的完整领域快照仍保留。
5. **再评估运行保存与调度。** 保留完整聚合的业务契约、原子保存和 CAS，评估在仓储内减少未变化子记录的更新；不能把状态判断下沉为 SQL。若有慢 Worker，调整执行并发与消费边界，并验证同一执行的并发领取、重投和取消行为。简单开更多线程或提前 ACK 不足以保证正确性。

明确的目标边界是：**构造、编排、校验在内存完成 → 用例明确决定保存时机 → Repository 分配存储版本并原子保存。** 不必引入新的分层框架。

[ADR 0083](/System/Volumes/Data/workspace/java/flow/docs/decisions/0083-allocate-flow-version-in-repository.md) 明确规定每次保存追加版本及 Repository 分配版本；[ADR 0084](/System/Volumes/Data/workspace/java/flow/docs/decisions/0084-save-domain-snapshots-without-business-transactions.md) 明确规定完整快照、并发冲突检查和 Worker 前后边界。合并两次用户保存、改版本语义或改变运行存储协议，需要同步调整这些决策与真实 PostgreSQL 验证，不能作为无语义变化的小优化处理。

## 额外发现：页面字段契约不一致

页面新建和保存提交 `raw`，后端 `PublishFlowCommand` 接收 `source`；本次检查的命令未声明 `raw` 别名。预览已使用 `source`。这可能造成操作失败，需要独立核对 HTTP 契约；不是已测 SQL 变慢的证据。本探针直接构造正确命令，因此上述计数不代表这些页面请求已验收通过。

## 复现

本目录的两个文件只注册临时诊断 Gradle 任务，不修改生产构建、不连接数据库，也不写入 UC 测试报告目录。复用项目现有 JOOQ 测试映射配置，没有新增依赖。生成 class 位于 `server/build/flow-operation-cost/classes`。

在仓库根目录，使用能加载当前依赖的 JDK：

```bash
./gradlew --offline --console=plain \
  -I docs/harness/flow-operation-cost/probe.gradle \
  :server:flowQueryProbe
```

本次使用本机 Microsoft JDK 25.0.4。Java 21 在当前已解析依赖上因 JVM 兼容性要求失败；未修改项目 JDK 约定。离线运行要求本地依赖缓存已经齐备。

加上 `-Dprobe.check=true` 会在记录全部数据后，针对当前列表逐条查询抛出诊断断言，当前基线预期失败。普通模式运行成功并输出上述计数。该断言用于复现查询放大，不是 UC 或完整功能回归；调整查询形态后，Mock 返回分支也需相应维护。

后续测量实际耗时，应在独立测试库使用相同数据规模，分别记录 HTTP/用例耗时、SQL 次数与总时间、返回体大小、连接池等待、Worker 耗时和队列积压。旧 PostgreSQL 队列压测任务已删除；当前 Pulsar 验证环境见 [pulsar-queues.md](../pulsar-queues.md)，负载数据需针对当前传输重新测量。
