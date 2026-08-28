# Flow UC 能力缺口

本目录保留历史 UC 实现能力缺口及其处理记录。这些记录只说明过去的实现状态，
不能定义或修改当前 UC 用户场景。

- 缺口文档用于说明为什么某些 `PASS-*` 曾缺少实现保障，以及相应的关闭证据。
- 静态缺口不等于测试已经失败。
- Test Agent 仍需按 UC 执行或评估测试，并在
  `docs/test-reports/flow/` 中记录 PASS、FAIL、NOT_COVERED 或 BLOCKED。
- 能力实现后保留缺口文档，更新状态、处理结论和对应复验报告，不直接删除历史。

## 清单

| 文档 | 原主要缺口 | 状态 |
| --- | --- | --- |
| [UC-01-gap.md](UC-01-gap.md) | FlowDraft/Flow 目标模型迁移与并发写协议 | RESOLVED |
| [UC-02-gap.md](UC-02-gap.md) | Execution 状态竞争保护 | RESOLVED |
| [UC-04-gap.md](UC-04-gap.md) | Pause 恢复与 Flow 推进原子性 | RESOLVED |
| [UC-05-gap.md](UC-05-gap.md) | 并行与汇合运行能力 | RESOLVED |
| [UC-06-gap.md](UC-06-gap.md) | 条件路由运行能力 | RESOLVED |
