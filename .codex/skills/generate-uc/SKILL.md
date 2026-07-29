---
name: generate-uc
description: 将 UC Agent 从已确认用户需求中提炼的流程编排、流程运行或任务类型场景整理为可验证的四章节 UC Markdown。适用于创建或更新单个 UC、补齐 Pause 等具体 Task 类型的 UC、形成完整流程用户闭环、维护 UC 索引并执行分类感知校验；不从整份 PRD 拆分 UC，也不根据架构、代码或测试结果定义预期。
---

# 生成真实用户场景 UC

把 UC Agent 提供的结构化场景契约转换为精简 UC。已确认需求及其内部追踪只用于
生成前审查，不写入最终 UC。

## 选择生成模式

从契约读取 `kind`，只加载对应参考：

- `ORCHESTRATION`：读取
  [流程编排覆盖](references/orchestration-coverage.md)。
- `EXECUTION`：读取
  [流程运行覆盖](references/execution-coverage.md)。
- `TASK_TYPE`：读取
  [任务类型覆盖](references/task-type-coverage.md)，并要求
  `targetTaskType`。

用户要求“补齐 Pause task 的 UC”或同义表达时，使用 `TASK_TYPE` 模式和
`targetTaskType: PAUSE`。先与已有 UC 做增量差异审计，不删除、改写或复制仍有效
场景。

## 输出硬约束

- UC 只描述用户场景，不验证技术架构、领域内部设计或具体实现。
- 只有已确认用户需求能够定义 UC 场景和预期。
- 最终 UC 只保留验证标识、验证目标、验证场景和验证方式四个二级章节。
- 只描述真实用户能执行的动作和能观察的业务结果。
- 把内部领域名、接口名、状态载体和持久化术语转换为用户语言。
- 每个场景必须查询结果并在本场景内到达终态或完成清理。
- 验证标识写明 `UC 类型`；任务类型 UC 还要写明 `目标任务类型`。

## 加载输入

1. 阅读项目根目录 `AGENTS.md`、`docs/uc/README.md`、目标领域
   `docs/uc/<domain>/README.md`。
2. 阅读 [场景契约](references/scenario-contract.md) 和
   [UC 模板](references/uc-template.md)。需要成品对照时，流程运行模式读取
   [通用示例](references/example-uc.md)，任务类型模式读取
   [任务类型示例](references/example-task-type-uc.md)。
3. 根据 `kind` 阅读且只阅读对应覆盖参考。
4. 阅读契约引用的已确认用户需求，优先采用用户最新确认规则。
5. 不使用技术架构、ADR、领域模型、生产代码、测试代码、测试报告或实现缺口定义
   正确行为。
6. Flow 运行类 UC 额外遵守
   `docs/standards/uc-testing.md#真实用户操作完整路径`。

领域、编号、目标文件和场景边界必须先由 UC Agent 确认。

## 内部审查

生成前在工作过程中确认：

- 每个场景都有已确认用户需求依据。
- 未确认问题不会改变场景结果。
- 已按当前生成模式的覆盖参考审计适用场景。
- 与其他 UC 类型重叠的候选场景已有唯一主要归属。
- 每个场景可以独立开始、独立验证并独立收尾。

覆盖参考用于发现候选场景，不能自行定义业务结果。超时、认领、转派、重试等行为
缺少确认规则时，先集中询问用户；确认前不写入 `ACCEPTED` UC。

这些需求来源和审查结果不输出到 UC Markdown。发现架构或实现与场景冲突时，在
交付说明中报告，但不修改 UC 适配架构或实现。

## 编写 UC

最终文档只允许四个二级章节：

1. `验证标识`
2. `验证目标`
3. `验证场景`
4. `验证方式`

不得增加规范来源、不变量、前置条件、PASS、追踪表、待确认事项、变更记录、能力
缺口或反例章节，也不得把这些信息塞入保留章节规避格式约束。

### 验证场景

每个 `S*` 场景使用业务语言描述：

- `参与用户`
- `开始状态`
- `验证流程`（仅任务类型 UC）
- `用户操作`
- `预期结果`
- `场景结束`

不在 UC 中写类、方法、Service、Handler、Repository、数据库、Mock、测试类、
代码路径、ADR、架构不变量或代码块。领域对象的内部名称也应转换为用户能理解的
业务概念。

异常场景必须同时描述拒绝结果和用户可观察到的无副作用结果。场景创建的草稿、
流程、任务或其他业务资源必须在本场景结束时到达明确终态或完成清理。

Flow PAUSE 场景必须描述被外派用户如何查询自己的具体任务、完成查询所得任务、
继续查询后续等待任务，并最终查询到流程终态，不能停在等待状态。

任务类型 UC 的 `验证流程` 必须是可由用户定义和发布的完整最小流程，并明确唯一
目标 Task。每个场景独立定义并发布流程、启动实例、观察目标 Task 结果、证明结果
被后续流程消费，再查询流程终态。不能脱离流程直接验证 Task，也不能把通用编排或
运行行为复制到任务类型 UC。

### 验证方式

每个场景有同编号验证方式，按用户真实操作顺序说明：

1. 用户准备或选择什么业务数据。
2. 用户从哪个业务界面或公开能力执行操作。
3. 用户如何查询并识别目标对象。
4. 用户观察哪些结果和禁止副作用。
5. 用户如何把本场景推进到结束状态。

验证方式可以被自动化测试或人工验证执行，但不描述测试实现。

## 写入与校验

按模板写入 UC Agent 指定的文件，并更新领域 `README.md`。不要修改生产代码、
测试代码或测试报告。

运行：

```bash
python3 .codex/skills/generate-uc/scripts/validate_uc.py \
  --strict --require-classification "docs/uc/<domain>/<UC-ID> <主题>.md"
```

再运行：

```bash
git diff --check -- <modified-files>
```

Skill 自身变化时额外运行：

```bash
uv run --with pyyaml python \
  "${CODEX_HOME:-$HOME/.codex}/skills/.system/skill-creator/scripts/quick_validate.py" \
  .codex/skills/generate-uc
```

## 交付

说明用户需求基线、UC 路径、场景数量、用户闭环覆盖、校验结果，以及未写入 UC
的未确认需求。
