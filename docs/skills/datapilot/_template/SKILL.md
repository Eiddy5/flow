---
# 复制本模板后请重命名 name 字段并替换全部占位符
name: skill-name-placeholder
description: |
  <一句动词短语描述能力>。Use when the user asks to "<触发短语 1>",
  "<触发短语 2>", "<触发短语 3>", or works with files under
  <相关路径 glob>。Scope: <明确边界，避免与相邻 skill 重叠>。
argument-hint: "[arguments]"
allowed-tools: Read Grep Glob
paths: "src/main/java/org/dataPilot/**"
version: 0.1.0
---

# <Skill Title>

<!-- 一句话目的：用最简洁语言说明本 skill 解决的问题 -->

## When to Use

<!-- 列出触发场景与典型用户提问，与 frontmatter description 中关键词保持一致 -->

- "<典型用户提问 1>"
- "<典型用户提问 2>"
- 编辑路径 `<glob 模式>` 下的文件时

## Workflow

<!-- 有序步骤，描述本 skill 的标准操作流程，引用符号名而非行号 -->

1. 从 `docs/skills/SYNC_BASE.json` 确认套件覆盖的源码基准
2. 根据本 skill 内联契约定位入口类和方法
3. 按需阅读同目录的 `reference.md` 获取契约细节
4. 执行用户请求，所有引用使用类名或方法名

## Checklist

<!-- 必做项清单，Agent 应逐项确认 -->

- [ ] 套件同步基准已确认
- [ ] 入口符号已定位
- [ ] 输入合法性已校验
- [ ] 输出格式符合契约
- [ ] 引用全部使用符号名而非行号

## Example

<!-- 给出一个真实可执行的调用示例 -->

```text
User: <典型请求>
Skill: <预期输出概要>
```

## Common Mistakes

<!-- 表格形式记录易错点与对应修正 -->

| Mistake | Fix |
|---|---|
| 按行号引用代码 | 使用类名或方法名等概念性描述 |
| 忽略 skill 同步基准 | 先读取 `docs/skills/SYNC_BASE.json` |
| 忽略边界范围 | 仅处理 frontmatter `paths` 与 Scope 限定的代码 |
