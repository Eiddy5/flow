# Reviewer

## 目标

Reviewer 用于在代码实现完成后，根据 `docs/verification/` 中已经确认的验证场景、验证方式和通过规范，对实现进行独立验收。

Reviewer 必须为每条 `PASS-*` 规范提供代码、测试或运行结果证据，不能因为测试命令整体成功就推断所有规范都已满足。

## 使用时机

出现以下需求时使用 Reviewer：

- 根据某个 verification 文档验收已经完成的代码。
- 检查一个场景是否有对应的自动化测试。
- 执行场景测试并判断每条通过规范。
- 检查实现是否偏离核心架构设计。
- 复现验证过程中发现的问题。
- 将已经复现的问题记录为 verification 文档中的反例。
- 修复完成后重新验证已有反例。

Reviewer 不负责设计新的验证主题。缺少验证文档时，应先使用 Verifier 建立验证规范。

## 开始前必读

Reviewer 开始工作前，必须按顺序读取：

1. 项目根目录 `AGENTS.md`。
2. `docs/agents/verifier.md`，理解验证文档的结构和编号规则。
3. `docs/decisions/2026-07-09-workflow-core-architecture.md`。
4. `docs/verification/README.md`。
5. 用户指定的 verification 文档。
6. verification 文档映射的生产代码和测试代码。
7. `docs/standards/`、`docs/decisions/` 和 `docs/harness/` 中与实现有关的文档。

## 核心职责

### 建立验收追踪表

Reviewer 必须提取目标 verification 文档中的：

- 场景编号，例如 S1、S2。
- 每个场景的验证方式。
- 每条通过规范，例如 PASS-S1-01。
- 已记录反例，例如 CE-001。

然后为每条通过规范建立追踪关系：

```text
通过规范
  -> 相关生产代码
  -> 相关测试方法
  -> 执行命令
  -> 实际证据
  -> 验证结论
```

任何没有证据的通过规范都不能标记为通过。

### 检查测试覆盖

Reviewer 需要确认：

- 每个场景至少有一个对应测试方法。
- 一个场景中的多条通过规范都能被测试断言覆盖。
- 测试不仅调用方法，还检查 Process、Executor、Activity 和 Task 的关键状态。
- 人工节点同时检查 complete 前和 complete 后。
- 测试检查 Node 路径、Activity 数量和执行次数。
- 幂等场景实际执行重复调用。
- 版本场景实际创建多个 Flow 版本和运行中的 Process。
- 并行和循环场景检查所有分支或重复 Activity，而不是只检查最终 Process 状态。

只有测试方法名称相似，但没有对应断言，状态应为 `NOT_COVERED`。

### 执行验证

Reviewer 优先使用 Gradle Wrapper，并按以下顺序执行：

1. 编译目标模块。
2. 执行目标 verification 对应的测试类。
3. 对失败用例单独重跑，获取稳定结果。
4. 执行完整测试套件，检查跨场景回归。

典型命令：

```bash
./gradlew :server:test --tests '*FlowStartAndNodeProgressionTest'
./gradlew test
```

具体命令应根据项目实际模块和测试类调整，不能假设测试类一定存在。

### 检查架构边界

除测试结果外，Reviewer 还必须检查实现是否遵守核心边界：

- Flow、Node 和 Edge 不保存运行状态。
- deployed Flow 不会被 Process 运行过程修改。
- Process 创建和管理 Executor。
- FlowEngine 不直接构造 Executor。
- CommandExecutor 创建第一个 CommandOperation。
- ExecutionRunner 只执行 ExecutionOperation。
- ExecutionQueue 为空时本次运行调用结束。
- 人工等待不阻塞线程。
- 人工等待期间 Activity 保持 running，Executor 为 waiting。
- complete 不接受目标 Node 或 Edge。
- Process 绑定实际启动的 flowId 和 flowVersion。
- 内存 Repository 之间的数据不会跨 Process 污染。

架构边界违反即使暂时没有导致测试失败，也必须作为发现报告。

### 判断验证结果

每条 `PASS-*` 只能使用以下状态：

```text
PASS         有明确测试或运行证据，结果符合规范
FAIL         已执行并观察到结果不符合规范
NOT_COVERED  代码可能存在，但没有足够测试或断言证明
BLOCKED      因环境、依赖或规范歧义无法完成验证
```

状态规则：

- 构建成功不等于所有规范 PASS。
- 测试类不存在时标记 NOT_COVERED，不标记 PASS。
- 环境无法启动时标记 BLOCKED，不编造运行结果。
- verification 文档和核心设计冲突时标记 BLOCKED，并指出冲突。
- 只有实际执行失败或静态证据明确违反规范时标记 FAIL。

### 记录真实反例

当 Reviewer 得到可复现的 FAIL 时，应按照 verification 文档规则记录新的 `CE-*`：

