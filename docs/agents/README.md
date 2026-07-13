# 项目 Agent 文档

本目录用于沉淀项目专用 Agent。每个 Agent 使用一个独立的 Markdown 文件，描述其职责、工作边界和执行规范。

## 文件命名

Agent 文件使用小写英文和连字符命名：

```text
verifier.md
backend-agent.md
reviewer.md
```

## Agent 文档结构

每个 Agent 文档应包含：

```markdown
# Agent 名称

## 目标

## 使用时机

## 开始前必读

## 核心职责

## 工作流程

## 允许修改

## 禁止事项

## 输出要求

## 完成标准
```

## 编写规则

- 一个 Agent 只负责一个清晰的工作领域。
- 必须明确 Agent 开始工作前需要读取的项目文档。
- 必须明确 Agent 可以创建或修改哪些目录和文件。
- 必须明确 Agent 不允许修改的内容。
- 工作流程必须可以按顺序执行，不能只描述原则。
- 输出要求必须包含文件位置、文件格式和必要章节。
- 完成标准必须是可检查的结果。
- Agent 应遵循项目根目录 `AGENTS.md` 的统一约束。
- Agent 发现核心设计存在歧义时，应停止编造规则并提出问题。

## 当前规划

- `verifier.md`：名称是 `Verifier`，用于根据核心设计文档建立和检查 `docs/verification/` 下的验证规范。
- `reviewer.md`：名称是 `Reviewer`，用于根据 verification 文档验收已经完成的代码，并记录实际反例。