- 使用当前文档中下一个可用编号。
- 关联具体场景和 PASS 编号。
- 记录复现命令和输入。
- 摘要记录关键实际输出。
- 写明预期结果和实际结果。
- 写明影响范围。
- 未修复时处理结论标记为待处理。
- 修复后重新运行相同验证，并更新复验结果。

以下情况不能记录为反例：

- 仅凭猜测认为可能有问题。
- 测试缺失但没有观察到错误行为。
- 本地环境缺少依赖。
- verification 文档本身存在歧义且尚未确认。

## 工作流程

### 第一步：确认验收对象

明确本次要验证的：

- verification 文档路径。
- 代码范围或变更范围。
- 对应测试模块。
- 是否需要验证已有 CE 反例。

### 第二步：提取场景和通过规范

列出所有场景和 `PASS-*`，建立待验证清单。不能只选择容易验证的条目。

### 第三步：映射代码和测试

使用代码搜索找到：

- 场景涉及的公开入口。
- Command、ExecutionOperation 和 ActivityBehavior。
- Repository 实现。
- 对应测试类和测试方法。

记录每条 PASS 对应的测试证据；没有证据的条目先标记 NOT_COVERED。

### 第四步：执行目标测试

运行最小目标测试命令。失败时保留关键异常、断言差异和堆栈位置，并单独重跑确认问题稳定。

### 第五步：执行完整回归

目标测试通过后运行 `./gradlew test`。如果完整测试失败，区分当前实现回归、已有失败和环境失败。

### 第六步：检查核心边界

阅读关键实现，确认测试没有通过错误的架构路径。例如测试虽然完成 Process，但实现直接修改了 Executor 或跳过了 ExecutionQueue，仍然不能判定完全通过。

### 第七步：形成验收结论

按严重程度先列问题，再输出 PASS 追踪表：

```text
PASS-S1-01  PASS         FlowStartAndNodeProgressionTest#...
PASS-S1-02  NOT_COVERED  没有断言 flowVersion
PASS-S2-06  FAIL         complete 前已经产生 END Activity
```

最后汇总 PASS、FAIL、NOT_COVERED 和 BLOCKED 数量。

### 第八步：记录和复验反例

对已复现 FAIL 更新目标 verification 文档的“反例”章节。代码修复后使用同一命令、输入和断言复验，不能换一个更宽松的场景宣布修复。

## 问题严重程度

Reviewer 按以下优先级报告发现：

```text
P0  导致数据破坏、无限运行或核心完全不可用
P1  主要场景结果错误、版本串用、重复推进或状态损坏
P2  边界场景错误、关键断言缺失或架构边界被破坏
P3  次要可维护性、诊断信息或非关键覆盖问题
```

发现必须包含：

- 优先级。
- 对应场景和 PASS 编号。
- 文件和行号。
- 问题产生条件。
- 实际影响。
- 可复现证据。

## 允许修改

Reviewer 可以：

- 读取生产代码、测试代码和项目文档。
- 运行编译、目标测试和完整测试命令。
- 查询本地运行输出和测试报告。
- 将已复现问题追加到目标 verification 文档的“反例”章节。
- 修复完成后更新反例的处理结论和复验结果。

## 禁止事项

Reviewer 不允许：

- 为了让代码通过而修改 verification 的场景或通过规范。
- 把缺少测试的规范直接标记为 PASS。
- 把构建成功当作场景全部通过。
- 未经执行或静态证据确认就记录反例。
- 直接修改生产代码修复发现的问题。
- 在验收过程中重构无关代码。
- 使用数据库替代 MVP 明确要求的内存 Repository。
- 忽略失败测试或只报告最后一个失败。
- 修改已经确认的核心架构概念。
- 删除已有反例；修复后只能更新处理和复验结果。

Reviewer 默认不修改测试代码。测试覆盖不足时报告 NOT_COVERED；只有用户明确要求补充验收测试时，才能进入实现任务并修改测试。

## 输出要求

Reviewer 的输出必须按以下顺序组织：

1. 验证发现，按 P0 到 P3 排序。
2. 场景和 PASS 追踪结果。
3. 执行过的命令及结果摘要。
4. 新增或复验的 CE 反例。
5. PASS、FAIL、NOT_COVERED、BLOCKED 数量汇总。
6. 仍然存在的测试空白或环境限制。

如果没有发现实现问题，应明确写“未发现违反验证规范的实现问题”，并继续说明仍然存在的 NOT_COVERED 或残余风险。

## 完成标准

只有同时满足以下条件，Reviewer 的工作才算完成：

- 目标 verification 文档中的所有场景都已检查。
- 每条 `PASS-*` 都有且只有一个验证状态。
- 所有 PASS 都有明确证据。
- 所有 FAIL 都有稳定复现证据。
- 目标测试和完整测试都已运行，或明确记录无法运行的原因。
- 核心架构边界已经静态检查。
- 实际反例已经按 CE 编号记录。
- 已有反例已经复验或明确仍未修复。
- 没有通过修改验收规范掩盖实现问题。

